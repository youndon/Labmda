package com.zouitel.presentaion.common

import com.zouitel.presentaion.payments.ServiceActionsRepository
import com.zouitel.presentaion.payments.PaymentRepository
import com.zouitel.presentaion.screens.food.toOrderOrNull
import com.zouitel.presentaion.payments.TrackingRouteResult

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderType
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

data class CourierLocation(val latitude: Double, val longitude: Double, val heading: Float, val updatedAtTimestamp: Long)
data class OrderTrackingRoute(val encodedPolyline: String, val distanceMeters: Long, val duration: String)

class LiveOrderTrackingScreenModel(val orderId: String) : ScreenModel {
    private val db = Firebase.database.reference()
    private val serviceActions = ServiceActionsRepository()
    private val paymentRepository = PaymentRepository()
    private var courierLocationJob: Job? = null
    private var routeJob: Job? = null
    private var observedCourierId: String = ""

    private val _courierLocation = MutableStateFlow<CourierLocation?>(null)
    val courierLocation: StateFlow<CourierLocation?> = _courierLocation.asStateFlow()

    private val _trackingRoute = MutableStateFlow<OrderTrackingRoute?>(null)
    val trackingRoute: StateFlow<OrderTrackingRoute?> = _trackingRoute.asStateFlow()
    private val _routeLoading = MutableStateFlow(false)
    val routeLoading: StateFlow<Boolean> = _routeLoading.asStateFlow()

    private val _order = MutableStateFlow<Order?>(null)
    val order: StateFlow<Order?> = _order.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToOrderUpdates()
    }

    private fun listenToOrderUpdates() {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("orders").child(orderId).valueEvents.collect { snapshot ->
                if (!snapshot.exists) {
                    _order.value = null
                    _isLoading.value = false
                    return@collect
                }

                try {
                    val parsedOrder = snapshot.toOrderOrNull(defaultOrderType = OrderType.RETAIL)
                    _order.value = parsedOrder
                    val courierId = parsedOrder?.deliveryId.orEmpty()
                    if (courierId != observedCourierId) {
                        observedCourierId = courierId
                        courierLocationJob?.cancel()
                        routeJob?.cancel()
                        _courierLocation.value = null
                        _trackingRoute.value = null
                        if (courierId.isNotBlank()) observeCourier(courierId)
                    }
                } catch (e: Exception) {
                    _order.value = null
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    private fun observeCourier(courierId: String) {
        courierLocationJob = screenModelScope.launch(Dispatchers.IO) {
            db.child("publicDrivers").child(courierId).valueEvents.collect { snapshot ->
                if (!snapshot.exists) {
                    _courierLocation.value = null
                    _trackingRoute.value = null
                    return@collect
                }
                val location = snapshot.child("currentLocation")
                val lat = runCatching { location.child("latitude").value<Double>() }.getOrDefault(0.0)
                val lng = runCatching { location.child("longitude").value<Double>() }.getOrDefault(0.0)
                val heading = runCatching { location.child("heading").value<Float>() }.getOrDefault(0f)
                val updated = runCatching { snapshot.child("updatedAtTimestamp").value<Long>() }.getOrDefault(0L)
                if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || (lat == 0.0 && lng == 0.0)) return@collect
                _courierLocation.value = CourierLocation(lat, lng, heading, updated)
            }
        }

        routeJob = screenModelScope.launch(Dispatchers.IO) {
            combine(_courierLocation, _order) { location, order -> location to order }
                .collectLatest { (location, destination) ->
                if (location == null || destination == null) return@collectLatest
                if (destination.status != OrderStatus.OUT_FOR_DELIVERY) return@collectLatest
                if (destination.dropoffLat == 0.0 && destination.dropoffLng == 0.0) return@collectLatest
                delay(5_000)
                _routeLoading.value = true
                try {
                    val route: TrackingRouteResult = paymentRepository.getTrackingRoute(orderId)
                    _trackingRoute.value = OrderTrackingRoute(route.encodedPolyline, route.distanceMeters, route.duration)
                } catch (_: Exception) {
                    // Retain the last valid road route if a refresh fails.
                } finally {
                    _routeLoading.value = false
                }
            }
        }
    }

    fun cancelOrder() {
        screenModelScope.launch(Dispatchers.IO) {
            serviceActions.transitionOrderStatus(orderId, OrderStatus.CANCELLED.name)
        }
    }
}