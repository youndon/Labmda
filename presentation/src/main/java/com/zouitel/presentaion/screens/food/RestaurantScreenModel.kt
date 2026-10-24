package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.OrderType
import com.zouitel.presentaion.screens.orders.OrderCreationRepository
import com.zouitel.presentaion.screens.orders.RequestedOrderItem
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
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
            repository.getMealsForRestaurant(restaurant.id)
                .onEach { _error.value = null }
                .catch { cause ->
                    _error.value = cause.message?.takeIf { it.isNotBlank() }
                        ?: "Could not load this restaurant's menu. Check your database permissions."
                    _isLoading.value = false
                    emit(emptyList())
                }
                .collect { list ->
                    _meals.value = list
                    _isLoading.value = false
                }
        }
    }

    fun addMeal(mealId: String) {
        if (mealId.isBlank()) return
        _quantities.value = _quantities.value + (mealId to ((_quantities.value[mealId] ?: 0) + 1).coerceAtMost(99))
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
     * Sends catalog IDs and quantities only. The trusted function reloads prices, delivery fees,
     * restaurant approval, and availability before creating the order.
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
        if (lines.isEmpty()) {
            _error.value = "Your cart is empty."
            return
        }
        if (dropoffAddress.isBlank() || !dropoffLat.isFinite() || !dropoffLng.isFinite() ||
            dropoffLat !in -90.0..90.0 || dropoffLng !in -180.0..180.0
        ) {
            _error.value = "Enter a valid delivery address and location."
            return
        }

        _error.value = null
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: "Could not place the order. Please try again."
            } finally {
                _isSubmitting.value = false
            }
        }
    }
}
