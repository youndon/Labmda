package com.zouitel.presentaion.screens.ride

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Trip
import com.zouitel.presentaion.model.UserProfile
import com.zouitel.presentaion.model.VehicleType
import com.zouitel.presentaion.screens.payment.PaymentCreationResult
import com.zouitel.presentaion.screens.payment.PaymentRepository
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Name and phone used to prefill the request form. */
data class PassengerInfo(val name: String = "", val phone: String = "")

class PassengerScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val currentUser = Firebase.auth.currentUser
    private val payments = PaymentRepository()
    private var pendingTripFingerprint: String? = null
    private var pendingTripIdempotencyKey: String? = null
    private var isRequestingTrip = false

    private val _trips = MutableStateFlow<List<Trip>>(emptyList())
    val trips: StateFlow<List<Trip>> = _trips.asStateFlow()

    private val _onlineDrivers = MutableStateFlow<List<UserProfile>>(emptyList())
    val onlineDrivers: StateFlow<List<UserProfile>> = _onlineDrivers.asStateFlow()

    private val _passengerInfo = MutableStateFlow(PassengerInfo())
    val passengerInfo: StateFlow<PassengerInfo> = _passengerInfo.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToPassengerTrips()
        listenToOnlineDrivers()
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
                            driverId = str("driverId"),
                            driverName = str("driverName"),
                            driverPhone = str("driverPhone"),
                            vehicleDetails = str("vehicleDetails"),
                            status = child.child("status").value<String>(),
                            paymentMethod = str("paymentMethod"),
                            paymentStatus = str("paymentStatus").ifBlank { "UNPAID" },
                            paymentId = str("paymentId"),
                            createdAtTimestamp = child.child("createdAtTimestamp").value<Long>()
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.sortedByDescending { it.createdAtTimestamp }

                _trips.value = list
                _isLoading.value = false
            }
        }
    }

    private fun listenToOnlineDrivers() {
        screenModelScope.launch {
            db.child("users").orderByChild("driverService/isOnline").equalTo(true).valueEvents.collect { snapshot ->
                val drivers = snapshot.children.mapNotNull { child ->
                    try {
                        val uid = child.key ?: return@mapNotNull null
                        val isOnline = runCatching { child.child("driverService").child("isOnline").value<Boolean>() }.getOrDefault(false)
                        if (!isOnline) return@mapNotNull null

                        val fullName = runCatching { child.child("fullName").value<String>() }.getOrDefault("Driver")
                        val phone = runCatching { child.child("phoneNumber").value<String>() }
                            .getOrElse { runCatching { child.child("phone").value<String>() }.getOrDefault("") }

                        val loc = child.child("currentLocation")
                        val lat = runCatching { loc.child("latitude").value<Double>() }.getOrDefault(0.0)
                        val lng = runCatching { loc.child("longitude").value<Double>() }.getOrDefault(0.0)
                        val heading = runCatching { loc.child("heading").value<Float>() }.getOrDefault(0f)

                        val v = child.child("vehicleInfo")
                        val typeStr = runCatching { v.child("type").value<String>() }.getOrDefault("CAR")
                        val vehicleType = runCatching { VehicleType.valueOf(typeStr) }.getOrDefault(VehicleType.CAR)

                        UserProfile(
                            uid = uid,
                            fullName = fullName,
                            phoneNumber = phone,

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

    @Suppress("UNUSED_PARAMETER")
    fun requestTrip(
        pickupAddress: String,
        pickupLat: Double,
        pickupLng: Double,
        dropoffAddress: String,
        dropoffLat: Double,
        dropoffLng: Double,
        fare: Double,
        passengerName: String,
        passengerPhone: String,
        targetDriver: UserProfile? = null,
        paymentMethod: String = "CASH",
        onSuccess: (PaymentCreationResult) -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        val uid = currentUser?.uid
        if (uid == null) {
            onFailure("Please sign in to request a ride.")
            return
        }
        if (isRequestingTrip) return

        val fingerprint = listOf(
            paymentMethod.trim().uppercase(),
            pickupAddress, pickupLat.toString(), pickupLng.toString(),
            dropoffAddress, dropoffLat.toString(), dropoffLng.toString(),
            passengerName, passengerPhone, targetDriver?.uid.orEmpty()
        ).joinToString("|")
        if (pendingTripFingerprint != fingerprint) {
            pendingTripFingerprint = fingerprint
            pendingTripIdempotencyKey = UUID.randomUUID().toString()
        }
        val idempotencyKey = pendingTripIdempotencyKey ?: UUID.randomUUID().toString().also {
            pendingTripIdempotencyKey = it
            pendingTripFingerprint = fingerprint
        }
        isRequestingTrip = true

        screenModelScope.launch {
            try {
                // The client-side fare is intentionally ignored. Cloud Functions calculate it
                // from server-managed service pricing and the submitted route coordinates.
                val result = payments.createTrip(
                    idempotencyKey = idempotencyKey,
                    serviceType = "RIDE",
                    pickupLocation = pickupAddress,
                    pickupLat = pickupLat,
                    pickupLng = pickupLng,
                    dropoffLocation = dropoffAddress,
                    dropoffLat = dropoffLat,
                    dropoffLng = dropoffLng,
                    passengerName = passengerName.ifBlank { "Passenger" },
                    passengerPhone = passengerPhone,
                    targetDriverId = targetDriver?.uid,
                    paymentMethod = paymentMethod
                )
                pendingTripFingerprint = null
                pendingTripIdempotencyKey = null
                onSuccess(result)
            } catch (e: Exception) {
                onFailure(e.message ?: "Could not request the trip. Please try again.")
            } finally {
                isRequestingTrip = false
            }
        }
    }

    fun cancelTrip(tripId: String) {
        screenModelScope.launch {
            db.child("trips").child(tripId).child("status").setValue("CANCELLED")
        }
    }
}