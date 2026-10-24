package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.OrderType
import com.zouitel.presentaion.screens.orders.OrderCreationRepository
import com.zouitel.presentaion.screens.orders.RequestedOrderItem
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
     * Sends catalog IDs and quantities only. The trusted function reloads prices, delivery fees
     * and merchant approval, then writes both the order and its payment record.
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
                val orderId = OrderCreationRepository().createOrder(
                    orderType = OrderType.FOOD,
                    sourceId = restaurant.id,
                    items = lines.map { meal ->
                        RequestedOrderItem(meal.id, (_quantities.value[meal.id] ?: 1).coerceIn(1, 99))
                    },
                    dropoffLocation = dropoffAddress,
                    dropoffLat = dropoffLat,
                    dropoffLng = dropoffLng,
                    paymentMethod = paymentMethod
                )
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