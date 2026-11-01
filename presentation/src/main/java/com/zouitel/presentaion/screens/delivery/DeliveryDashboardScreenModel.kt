package com.zouitel.presentaion.screens.delivery

import com.zouitel.presentaion.payments.ServiceActionsRepository
import com.zouitel.presentaion.screens.food.toOrderOrNull

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
import com.zouitel.presentaion.model.OrderType
import com.zouitel.presentaion.payments.PaymentRepository
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Lightweight lat/lng so the model stays free of map types. */
data class OrderPoint(val lat: Double, val lng: Double)

class DeliveryDashboardScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val payments = PaymentRepository()
    private val serviceActions = ServiceActionsRepository()
    private val currentUid: String? get() = Firebase.auth.currentUser?.uid

    private val _activeDeliveries = MutableStateFlow<List<Order>>(emptyList())
    val activeDeliveries: StateFlow<List<Order>> = _activeDeliveries.asStateFlow()

    private val _completedDeliveries = MutableStateFlow<List<Order>>(emptyList())
    val completedDeliveries: StateFlow<List<Order>> = _completedDeliveries.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    private val _cashActionError = MutableStateFlow<String?>(null)
    val cashActionError: StateFlow<String?> = _cashActionError.asStateFlow()

    private val _trackingRoute = MutableStateFlow<com.zouitel.presentaion.payments.TrackingRouteResult?>(null)
    val trackingRoute: StateFlow<com.zouitel.presentaion.payments.TrackingRouteResult?> = _trackingRoute.asStateFlow()
    private val _routeLoading = MutableStateFlow(false)
    val routeLoading: StateFlow<Boolean> = _routeLoading.asStateFlow()
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
            _activeDeliveries.value = emptyList()
            _completedDeliveries.value = emptyList()
            _isLoading.value = false
            return
        }

        val orders = db.child("orders")
        val openTasks = db.child("availableDeliveryTasks").valueEvents
        val assignedTasks = orders.orderByChild("deliveryId").equalTo(uid).valueEvents

        screenModelScope.launch(Dispatchers.IO) {
            combine(openTasks, assignedTasks) { openSnapshot, assignedSnapshot ->
                openSnapshot.children + assignedSnapshot.children
            }.collect { snapshots ->
                val orderSnapshots = snapshots.distinctBy { it.key }
                val allTasks = orderSnapshots.mapNotNull { child ->
                    val deliveryType = runCatching { child.child("deliveryType").value<String>() }.getOrDefault("")
                    if (deliveryType == "FREIGHT") return@mapNotNull null

                    val order = child.toOrderOrNull(defaultOrderType = OrderType.RETAIL)
                        ?: return@mapNotNull null
                    order.takeIf {
                        it.status == OrderStatus.OUT_FOR_DELIVERY || it.status == OrderStatus.DELIVERED
                    }
                }

                val points = mutableMapOf<String, OrderPoint>()
                orderSnapshots.forEach { child ->
                    val id = child.key ?: return@forEach
                    val lat = runCatching { child.child("clientLat").value<Double>() }.getOrNull()
                    val lng = runCatching { child.child("clientLng").value<Double>() }.getOrNull()
                    if (lat != null && lng != null && (lat != 0.0 || lng != 0.0)) {
                        points[id] = OrderPoint(lat, lng)
                    }
                }
//                _orderPoints.value = points

                val uid = currentUid

                _activeDeliveries.value = allTasks
                    .filter { it.status == OrderStatus.OUT_FOR_DELIVERY }
                    .filter { it.deliveryId.isBlank() || it.deliveryId == uid }
                    .sortedByDescending { it.createdAtTimestamp }

                _completedDeliveries.value = allTasks
                    .filter { it.status == OrderStatus.DELIVERED && (uid == null || it.deliveryId == uid) }
                    .sortedByDescending { it.createdAtTimestamp }

                _isLoading.value = false
            }
        }
    }

    fun updateCourierLocation(latitude: Double, longitude: Double, heading: Float = 0f) {
        val uid = currentUid ?: return
        screenModelScope.launch(Dispatchers.IO) {
            runCatching {
                db.child("driverLocations").child(uid).setValue(
                    mapOf("latitude" to latitude, "longitude" to longitude, "heading" to heading)
                )
            }
        }
    }

    fun refreshCourierRoute(orderId: String) {
        screenModelScope.launch(Dispatchers.IO) {
            _routeLoading.value = true
            runCatching { payments.getTrackingRoute(orderId, "COURIER") }
                .onSuccess { _trackingRoute.value = it }
                .onFailure { _cashActionError.value = it.message ?: "The delivery route is temporarily unavailable." }
            _routeLoading.value = false
        }
    }

    fun markDeliveryPickedUp(order: Order) {
        screenModelScope.launch(Dispatchers.IO) {
            runCatching { payments.markDeliveryPickedUp(order.id) }
                .onFailure { _cashActionError.value = it.message ?: "Pickup could not be confirmed." }
        }
    }

    fun clearTrackingRoute() { _trackingRoute.value = null }

    fun claimCourierTask(order: Order) {
        val uid = currentUid ?: return

        screenModelScope.launch(Dispatchers.IO) {
            runCatching { serviceActions.claimDeliveryOrder(order.id) }
                .onFailure { error -> _cashActionError.value = error.message ?: "This delivery could not be claimed." }
        }
    }

    fun rejectCourierTask(order: Order) {
        screenModelScope.launch(Dispatchers.IO) {
            runCatching { serviceActions.releaseDeliveryOrder(order.id) }
                .onFailure { error -> _cashActionError.value = error.message ?: "This delivery could not be released." }
        }
    }

    fun markCourierOrderDelivered(order: Order) {
        screenModelScope.launch(Dispatchers.IO) {
            if (order.paymentMethod.trim().equals("CASH", ignoreCase = true)) {
                // Cash amount and split are read from the server-owned order record.
                // The callable also changes the order status to DELIVERED.
                runCatching { payments.confirmCashCollected("ORDER", order.id) }
                    .onFailure { error ->
                        _cashActionError.value = error.message ?: "Cash collection could not be confirmed."
                    }
            } else {
                runCatching { serviceActions.transitionOrderStatus(order.id, OrderStatus.DELIVERED.name) }
                    .onFailure { error -> _cashActionError.value = error.message ?: "Delivery could not be completed." }
            }
        }
    }
}