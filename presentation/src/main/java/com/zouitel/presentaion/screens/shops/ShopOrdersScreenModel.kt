package com.zouitel.presentaion.screens.shops

import com.zouitel.presentaion.data.repository.OrdersRepository
import dev.gitlive.firebase.auth.auth
import com.zouitel.presentaion.payments.ServiceActionsRepository
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ShopOrdersScreenModel(
    private val ordersRepository: OrdersRepository,
    private val serviceActions: ServiceActionsRepository = ServiceActionsRepository()
) : ScreenModel {
    private val currentUid = dev.gitlive.firebase.Firebase.auth.currentUser?.uid

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
            ordersRepository.observeShopOrders(uid).collect {
                _orders.value = it
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