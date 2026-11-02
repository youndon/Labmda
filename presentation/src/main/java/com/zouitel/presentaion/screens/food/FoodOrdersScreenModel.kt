package com.zouitel.presentaion.screens.food

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

class FoodOrdersScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val currentUid = Firebase.auth.currentUser?.uid

    private val _orders = MutableStateFlow<List<Order>>(emptyList())
    val orders: StateFlow<List<Order>> = _orders.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToIncomingOrders()
    }

    private fun listenToIncomingOrders() {
        screenModelScope.launch(Dispatchers.IO) {
            val uid = currentUid ?: run { _isLoading.value = false; return@launch }
            db.child("orders").orderByChild("sourceId").equalTo(uid).valueEvents.collect { snapshot ->
                _orders.value = snapshot.children
                    .mapNotNull { it.toOrderOrNull() }
                    // Food orders only; retail shop orders live in the shop dashboard
                    .filter { it.orderType == OrderType.FOOD }
                    // A restaurant's id is its manager's uid, so show only this restaurant's orders
                    .sortedByDescending { it.createdAtTimestamp }
                _isLoading.value = false
            }
        }
    }

    fun updateOrderStatus(order: Order, nextStatus: OrderStatus) {
        // Restaurant managers cannot mark DELIVERED; only the courier can
        if (nextStatus == OrderStatus.DELIVERED) return

        screenModelScope.launch(Dispatchers.IO) {
            com.zouitel.presentaion.screens.trustedTransactionCall("transitionOrderStatus", mapOf("orderId" to order.id, "nextStatus" to nextStatus.name))
        }
    }
}