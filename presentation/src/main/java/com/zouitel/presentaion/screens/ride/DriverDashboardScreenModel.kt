package com.zouitel.presentaion.screens.ride

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Trip
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DriverDashboardScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val currentUser = Firebase.auth.currentUser

    private val _availableTrips = MutableStateFlow<List<Trip>>(emptyList())
    val availableTrips: StateFlow<List<Trip>> = _availableTrips.asStateFlow()

    private val _activeTrips = MutableStateFlow<List<Trip>>(emptyList())
    val activeTrips: StateFlow<List<Trip>> = _activeTrips.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToDriverDashboardTrips()
    }

    private fun listenToDriverDashboardTrips() {
        val uid = currentUser?.uid ?: run {
            _isLoading.value = false
            return
        }

        screenModelScope.launch {
            db.child("trips").valueEvents.collect { snapshot ->
                val available = mutableListOf<Trip>()
                val active = mutableListOf<Trip>()

                snapshot.children.forEach { child ->
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
                            createdAtTimestamp = child.child("createdAtTimestamp").value<Long>()
                        )

                        if (status == "REQUESTED" && driverId.isBlank()) {
                            available.add(trip)
                        } else if (driverId == uid && status != "COMPLETED" && status != "CANCELLED") {
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
            val tripRef = db.child("trips").child(trip.id)

            // Don't take a trip that another driver already accepted or that was cancelled
            val status = runCatching { tripRef.child("status").valueEvents.first().value<String>() }.getOrDefault("")
            val takenBy = runCatching { tripRef.child("driverId").valueEvents.first().value<String>() }.getOrDefault("")
            if (status != "REQUESTED" || takenBy.isNotBlank()) return@launch

            // Read only THIS driver's profile (once), not the whole users node
            val profile = db.child("users").child(driverUid).valueEvents.first()

            val fullName = runCatching { profile.child("fullName").value<String>() }
                .getOrElse { currentUser.displayName ?: "Verified Driver" }
            val phone = runCatching { profile.child("phoneNumber").value<String>() }
                .getOrElse {
                    runCatching { profile.child("phone").value<String>() }
                        .getOrDefault(currentUser.phoneNumber ?: "")
                }

            val vehicle = profile.child("vehicleInfo")
            val make = runCatching { vehicle.child("make").value<String>() }.getOrDefault("Toyota")
            val model = runCatching { vehicle.child("model").value<String>() }.getOrDefault("Corolla")
            val color = runCatching { vehicle.child("color").value<String>() }.getOrDefault("Silver")
            val plate = runCatching { vehicle.child("licensePlate").value<String>() }.getOrDefault("N/A")

            tripRef.updateChildren(
                mapOf(
                    "driverId" to driverUid,
                    "driverName" to fullName,
                    "driverPhone" to phone,
                    "vehicleDetails" to "$color $make $model ($plate)",
                    "status" to "ACCEPTED"
                )
            )
        }
    }

    fun updateTripStatus(tripId: String, newStatus: String) {
        screenModelScope.launch {
            db.child("trips").child(tripId).child("status").setValue(newStatus)
        }
    }

    fun updateDriverLocation(latitude: Double, longitude: Double, heading: Float = 0f) {
        val uid = currentUser?.uid ?: return
        screenModelScope.launch {
            db.child("users").child(uid).updateChildren(
                mapOf(
                    "currentLocation" to mapOf(
                        "latitude" to latitude,
                        "longitude" to longitude,
                        "heading" to heading
                    ),
                    "isDriverOnline" to true
                )
            )
        }
    }

    fun setDriverOffline() {
        val uid = currentUser?.uid ?: return
        screenModelScope.launch {
            db.child("users").child(uid).child("isDriverOnline").setValue(false)
        }
    }
}