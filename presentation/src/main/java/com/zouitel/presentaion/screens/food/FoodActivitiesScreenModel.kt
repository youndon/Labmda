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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FoodActivitiesScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val currentUserId = Firebase.auth.currentUser?.uid

    private val _orders = MutableStateFlow<List<Order>>(emptyList())
    val orders: StateFlow<List<Order>> = _orders.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToUserOrders()
    }

    private fun listenToUserOrders() {
        screenModelScope.launch(Dispatchers.IO) {
            val uid = currentUserId ?: run {
                _orders.value = emptyList()
                _isLoading.value = false
                return@launch
            }
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
            runCatching { OrderStatusRepository().updateStatus(orderId, OrderStatus.CANCELLED.name) }
        }
    }
}