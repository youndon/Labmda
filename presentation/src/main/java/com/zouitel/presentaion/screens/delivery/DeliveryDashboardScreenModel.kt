package com.zouitel.presentaion.screens.delivery

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
import com.zouitel.presentaion.screens.food.toOrderOrNull
import com.zouitel.presentaion.screens.orders.OrderStatusRepository
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** Lightweight lat/lng so the model stays free of map types. */
data class OrderPoint(val lat: Double, val lng: Double)

class DeliveryDashboardScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val currentUid: String? get() = Firebase.auth.currentUser?.uid

    private val _activeDeliveries = MutableStateFlow<List<Order>>(emptyList())
    val activeDeliveries: StateFlow<List<Order>> = _activeDeliveries.asStateFlow()

    private val _completedDeliveries = MutableStateFlow<List<Order>>(emptyList())
    val completedDeliveries: StateFlow<List<Order>> = _completedDeliveries.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
//
//    private val _orderPoints = MutableStateFlow<Map<String, OrderPoint>>(emptyMap())
//    val orderPoints: StateFlow<Map<String, OrderPoint>> = _orderPoints.asStateFlow()

    init {
        listenToOrdersTable()
    }

    private fun listenToOrdersTable() {
        val uid = currentUid
        if (uid.isNullOrBlank()) {
            _isLoading.value = false
            return
        }
        screenModelScope.launch(Dispatchers.IO) {
            val queue = db.child("deliveryQueue").valueEvents
            val assigned = db.child("orders").orderByChild("deliveryId").equalTo(uid).valueEvents
            combine(queue, assigned) { queuedSnapshot, assignedSnapshot ->
                (listOf(queuedSnapshot, assignedSnapshot).flatMap { it.children.toList() })
                    .distinctBy { it.key }
                    .mapNotNull { it.toOrderOrNull() }
                    .filter { it.status == OrderStatus.OUT_FOR_DELIVERY || it.status == OrderStatus.DELIVERED }
            }.catch {
                _activeDeliveries.value = emptyList()
                _completedDeliveries.value = emptyList()
                _isLoading.value = false
            }.collect { allTasks ->
                _activeDeliveries.value = allTasks
                    .filter { it.status == OrderStatus.OUT_FOR_DELIVERY }
                    .filter { it.deliveryId.isBlank() || it.deliveryId == uid }
                    .sortedByDescending { it.createdAtTimestamp }

                _completedDeliveries.value = allTasks
                    .filter { it.status == OrderStatus.DELIVERED && it.deliveryId == uid }
                    .sortedByDescending { it.createdAtTimestamp }

                _isLoading.value = false
            }
        }
    }

    fun claimCourierTask(order: Order) {
        screenModelScope.launch(Dispatchers.IO) {
            runCatching { OrderStatusRepository().claimCourierOrder(order.id) }
        }
    }

    fun rejectCourierTask(order: Order) {
        screenModelScope.launch(Dispatchers.IO) {
            runCatching { OrderStatusRepository().releaseCourierOrder(order.id) }
        }
    }

    fun markCourierOrderDelivered(order: Order) {
        screenModelScope.launch(Dispatchers.IO) {
            runCatching { OrderStatusRepository().updateStatus(order.id, OrderStatus.DELIVERED.name) }
        }
    }

}