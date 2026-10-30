package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.payments.PaymentCreationResult
import com.zouitel.presentaion.payments.PaymentRepository
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class RestaurantScreenModel(
    val restaurant: Restaurant,
    private val repository: FoodRepository = FoodRepository(),
    private val payments: PaymentRepository = PaymentRepository()
) : ScreenModel {

    private val auth = Firebase.auth
    private var pendingOrderFingerprint: String? = null
    private var pendingOrderIdempotencyKey: String? = null

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
        pendingOrderFingerprint = null
        pendingOrderIdempotencyKey = null
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Only catalog IDs and quantities are sent to the backend. It reads the current catalog
     * prices and delivery fee from Realtime Database and creates the order/payment atomically.
     */
    fun placeOrder(
        lines: List<Meal>,
        paymentMethod: String,
        dropoffAddress: String,
        dropoffLat: Double,
        dropoffLng: Double,
        onSuccess: (PaymentCreationResult) -> Unit,
        onFailure: (String) -> Unit = {}
    ) {
        if (_isSubmitting.value) return
        val uid = auth.currentUser?.uid
        if (uid == null) {
            _error.value = "Please sign in to place an order."
            return
        }
        if (lines.isEmpty()) return

        val fingerprint = buildString {
            append(paymentMethod.trim().uppercase()).append('|')
            append(dropoffAddress).append('|')
            append(dropoffLat).append('|').append(dropoffLng)
            lines.sortedBy { it.id }.forEach { meal ->
                append('|').append(meal.id).append(':').append(_quantities.value[meal.id] ?: 1)
            }
        }
        if (pendingOrderFingerprint != fingerprint) {
            pendingOrderFingerprint = fingerprint
            pendingOrderIdempotencyKey = UUID.randomUUID().toString()
        }
        val idempotencyKey = pendingOrderIdempotencyKey ?: UUID.randomUUID().toString().also {
            pendingOrderIdempotencyKey = it
            pendingOrderFingerprint = fingerprint
        }

        _isSubmitting.value = true
        screenModelScope.launch {
            try {
                val items = lines.map { meal ->
                    mapOf(
                        "id" to meal.id,
                        "quantity" to (_quantities.value[meal.id] ?: 1),
                        "specialInstructions" to ""
                    )
                }
                val result = payments.createOrder(
                    idempotencyKey = idempotencyKey,
                    orderType = "FOOD",
                    sourceId = restaurant.id,
                    items = items,
                    paymentMethod = paymentMethod,
                    pickupLocation = restaurant.address,
                    pickupLat = restaurant.latitude,
                    pickupLng = restaurant.longitude,
                    dropoffLocation = dropoffAddress,
                    dropoffLat = dropoffLat,
                    dropoffLng = dropoffLng
                )
                clearCart()
                onSuccess(result)
            } catch (e: Exception) {
                val message = e.message ?: "Could not place the order. Please try again."
                _error.value = message
                onFailure(message)
            } finally {
                _isSubmitting.value = false
            }
        }
    }
}