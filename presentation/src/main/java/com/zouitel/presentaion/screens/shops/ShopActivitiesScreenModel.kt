package com.zouitel.presentaion.screens.shops

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
import com.zouitel.presentaion.model.OrderType
import com.zouitel.presentaion.screens.food.toOrderOrNull
import com.zouitel.presentaion.screens.orders.OrderStatusRepository
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ShopActivitiesScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val currentUserId = Firebase.auth.currentUser?.uid

    private val _activities = MutableStateFlow<List<Order>>(emptyList())
    val activities: StateFlow<List<Order>> = _activities.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToUserShopActivities()
    }

    private fun listenToUserShopActivities() {
        val uid = currentUserId
        if (uid.isNullOrBlank()) {
            _isLoading.value = false
            return
        }
        screenModelScope.launch {
            db.child("orders").orderByChild("clientId").equalTo(uid).valueEvents.collect { snapshot ->
                _activities.value = snapshot.children
                    .mapNotNull { it.toOrderOrNull() }
                    .filter { it.orderType == OrderType.RETAIL }
                    .sortedByDescending { it.createdAtTimestamp }
                _isLoading.value = false
            }
        }
    }

    fun cancelShopOrder(orderId: String) {
        screenModelScope.launch {
            OrderStatusRepository().updateStatus(orderId, OrderStatus.CANCELLED.name)
        }
    }
}