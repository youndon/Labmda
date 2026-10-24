package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
import com.zouitel.presentaion.model.OrderType
import com.zouitel.presentaion.screens.orders.OrderStatusRepository
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class FoodActivitiesScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val currentUserId = Firebase.auth.currentUser?.uid

    private val _orders = MutableStateFlow<List<Order>>(emptyList())
    val orders: StateFlow<List<Order>> = _orders.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        listenToUserOrders()
    }

    private fun listenToUserOrders() {
        screenModelScope.launch(Dispatchers.IO) {
            val uid = currentUserId
            if (uid.isNullOrBlank()) {
                _orders.value = emptyList()
                _error.value = "Please sign in to view your food orders."
                _isLoading.value = false
                return@launch
            }

            db.child("orders")
                .orderByChild("clientId")
                .equalTo(uid)
                .valueEvents
                .onEach { _error.value = null }
                .catch { cause ->
                    _orders.value = emptyList()
                    _error.value = cause.message?.takeIf { it.isNotBlank() }
                        ?: "Could not load your food orders. Check the /orders query rules."
                    _isLoading.value = false
                }
                .collect { snapshot ->
                    _orders.value = snapshot.children
                        .mapNotNull { it.toOrderOrNull() }
                        .filter { it.orderType == OrderType.FOOD }
                        .sortedByDescending { it.createdAtTimestamp }
                    _isLoading.value = false
                }
        }
    }

    fun cancelOrder(orderId: String) {
        if (orderId.isBlank()) {
            _error.value = "Cannot cancel an order without an ID."
            return
        }

        screenModelScope.launch(Dispatchers.IO) {
            try {
                // Trusted Cloud Function verifies that this order belongs to the current user
                // and that cancellation is permitted for its current status.
                OrderStatusRepository().updateStatus(orderId, OrderStatus.CANCELLED.name)
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: "Could not cancel the order. Please try again."
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
