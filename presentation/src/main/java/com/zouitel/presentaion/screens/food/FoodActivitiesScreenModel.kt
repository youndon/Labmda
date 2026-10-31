package com.zouitel.presentaion.screens.food

import com.zouitel.presentaion.payments.ServiceActionsRepository

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
import com.zouitel.presentaion.model.OrderType
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class FoodActivitiesScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val serviceActions = ServiceActionsRepository()
    private val currentUserId = Firebase.auth.currentUser?.uid

    private val _orders = MutableStateFlow<List<Order>>(emptyList())
    val orders: StateFlow<List<Order>> = _orders.asStateFlow()

    private val _refundNotice = MutableStateFlow<String?>(null)
    val refundNotice: StateFlow<String?> = _refundNotice.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToUserOrders()
    }

    private fun listenToUserOrders() {
        val uid = currentUserId
        if (uid.isNullOrBlank()) {
            _orders.value = emptyList()
            _isLoading.value = false
            return
        }
        screenModelScope.launch(Dispatchers.IO) {
            db.child("orders").orderByChild("clientId").equalTo(uid).valueEvents.collect { snapshot ->
                _orders.value = snapshot.children
                    .mapNotNull { it.toOrderOrNull() }
                    .filter { it.orderType == OrderType.FOOD }
                    .sortedByDescending { it.createdAtTimestamp }
                _isLoading.value = false
            }
        }
    }
    fun cancelOrder(orderId: String) {
        screenModelScope.launch(Dispatchers.IO) {
            runCatching { serviceActions.transitionOrderStatus(orderId, OrderStatus.CANCELLED.name) }
                .onFailure { _refundNotice.value = it.message ?: "Could not cancel this order." }
        }
    }

    fun requestRefund(orderId: String) {
        screenModelScope.launch(Dispatchers.IO) {
            runCatching { serviceActions.requestPaymentRefund("ORDER", orderId, UUID.randomUUID().toString()) }
                .onSuccess { _refundNotice.value = "Refund request submitted for administrator review. Your order has not been cancelled." }
                .onFailure { _refundNotice.value = it.message ?: "Could not submit refund request." }
        }
    }
}