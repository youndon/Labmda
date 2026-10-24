package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
import com.zouitel.presentaion.model.OrderType
import com.zouitel.presentaion.screens.orders.OrderStatusRepository
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.DataSnapshot
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.collections.emptyList

class FoodOrdersScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val currentUid = Firebase.auth.currentUser?.uid

    private val _orders = MutableStateFlow<List<Order>>(emptyList())
    val orders: StateFlow<List<Order>> = _orders.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        listenToIncomingOrders()
    }

    private fun listenToIncomingOrders() {
        screenModelScope.launch(Dispatchers.IO) {
            val uid = currentUid
            if (uid.isNullOrBlank()) {
                _orders.value = emptyList()
                _error.value = "Please sign in to view restaurant orders."
                _isLoading.value = false
                return@launch
            }

            db.child("orders")
                .orderByChild("merchantId")
                .equalTo(uid)
                .valueEvents
                .onEach { _error.value = null }
                .catch { cause ->
                    _orders.value = emptyList()
                    _error.value = cause.message?.takeIf { it.isNotBlank() }
                        ?: "Could not load restaurant orders. Check the /orders query rules."
                    _isLoading.value = false
                }
                .collect { snapshot ->
                    _orders.value = snapshot.children
                        .mapNotNull { it.toOrderOrNull() }
                        // Food orders only; retail shop orders live in the shop dashboard.
                        .filter { it.orderType == OrderType.FOOD }
                        .sortedByDescending { it.createdAtTimestamp }
                    _isLoading.value = false
                }
        }
    }

    fun updateOrderStatus(order: Order, nextStatus: OrderStatus) {
        // The courier alone can mark an order delivered. The callable function also validates
        // ownership and status transitions on the server.
        if (nextStatus == OrderStatus.DELIVERED) return
        if (order.id.isBlank()) {
            _error.value = "Cannot update an order without an ID."
            return
        }

        screenModelScope.launch(Dispatchers.IO) {
            try {
                OrderStatusRepository().updateStatus(order.id, nextStatus.name)
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: "Could not update the order status. Please try again."
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
