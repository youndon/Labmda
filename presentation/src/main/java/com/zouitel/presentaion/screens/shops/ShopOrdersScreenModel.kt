package com.zouitel.presentaion.screens.shops

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
import com.zouitel.presentaion.screens.food.toOrderOrNull
import com.zouitel.presentaion.screens.orders.OrderStatusRepository
import com.zouitel.presentaion.model.OrderType
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ShopOrdersScreenModel : ScreenModel {
    private val db = Firebase.database.reference()

    private val _orders = MutableStateFlow<List<Order>>(emptyList())
    val orders: StateFlow<List<Order>> = _orders.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToShopOrders()
    }

    private fun listenToShopOrders() {
        val uid = Firebase.auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            _isLoading.value = false
            return
        }
        screenModelScope.launch {
            db.child("orders").orderByChild("merchantId").equalTo(uid).valueEvents.collect { snapshot ->
                _orders.value = snapshot.children
                    .mapNotNull { it.toOrderOrNull() }
                    .filter { it.orderType == OrderType.RETAIL }
                    .sortedByDescending { it.createdAtTimestamp }
                _isLoading.value = false
            }
        }
    }

    fun updateOrderStatus(order: Order, nextStatus: OrderStatus) {
        if (nextStatus == OrderStatus.DELIVERED) return
        screenModelScope.launch {
            runCatching { OrderStatusRepository().updateStatus(order.id, nextStatus.name) }
        }
    }

}