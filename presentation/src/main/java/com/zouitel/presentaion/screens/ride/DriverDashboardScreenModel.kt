package com.zouitel.presentaion.screens.ride

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Trip
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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

    // The private source of truth remains users/{uid}/driverService/isOnline.
    // Passenger screens read the intentionally shareable mirror at /driverPresence/{uid}.
    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        listenToOnlineState()
        listenToDriverDashboardTrips()
    }

    private fun listenToOnlineState() {
        val uid = currentUser?.uid ?: return
        screenModelScope.launch {
            db.child("users").child(uid).child("driverService").child("isOnline").valueEvents
                .catch { _isOnline.value = false }
                .collect { snap ->
                    _isOnline.value = runCatching { snap.value<Boolean>() }.getOrDefault(false)
                }
        }
    }

    fun setOnline(online: Boolean) {
        val uid = currentUser?.uid ?: return
        val onlineRef = db.child("users").child(uid).child("driverService").child("isOnline")
        val presenceRef = db.child("driverPresence").child(uid)
        val presenceOnlineRef = presenceRef.child("isDriverOnline")

        screenModelScope.launch {
            runCatching {
                if (online) {
                    // Keep the public presence mirror in sync if the connection disappears.
                    runCatching { onlineRef.onDisconnect().setValue(false) }
                    runCatching { presenceOnlineRef.onDisconnect().setValue(false) }

                    onlineRef.setValue(true)
                    publishDriverPresence(uid)
                    presenceOnlineRef.setValue(true)
                    _isOnline.value = true
                } else {
                    runCatching { onlineRef.onDisconnect().cancel() }
                    runCatching { presenceOnlineRef.onDisconnect().cancel() }
                    onlineRef.setValue(false)
                    presenceOnlineRef.setValue(false)
                    _isOnline.value = false
                }
            }
        }
    }

    /** Copies only passenger-facing fields into the separate presence node. */
    private suspend fun publishDriverPresence(uid: String) {
        val profile = db.child("users").child(uid).valueEvents.first()
        val presenceRef = db.child("driverPresence").child(uid)

        val fullName = runCatching { profile.child("fullName").value<String>() }
            .getOrDefault(currentUser?.displayName ?: "Driver")
        val phone = runCatching { profile.child("phoneNumber").value<String>() }
            .getOrElse { runCatching { profile.child("phone").value<String>() }.getOrDefault("") }

        presenceRef.child("fullName").setValue(fullName)
        presenceRef.child("phoneNumber").setValue(phone)

        val location = profile.child("currentLocation")
        val latitude = runCatching { location.child("latitude").value<Double>() }.getOrNull()
        val longitude = runCatching { location.child("longitude").value<Double>() }.getOrNull()
        if (latitude != null && longitude != null) {
            val heading = runCatching { location.child("heading").value<Float>() }.getOrDefault(0f)
            presenceRef.child("currentLocation").setValue(
                mapOf("latitude" to latitude, "longitude" to longitude, "heading" to heading)
            )
        }
    }

    /**
     * Do not listen to all of /trips. Each listener below must have a matching query-based
     * Realtime Database read rule. The REQUESTED query should be restricted to approved drivers.
     */
    private fun listenToDriverDashboardTrips() {
        val uid = currentUser?.uid ?: run {
            _isLoading.value = false
            return
        }

        // Available requests: rules must authorize only trusted/approved drivers for this query.
        screenModelScope.launch {
            db.child("trips").orderByChild("status").equalTo("REQUESTED").valueEvents
                .catch {
                    _availableTrips.value = emptyList()
                    _isLoading.value = false
                }
                .collect { snapshot ->
                    val available = snapshot.children.mapNotNull { child ->
                        try {
                            val id = child.key ?: return@mapNotNull null
                            fun str(key: String) = runCatching { child.child(key).value<String>() }.getOrDefault("")
                            fun dbl(key: String) = runCatching { child.child(key).value<Double>() }.getOrDefault(0.0)

                            val driverId = str("driverId")
                            val status = runCatching { child.child("status").value<String>() }.getOrDefault("")
                            if (status != "REQUESTED" || driverId.isNotBlank()) return@mapNotNull null

                            Trip(
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
                        } catch (_: Exception) {
                            null
                        }
                    }.sortedByDescending { it.createdAtTimestamp }

                    _availableTrips.value = available
                    _isLoading.value = false
                }
        }

        // Assigned trips: this query is constrained to the signed-in driver's UID.
        screenModelScope.launch {
            db.child("trips").orderByChild("driverId").equalTo(uid).valueEvents
                .catch {
                    _activeTrips.value = emptyList()
                    _isLoading.value = false
                }
                .collect { snapshot ->
                    val active = snapshot.children.mapNotNull { child ->
                        try {
                            val id = child.key ?: return@mapNotNull null
                            fun str(key: String) = runCatching { child.child(key).value<String>() }.getOrDefault("")
                            fun dbl(key: String) = runCatching { child.child(key).value<Double>() }.getOrDefault(0.0)

                            val driverId = str("driverId")
                            val status = runCatching { child.child("status").value<String>() }.getOrDefault("")
                            if (driverId != uid || status == "COMPLETED" || status == "CANCELLED") {
                                return@mapNotNull null
                            }

                            Trip(
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
                        } catch (_: Exception) {
                            null
                        }
                    }.sortedByDescending { it.createdAtTimestamp }

                    _activeTrips.value = active
                    _isLoading.value = false
                }
        }
    }

    /**
     * IMPORTANT: This still writes directly from the client and is not race-safe. For production,
     * replace it with a trusted callable Cloud Function that atomically claims the trip.
     * The current rules from the earlier example do not authorize this driver's first claim.
     */
    fun acceptTrip(trip: Trip) {
        val driverUid = currentUser?.uid ?: return

        screenModelScope.launch {
            runCatching {
                val tripRef = db.child("trips").child(trip.id)
                val status = tripRef.child("status").valueEvents.first().value<String>()
                val takenBy = runCatching { tripRef.child("driverId").valueEvents.first().value<String>() }
                    .getOrDefault("")
                if (status != "REQUESTED" || takenBy.isNotBlank()) return@launch

                val profile = db.child("users").child(driverUid).valueEvents.first()
                val fullName = runCatching { profile.child("fullName").value<String>() }
                    .getOrElse { currentUser?.displayName ?: "Verified Driver" }
                val phone = runCatching { profile.child("phoneNumber").value<String>() }
                    .getOrElse {
                        runCatching { profile.child("phone").value<String>() }
                            .getOrDefault(currentUser?.phoneNumber ?: "")
                    }
                val vehicle = profile.child("driverService").child("vehicleInfo")
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
    }

    /** Use a trusted server function for validated status transitions in production. */
    fun updateTripStatus(tripId: String, newStatus: String) {
        screenModelScope.launch {
            runCatching {
                db.child("trips").child(tripId).child("status").setValue(newStatus)
            }
        }
    }

    fun updateDriverLocation(latitude: Double, longitude: Double, heading: Float = 0f) {
        val uid = currentUser?.uid ?: return
        val location = mapOf("latitude" to latitude, "longitude" to longitude, "heading" to heading)

        screenModelScope.launch {
            runCatching {
                db.child("users").child(uid).child("currentLocation").setValue(location)
                if (_isOnline.value) {
                    db.child("driverPresence").child(uid).child("currentLocation").setValue(location)
                }
            }
        }
    }

    fun setDriverOffline() = setOnline(false)

    @OptIn(DelicateCoroutinesApi::class)
    override fun onDispose() {
        val uid = currentUser?.uid ?: return
        GlobalScope.launch {
            runCatching {
                db.child("users").child(uid).child("driverService").child("isOnline").setValue(false)
            }
            runCatching {
                db.child("driverPresence").child(uid).child("isDriverOnline").setValue(false)
            }
        }
    }
}
