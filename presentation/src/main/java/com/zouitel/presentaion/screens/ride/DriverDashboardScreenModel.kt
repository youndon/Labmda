package com.zouitel.presentaion.screens.ride

import com.zouitel.presentaion.payments.ServiceActionsRepository

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Trip
import com.zouitel.presentaion.payments.PaymentRepository
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DriverDashboardScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val currentUser = Firebase.auth.currentUser
    private val payments = PaymentRepository()
    private val serviceActions = ServiceActionsRepository()

    private val _availableTrips = MutableStateFlow<List<Trip>>(emptyList())
    val availableTrips: StateFlow<List<Trip>> = _availableTrips.asStateFlow()

    private val _activeTrips = MutableStateFlow<List<Trip>>(emptyList())
    val activeTrips: StateFlow<List<Trip>> = _activeTrips.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    private val _cashActionError = MutableStateFlow<String?>(null)
    val cashActionError: StateFlow<String?> = _cashActionError.asStateFlow()
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Mirrors users/{uid}/driverService/isOnline so the switch always shows the real stored value
    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        listenToOnlineState()
        listenToDriverDashboardTrips()
    }

    private fun listenToOnlineState() {
        val uid = currentUser?.uid ?: return
        screenModelScope.launch {
            db.child("users").child(uid).child("driverService").child("isOnline").valueEvents.collect { snap ->
                _isOnline.value = runCatching { snap.value<Boolean>() }.getOrDefault(false)
            }
        }
    }

    fun setOnline(online: Boolean) {
        val uid = currentUser?.uid ?: return
        val onlineRef = db.child("users").child(uid).child("driverService").child("isOnline")

        screenModelScope.launch {
            if (online) {
                // If the app is killed or loses connection, Firebase flips the driver offline
                runCatching { onlineRef.onDisconnect().setValue(false) }
                onlineRef.setValue(true)
            } else {
                runCatching { onlineRef.onDisconnect().cancel() }
                onlineRef.setValue(false)
            }
        }
    }

    private fun listenToDriverDashboardTrips() {
        val uid = currentUser?.uid ?: run {
            _isLoading.value = false
            return
        }

        val trips = db.child("trips")
        val openRequests = db.child("availableRideRequests").valueEvents
        val assignedToDriver = trips.orderByChild("driverId").equalTo(uid).valueEvents

        screenModelScope.launch {
            combine(openRequests, assignedToDriver) { openSnapshot, assignedSnapshot ->
                openSnapshot.children + assignedSnapshot.children
            }.collect { snapshots ->
                val available = mutableListOf<Trip>()
                val active = mutableListOf<Trip>()

                snapshots.distinctBy { it.key }.forEach { child ->
                    try {
                        val id = child.key ?: return@forEach

                        fun str(key: String) = runCatching { child.child(key).value<String>() }.getOrDefault("")
                        fun dbl(key: String) = runCatching { child.child(key).value<Double>() }.getOrDefault(0.0)

                        val driverId = str("driverId")
                        val status = child.child("status").value<String>()

                        val trip = Trip(
                            id = id,
                            passengerId = child.child("passengerId").value<String>(),
                            passengerName = child.child("passengerName").value<String>(),
                            passengerPhone = str("passengerPhone"),
                            pickupLocation = child.child("pickupLocation").value<String>(),
                            dropoffLocation = child.child("dropoffLocation").value<String>(),
                            pickupLat = dbl("pickupLat"),
                            pickupLng = dbl("pickupLng"),
                            dropoffLat = dbl("dropoffLat"),
                            dropoffLng = dbl("dropoffLng"),
                            fare = child.child("fare").value<Double>(),
                            driverId = driverId,
                            driverName = str("driverName"),
                            driverPhone = str("driverPhone"),
                            vehicleDetails = str("vehicleDetails"),
                            status = status,
                            paymentMethod = str("paymentMethod"),
                            paymentStatus = str("paymentStatus").ifBlank { "UNPAID" },
                            paymentId = str("paymentId"),
                            createdAtTimestamp = child.child("createdAtTimestamp").value<Long>(),
                            cashCollectionStatus = str("cashCollectionStatus"),
                            cashCollectedBy = str("cashCollectedBy"),
                            cashCollectedByRole = str("cashCollectedByRole"),
                            cashCollectedAmountCentavos = runCatching { child.child("cashCollectedAmountCentavos").value<Long>() }.getOrDefault(0L),
                            cashPlatformOwedCentavos = runCatching { child.child("cashPlatformOwedCentavos").value<Long>() }.getOrDefault(0L),
                            cashCollectorEarningsCentavos = runCatching { child.child("cashCollectorEarningsCentavos").value<Long>() }.getOrDefault(0L),
                            cashRemittedCentavos = runCatching { child.child("cashRemittedCentavos").value<Long>() }.getOrDefault(0L),
                            cashOutstandingRemittanceCentavos = runCatching { child.child("cashOutstandingRemittanceCentavos").value<Long>() }.getOrDefault(0L),
                            cashRemittanceStatus = str("cashRemittanceStatus")
                        )

                        if (status == "REQUESTED" && driverId.isBlank()) {
                            available.add(trip)
                        } else if (
                            driverId == uid &&
                            status != "AWAITING_PAYMENT" &&
                            status != "COMPLETED" &&
                            status != "CANCELLED"
                        ) {
                            active.add(trip)
                        }
                    } catch (e: Exception) {
                        // ignore malformed nodes
                    }
                }

                _availableTrips.value = available.sortedByDescending { it.createdAtTimestamp }
                _activeTrips.value = active.sortedByDescending { it.createdAtTimestamp }
                _isLoading.value = false
            }
        }
    }

    fun acceptTrip(trip: Trip) {
        val driverUid = currentUser?.uid ?: return

        screenModelScope.launch {
            runCatching { serviceActions.claimTrip(trip.id) }
                .onFailure { error -> _cashActionError.value = error.message ?: "This ride could not be accepted." }
        }
    }

    fun updateTripStatus(tripId: String, newStatus: String) {
        screenModelScope.launch {
            if (newStatus.equals("COMPLETED", ignoreCase = true)) {
                val tripRef = db.child("trips").child(tripId)
                val paymentMethod = runCatching {
                    tripRef.child("paymentMethod").valueEvents.first().value<String>()
                }.getOrDefault("")
                if (paymentMethod.equals("CASH", ignoreCase = true)) {
                    // Driver identity, fare and split are verified/recomputed by Functions.
                    runCatching { payments.confirmCashCollected("TRIP", tripId) }
                        .onFailure { error ->
                            _cashActionError.value = error.message ?: "Cash collection could not be confirmed."
                        }
                    return@launch
                }
            }
            runCatching { serviceActions.transitionTripStatus(tripId, newStatus) }
                .onFailure { error -> _cashActionError.value = error.message ?: "Ride status could not be updated." }
        }
    }

    fun updateDriverLocation(latitude: Double, longitude: Double, heading: Float = 0f) {
        val uid = currentUser?.uid ?: return
        screenModelScope.launch {
            db.child("users").child(uid).child("currentLocation").setValue(
                mapOf(
                    "latitude" to latitude,
                    "longitude" to longitude,
                    "heading" to heading
                )
            )
        }
    }

    fun setDriverOffline() = setOnline(false)

    @OptIn(DelicateCoroutinesApi::class)
    override fun onDispose() {
        val uid = currentUser?.uid ?: return
        GlobalScope.launch {
            runCatching { db.child("users").child(uid).child("driverService").child("isOnline").setValue(false) }
        }
    }
}