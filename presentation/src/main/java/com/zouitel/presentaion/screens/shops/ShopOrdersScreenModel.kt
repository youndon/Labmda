package com.zouitel.presentaion.screens.shops

import com.zouitel.presentaion.payments.ServiceActionsRepository
import com.zouitel.presentaion.screens.food.toOrderOrNull

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
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
    private val serviceActions = ServiceActionsRepository()
    private val currentUid = Firebase.auth.currentUser?.uid

    private val _orders = MutableStateFlow<List<Order>>(emptyList())
    val orders: StateFlow<List<Order>> = _orders.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToShopOrders()
    }

    private fun listenToShopOrders() {
        val uid = currentUid
        if (uid.isNullOrBlank()) {
            _orders.value = emptyList()
            _isLoading.value = false
            return
        }
        screenModelScope.launch {
            db.child("orders").orderByChild("merchantId").equalTo(uid).valueEvents.collect { snapshot ->
                val fetchedOrders = snapshot.children
                    .mapNotNull { it.toOrderOrNull(defaultOrderType = OrderType.RETAIL) }
                    .sortedByDescending { it.createdAtTimestamp }

                _orders.value = fetchedOrders
                _isLoading.value = false
            }
        }
    }

    fun updateOrderStatus(order: Order, nextStatus: OrderStatus) {
        if (nextStatus == OrderStatus.DELIVERED) return

        screenModelScope.launch {
            serviceActions.transitionOrderStatus(order.id, nextStatus.name)
        }
    }
}