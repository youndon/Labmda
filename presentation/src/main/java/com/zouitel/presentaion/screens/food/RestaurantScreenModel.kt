package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import com.zouitel.presentaion.model.CartItem
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
    private val _specialInstructions = MutableStateFlow<Map<String, String>>(emptyMap())

    fun setMealDetails(item: CartItem) {
        _quantities.value = _quantities.value + (item.id to item.quantity.coerceAtLeast(1))
        _specialInstructions.value = _specialInstructions.value + (item.id to item.specialInstructions)
    }

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
        _specialInstructions.value = emptyMap()
    }

    fun clearError() {
        _error.value = null
    }

    fun requestOrderQuote(
        lines: List<Meal>,
        paymentMethod: String,
        dropoffAddress: String,
        dropoffLat: Double,
        dropoffLng: Double,
        onSuccess: (Map<String, Any?>) -> Unit,
        onError: (String) -> Unit
    ) {
        if (auth.currentUser?.uid == null || lines.isEmpty()) {
            onError("Please sign in and add items before requesting a quote.")
            return
        }
        if (_isSubmitting.value) return
        _isSubmitting.value = true
        screenModelScope.launch {
            try {
                val method = if (paymentMethod.contains("GCASH", ignoreCase = true)) "GCASH" else "CASH"
                val quote = com.zouitel.presentaion.screens.trustedTransactionCallData(
                    "quoteFoodOrder",
                    mapOf(
                        "sourceId" to restaurant.id,
                        "items" to lines.map { meal ->
                            mapOf("itemId" to meal.id, "quantity" to (_quantities.value[meal.id] ?: 1), "specialInstructions" to (_specialInstructions.value[meal.id] ?: ""))
                        },
                        "pickup" to mapOf("latitude" to restaurant.latitude, "longitude" to restaurant.longitude),
                        "dropoff" to mapOf("latitude" to dropoffLat, "longitude" to dropoffLng),
                        "pickupAddress" to restaurant.address,
                        "dropoffAddress" to dropoffAddress,
                        "paymentMethod" to method
                    )
                )
                if (quote["quoteId"] !is String) error("The server did not return an order quote.")
                onSuccess(quote)
            } catch (e: Exception) {
                onError(e.message ?: "Could not calculate the order total.")
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun confirmQuotedOrder(quoteId: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        if (_isSubmitting.value) return
        _isSubmitting.value = true
        screenModelScope.launch {
            try {
                val created = com.zouitel.presentaion.screens.trustedTransactionCallData(
                    "createOrderFromQuote", mapOf("quoteId" to quoteId, "kind" to "FOOD")
                )
                val orderId = created["orderId"] as? String ?: error("The server did not return the new order ID.")
                clearCart()
                onSuccess(orderId)
            } catch (e: Exception) {
                onError(e.message ?: "Could not confirm the order. Request a fresh quote and try again.")
            } finally {
                _isSubmitting.value = false
            }
        }
    }

}