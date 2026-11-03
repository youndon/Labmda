package com.zouitel.presentaion.screens.ride

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Trip
import com.zouitel.presentaion.model.UserProfile
import com.zouitel.presentaion.model.VehicleType
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** Name and phone used to prefill the request form. */
data class PassengerInfo(val name: String = "", val phone: String = "")
data class RideFareQuote(val quoteId: String, val customerTotal: Double)

class PassengerScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val currentUser = Firebase.auth.currentUser

    private val _trips = MutableStateFlow<List<Trip>>(emptyList())
    val trips: StateFlow<List<Trip>> = _trips.asStateFlow()

    private val _onlineDrivers = MutableStateFlow<List<UserProfile>>(emptyList())
    private var driverPresenceJob: Job? = null
    val onlineDrivers: StateFlow<List<UserProfile>> = _onlineDrivers.asStateFlow()

    private val _passengerInfo = MutableStateFlow(PassengerInfo())
    val passengerInfo: StateFlow<PassengerInfo> = _passengerInfo.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    private val _requestError = MutableStateFlow<String?>(null)
    private val _rideFareQuote = MutableStateFlow<RideFareQuote?>(null)
    val rideFareQuote: StateFlow<RideFareQuote?> = _rideFareQuote.asStateFlow()
    private var rideQuoteJob: Job? = null
    val requestError: StateFlow<String?> = _requestError.asStateFlow()
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToPassengerTrips()
        listenToPassengerInfo()
    }

    private fun listenToPassengerInfo() {
        val uid = currentUser?.uid ?: return
        screenModelScope.launch {
            db.child("users").child(uid).valueEvents.collect { snap ->
                val name = runCatching { snap.child("fullName").value<String>() }
                    .getOrElse { runCatching { snap.child("name").value<String>() }.getOrDefault("") }
                val phone = runCatching { snap.child("phone").value<String>() }
                    .getOrElse { runCatching { snap.child("phoneNumber").value<String>() }.getOrDefault("") }
                _passengerInfo.value = PassengerInfo(name, phone)
            }
        }
    }

    private fun listenToPassengerTrips() {
        val uid = currentUser?.uid ?: run {
            _isLoading.value = false
            return
        }

        screenModelScope.launch {
            db.child("trips").orderByChild("passengerId").equalTo(uid).valueEvents.collect { snapshot ->
                val list = snapshot.children.mapNotNull { child ->
                    try {
                        val id = child.key ?: return@mapNotNull null
                        val passengerId = child.child("passengerId").value<String>()

                        fun str(key: String) = runCatching { child.child(key).value<String>() }.getOrDefault("")
                        fun dbl(key: String) = runCatching { child.child(key).value<Double>() }.getOrDefault(0.0)

                        Trip(
                            id = id,
                            passengerId = passengerId,
                            passengerName = child.child("passengerName").value<String>(),
                            passengerPhone = str("passengerPhone"),
                            pickupLocation = child.child("pickupLocation").value<String>(),
                            dropoffLocation = child.child("dropoffLocation").value<String>(),
                            pickupLat = dbl("pickupLat"),
                            pickupLng = dbl("pickupLng"),
                            dropoffLat = dbl("dropoffLat"),
                            dropoffLng = dbl("dropoffLng"),
                            fare = child.child("fare").value<Double>(),
                            paymentMethod = runCatching { child.child("paymentMethod").value<String>() }.getOrDefault("CASH"),
                            paymentStatus = runCatching { child.child("paymentStatus").value<String>() }.getOrDefault(""),
                            checkoutUrl = runCatching { child.child("checkoutUrl").value<String>() }.getOrDefault(""),
                            driverId = str("driverId"),
                            driverName = str("driverName"),
                            driverPhone = str("driverPhone"),
                            vehicleDetails = str("vehicleDetails"),
                            status = child.child("status").value<String>(),
                            createdAtTimestamp = child.child("createdAtTimestamp").value<Long>()
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.sortedByDescending { it.createdAtTimestamp }

                _trips.value = list
                listenToOnlineDrivers(list)
                _isLoading.value = false
            }
        }
    }

    private fun listenToOnlineDrivers(trips: List<Trip>) {
        val hasActiveRideRequest = trips.any { it.status in setOf("REQUESTED", "ACCEPTED", "IN_PROGRESS") }
        driverPresenceJob?.cancel()
        if (!hasActiveRideRequest) {
            _onlineDrivers.value = emptyList()
            return
        }

        driverPresenceJob = screenModelScope.launch {
            db.child("driverPresence").valueEvents
                .catch { _onlineDrivers.value = emptyList() }
                .collect { snapshot ->
                    val drivers = snapshot.children.mapNotNull { child ->
                        try {
                            val uid = child.key ?: return@mapNotNull null
                            val fullName = runCatching { child.child("fullName").value<String>() }.getOrDefault("Driver")
                            val lat = runCatching { child.child("latitude").value<Double>() }.getOrDefault(0.0)
                            val lng = runCatching { child.child("longitude").value<Double>() }.getOrDefault(0.0)
                            val heading = runCatching { child.child("heading").value<Float>() }.getOrDefault(0f)

                            UserProfile(
                                uid = uid,
                                fullName = fullName,
                                driverService = DriverAccountInfo(
                                    currentLocation = DriverLocation(latitude = lat, longitude = lng, heading = heading),
                                    isOnline = true,
                                )
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }
                    _onlineDrivers.value = drivers
                }
        }
    }

    fun clearRideQuote() {
        rideQuoteJob?.cancel()
        _rideFareQuote.value = null
    }

    fun previewRideQuote(
        pickupAddress: String,
        pickupLat: Double,
        pickupLng: Double,
        dropoffAddress: String,
        dropoffLat: Double,
        dropoffLng: Double,
        paymentMethod: String,
        rideType: String
    ) {
        rideQuoteJob?.cancel()
        _rideFareQuote.value = null
        val uid = currentUser?.uid ?: return
        rideQuoteJob = screenModelScope.launch {
            delay(900)
            runCatching {
                val method = if (paymentMethod.contains("GCASH", ignoreCase = true)) "GCASH" else "CASH"
                val quote = com.zouitel.presentaion.screens.trustedTransactionCallData(
                    "quoteRide",
                    mapOf(
                        "pickup" to mapOf("latitude" to pickupLat, "longitude" to pickupLng),
                        "dropoff" to mapOf("latitude" to dropoffLat, "longitude" to dropoffLng),
                        "pickupAddress" to pickupAddress,
                        "dropoffAddress" to dropoffAddress,
                        "paymentMethod" to method,
                        "rideType" to rideType
                    )
                )
                val quoteId = quote["quoteId"] as? String ?: error("The server did not return a fare quote.")
                val breakdown = quote["breakdown"] as? Map<*, *> ?: error("The server returned an invalid fare quote.")
                val totalMinor = (breakdown["customerTotalMinor"] as? Number)?.toLong()
                    ?: error("The server returned an invalid fare amount.")
                RideFareQuote(quoteId, totalMinor / 100.0)
            }.onSuccess { result ->
                if (currentUser?.uid == uid) _rideFareQuote.value = result
            }.onFailure { error ->
                if (currentUser?.uid == uid) _requestError.value = error.message ?: "Could not calculate the ride fare."
            }
        }
    }

    fun requestTrip(
        pickupAddress: String,
        pickupLat: Double,
        pickupLng: Double,
        dropoffAddress: String,
        dropoffLat: Double,
        dropoffLng: Double,
        fare: Double,
        quoteId: String,
        passengerName: String,
        passengerPhone: String,
        targetDriver: UserProfile? = null,
        paymentMethod: String = "CASH",
        onSuccess: (String) -> Unit = {}
    ) {
        if (currentUser?.uid == null) return
        screenModelScope.launch {
            runCatching {
                val created = com.zouitel.presentaion.screens.trustedTransactionCallData(
                    "createRideFromQuote",
                    mapOf("quoteId" to quoteId)
                )
                created["tripId"] as? String
                    ?: error("The server did not return the new trip ID.")
            }.onSuccess(onSuccess).onFailure { error ->
                _requestError.value = error.message ?: "Could not request this ride."
            }
        }
    }

    fun requestDelivery(
        pickupAddress: String,
        pickupLat: Double,
        pickupLng: Double,
        dropoffAddress: String,
        dropoffLat: Double,
        dropoffLng: Double,
        paymentMethod: String = "CASH",
        onSuccess: (String) -> Unit
    ) {
        if (currentUser?.uid == null) return
        screenModelScope.launch {
            runCatching {
                val method = if (paymentMethod.contains("GCASH", ignoreCase = true)) "GCASH" else "CASH"
                val quote = com.zouitel.presentaion.screens.trustedTransactionCallData(
                    "quoteDelivery",
                    mapOf(
                        "pickup" to mapOf("latitude" to pickupLat, "longitude" to pickupLng),
                        "dropoff" to mapOf("latitude" to dropoffLat, "longitude" to dropoffLng),
                        "pickupAddress" to pickupAddress,
                        "dropoffAddress" to dropoffAddress,
                        "paymentMethod" to method
                    )
                )
                val quoteId = quote["quoteId"] as? String
                    ?: error("The server did not return a delivery quote.")
                val created = com.zouitel.presentaion.screens.trustedTransactionCallData(
                    "createDeliveryFromQuote",
                    mapOf("quoteId" to quoteId)
                )
                created["orderId"] as? String
                    ?: error("The server did not return the new delivery booking ID.")
            }.onSuccess(onSuccess).onFailure { error ->
                _requestError.value = error.message ?: "Could not book this delivery."
            }
        }
    }

    fun cancelTrip(tripId: String) {
        screenModelScope.launch {
            com.zouitel.presentaion.screens.trustedTransactionCall("cancelPassengerTrip", mapOf("tripId" to tripId))
        }
    }
}