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
import kotlinx.coroutines.CancellationException

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
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _cashActionError = MutableStateFlow<String?>(null)
    val cashActionError: StateFlow<String?> = _cashActionError.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _actionInProgressTripIds = MutableStateFlow<Set<String>>(emptySet())
    val actionInProgressTripIds: StateFlow<Set<String>> = _actionInProgressTripIds.asStateFlow()

    // Mirrors users/{uid}/driverService/isOnline so the switch always shows the stored value.
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
                // If the app is killed or loses connection, Firebase flips the driver offline.
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
        val driverVehicleType = db.child("users").child(uid).child("driverService")
            .child("vehicleInfo").child("type").valueEvents

        screenModelScope.launch {
            combine(openRequests, assignedToDriver, driverVehicleType) { openSnapshot, assignedSnapshot, vehicleSnapshot ->
                Triple(
                    openSnapshot.children + assignedSnapshot.children,
                    runCatching { vehicleSnapshot.value<String>() }.getOrDefault("").uppercase(),
                    uid
                )
            }.collect { (snapshots, currentVehicleType, driverUid) ->
                val available = mutableListOf<Trip>()
                val active = mutableListOf<Trip>()

                snapshots.distinctBy { it.key }.forEach { child ->
                    try {
                        val id = child.key ?: return@forEach

                        fun str(key: String) = runCatching { child.child(key).value<String>() }.getOrDefault("")
                        fun dbl(key: String) = runCatching { child.child(key).value<Double>() }.getOrDefault(0.0)

                        val assignedDriverId = str("driverId")
                        val status = child.child("status").value<String>()

                        val trip = Trip(
                            id = id,
                            passengerId = str("passengerId"),
                            passengerName = str("passengerName"),
                            passengerPhone = str("passengerPhone"),
                            pickupLocation = str("pickupLocation"),
                            dropoffLocation = str("dropoffLocation"),
                            pickupLat = dbl("pickupLat"),
                            pickupLng = dbl("pickupLng"),
                            dropoffLat = dbl("dropoffLat"),
                            dropoffLng = dbl("dropoffLng"),
                            fare = dbl("fare"),
                            serviceType = str("serviceType"),
                            routeDuration = str("routeDuration"),
                            routePolyline = str("routePolyline"),
                            driverId = assignedDriverId,
                            driverName = str("driverName"),
                            driverPhone = str("driverPhone"),
                            vehicleDetails = str("vehicleDetails"),
                            status = status,
                            paymentMethod = str("paymentMethod"),
                            paymentStatus = str("paymentStatus").ifBlank { "UNPAID" },
                            paymentId = str("paymentId"),
                            createdAtTimestamp = runCatching { child.child("createdAtTimestamp").value<Long>() }.getOrDefault(0L),
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

                        if (
                            status == "REQUESTED" &&
                            assignedDriverId.isBlank() &&
                            (trip.serviceType.isBlank() || trip.serviceType.equals("RIDE", true) ||
                                trip.serviceType.equals(currentVehicleType, true))
                        ) {
                            available.add(trip)
                        } else if (
                            assignedDriverId == driverUid &&
                            status != "AWAITING_PAYMENT" &&
                            status != "COMPLETED" &&
                            status != "CANCELLED"
                        ) {
                            active.add(trip)
                        }
                    } catch (_: Exception) {
                        // Ignore malformed or partially written trip nodes.
                    }
                }

                _availableTrips.value = available.sortedByDescending { it.createdAtTimestamp }
                _activeTrips.value = active.sortedByDescending { it.createdAtTimestamp }
                _isLoading.value = false
            }
        }
    }

    fun acceptTrip(trip: Trip) {
        performTripAction(
            tripId = trip.id,
            successMessage = "Ride accepted. Contact the passenger and head to pickup.",
            fallbackMessage = "This ride could not be accepted."
        ) {
            serviceActions.claimTrip(trip.id)
        }
    }

    fun updateTripStatus(tripId: String, newStatus: String) {
        val targetStatus = newStatus.uppercase()
        val successMessage = when (targetStatus) {
            "ARRIVED" -> "Arrival confirmed. You can start the trip."
            "IN_PROGRESS" -> "Trip started."
            "COMPLETED" -> "Trip completed."
            else -> "Ride updated."
        }

        performTripAction(
            tripId = tripId,
            successMessage = successMessage,
            fallbackMessage = "Ride status could not be updated."
        ) {
            if (targetStatus == "COMPLETED") {
                val tripRef = db.child("trips").child(tripId)
                val paymentMethod = runCatching {
                    tripRef.child("paymentMethod").valueEvents.first().value<String>()
                }.getOrDefault("")

                if (paymentMethod.equals("CASH", ignoreCase = true)) {
                    // Server verifies the assigned driver, collected amount and ledger split.
                    payments.confirmCashCollected("TRIP", tripId)
                    return@performTripAction
                }
            }
            // Status transitions remain callable/server-authorized; the client never writes status.
            serviceActions.transitionTripStatus(tripId, targetStatus)
        }
    }

    private fun performTripAction(
        tripId: String,
        successMessage: String,
        fallbackMessage: String,
        action: suspend () -> Unit
    ) {
        screenModelScope.launch {
            if (tripId in _actionInProgressTripIds.value) return@launch

            _cashActionError.value = null
            _actionMessage.value = null
            _actionInProgressTripIds.value = _actionInProgressTripIds.value + tripId

            try {
                action()
                _actionMessage.value = successMessage
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _cashActionError.value = error.message?.takeIf { it.isNotBlank() } ?: fallbackMessage
            } finally {
                _actionInProgressTripIds.value = _actionInProgressTripIds.value - tripId
            }
        }
    }

    fun clearCashActionError() {
        _cashActionError.value = null
    }

    fun clearActionMessage() {
        _actionMessage.value = null
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