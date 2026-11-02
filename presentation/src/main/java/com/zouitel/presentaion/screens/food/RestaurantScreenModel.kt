package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RestaurantScreenModel(
    val restaurant: Restaurant,
    private val repository: FoodRepository = FoodRepository()
) : ScreenModel {

    private val auth = Firebase.auth

    private val _meals = MutableStateFlow<List<Meal>>(emptyList())
    val meals: StateFlow<List<Meal>> = _meals.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** mealId -> quantity */
    private val _quantities = MutableStateFlow<Map<String, Int>>(emptyMap())
    val quantities: StateFlow<Map<String, Int>> = _quantities.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        screenModelScope.launch {
            repository.getMealsForRestaurant(restaurant.id).collect { list ->
                _meals.value = list
                _isLoading.value = false
            }
        }
    }

    fun addMeal(mealId: String) {
        _quantities.value = _quantities.value + (mealId to ((_quantities.value[mealId] ?: 0) + 1))
    }

    fun removeMeal(mealId: String) {
        val current = _quantities.value[mealId] ?: return
        _quantities.value =
            if (current <= 1) _quantities.value - mealId
            else _quantities.value + (mealId to current - 1)
    }

    fun clearCart() {
        _quantities.value = emptyMap()
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Pickup is always the restaurant's saved location; drop-off is the point the client
     * picked on the map. Totals are computed here so the stored order is the source of truth.
     */
    fun placeOrder(
        lines: List<Meal>,
        paymentMethod: String,
        dropoffAddress: String,
        dropoffLat: Double,
        dropoffLng: Double,
        onSuccess: (String) -> Unit
    ) {
        if (_isSubmitting.value) return
        if (auth.currentUser?.uid == null) {
            _error.value = "Please sign in to place an order."
            return
        }
        if (lines.isEmpty()) return

        _isSubmitting.value = true
        screenModelScope.launch {
            try {
                val method = if (paymentMethod.contains("GCASH", ignoreCase = true)) "GCASH" else "CASH"
                val quote = com.zouitel.presentaion.screens.trustedTransactionCallData(
                    "quoteFoodOrder",
                    mapOf(
                        "sourceId" to restaurant.id,
                        "items" to lines.map { meal ->
                            mapOf("itemId" to meal.id, "quantity" to (_quantities.value[meal.id] ?: 1))
                        },
                        "pickup" to mapOf("latitude" to restaurant.latitude, "longitude" to restaurant.longitude),
                        "dropoff" to mapOf("latitude" to dropoffLat, "longitude" to dropoffLng),
                        "pickupAddress" to restaurant.address,
                        "dropoffAddress" to dropoffAddress,
                        "paymentMethod" to method
                    )
                )
                val quoteId = quote["quoteId"] as? String
                    ?: throw IllegalStateException("The server did not return an order quote.")
                val created = com.zouitel.presentaion.screens.trustedTransactionCallData(
                    "createOrderFromQuote",
                    mapOf("quoteId" to quoteId, "kind" to "FOOD")
                )
                val orderId = created["orderId"] as? String
                    ?: throw IllegalStateException("The server did not return the new order ID.")
                clearCart()
                onSuccess(orderId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not place the order. Please try again."
            } finally {
                _isSubmitting.value = false
            }
        }
    }
}