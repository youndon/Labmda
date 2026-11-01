package com.zouitel.presentaion.screens.shops

import com.zouitel.presentaion.data.repository.OrdersRepository
import com.zouitel.presentaion.payments.ServiceActionsRepository
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class ShopActivitiesScreenModel(
    private val ordersRepository: OrdersRepository
) : ScreenModel {
    private val serviceActions = ServiceActionsRepository()
    private val currentUserId = dev.gitlive.firebase.Firebase.auth.currentUser?.uid

    private val _activities = MutableStateFlow<List<Order>>(emptyList())
    val activities: StateFlow<List<Order>> = _activities.asStateFlow()

    private val _refundNotice = MutableStateFlow<String?>(null)
    val refundNotice: StateFlow<String?> = _refundNotice.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init { listenToUserShopActivities() }

    private fun listenToUserShopActivities() {
        val uid = currentUserId
        if (uid.isNullOrBlank()) {
            _activities.value = emptyList()
            _isLoading.value = false
            return
        }
        screenModelScope.launch {
            ordersRepository.observeClientShopOrders(uid).collect {
                _activities.value = it
                _isLoading.value = false
            }
        }
    }

    fun cancelShopOrder(orderId: String) {
        screenModelScope.launch {
            runCatching { serviceActions.transitionOrderStatus(orderId, OrderStatus.CANCELLED.name) }
                .onFailure { _refundNotice.value = it.message ?: "Could not cancel this order." }
        }
    }

    fun requestRefund(orderId: String) {
        screenModelScope.launch {
            runCatching { serviceActions.requestPaymentRefund("ORDER", orderId, UUID.randomUUID().toString()) }
                .onSuccess { _refundNotice.value = "Refund request submitted for administrator review. Your order has not been cancelled." }
                .onFailure { _refundNotice.value = it.message ?: "Could not submit refund request." }
        }
    }
}