package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.CartItem
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderType
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RestaurantScreenModel(
    val restaurant: Restaurant,
    private val repository: FoodRepository = FoodRepository()
) : ScreenModel {

    private val db = Firebase.database.reference()
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
        val uid = auth.currentUser?.uid
        if (uid == null) {
            _error.value = "Please sign in to place an order."
            return
        }
        if (lines.isEmpty()) return

        _isSubmitting.value = true
        screenModelScope.launch {
            try {
                val orderRef = db.child("orders").push()
                val orderId = orderRef.key ?: System.currentTimeMillis().toString()

                val items = lines.map { meal ->
                    CartItem(
                        id = meal.id,
                        name = meal.name,
                        basePrice = meal.basePrice,
                        quantity = _quantities.value[meal.id] ?: 1
                    )
                }
                val subtotal = items.sumOf { it.totalPrice }
                val fee = restaurant.deliveryFee

                val order = Order(
                    id = orderId,
                    clientId = uid,
                    orderType = OrderType.FOOD,
                    sourceId = restaurant.id,
                    sourceName = restaurant.name,
                    items = items,
                    subtotal = subtotal,
                    deliveryFee = fee,
                    totalPrice = subtotal + fee,
                    pickupLocation = restaurant.address,
                    pickupLat = restaurant.latitude,
                    pickupLng = restaurant.longitude,
                    dropoffLocation = dropoffAddress,
                    dropoffLat = dropoffLat,
                    dropoffLng = dropoffLng,
                    createdAtTimestamp = System.currentTimeMillis(),
                    paymentMethod = paymentMethod
                )

                orderRef.setValue(order)
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