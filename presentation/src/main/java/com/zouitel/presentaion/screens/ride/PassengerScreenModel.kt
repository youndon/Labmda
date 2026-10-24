package com.zouitel.presentaion.screens.ride

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Trip
import com.zouitel.presentaion.model.UserProfile
import com.zouitel.presentaion.model.VehicleType
import com.zouitel.presentaion.model.PaymentMethodType
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import dev.gitlive.firebase.functions.functions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.coroutines.launch

/** Name and phone used to prefill the request form. */
data class PassengerInfo(val name: String = "", val phone: String = "")

class PassengerScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val currentUser = Firebase.auth.currentUser

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
            db.child("trips").valueEvents.collect { snapshot ->
                val list = snapshot.children.mapNotNull { child ->
                    try {
                        val id = child.key ?: return@mapNotNull null
                        val passengerId = child.child("passengerId").value<String>()
                        if (passengerId != uid) return@mapNotNull null

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
                            paymentMethodType = str("PaymentMethodType").ifBlank { "CASH" },
                            paymentStatus = str("paymentStatus").ifBlank { "PENDING" },
                            paymentId = str("paymentId"),
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
                _isLoading.value = false
            }
        }
    }

    private fun listenToOnlineDrivers() {
        screenModelScope.launch {
            db.child("users").valueEvents.collect { snapshot ->
                val drivers = snapshot.children.mapNotNull { child ->
                    try {
                        val uid = child.key ?: return@mapNotNull null
                        val isOnline = runCatching { child.child("isDriverOnline").value<Boolean>() }.getOrDefault(false)
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

    /**
     * Creates the trip through trusted server code. The visible fare is only an estimate;
     * the server recalculates distance and fare before writing the trip and payment record.
     */
    fun requestTrip(
        pickupAddress: String,
        pickupLat: Double,
        pickupLng: Double,
        dropoffAddress: String,
        dropoffLat: Double,
        dropoffLng: Double,
        PaymentMethodType: PaymentMethodType,
        passengerName: String,
        passengerPhone: String,
        targetDriver: UserProfile? = null,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (currentUser?.uid.isNullOrBlank()) {
            onFailure("Please sign in before requesting a ride.")
            return
        }
        screenModelScope.launch {
            try {
                val response = Firebase.functions.httpsCallable("createRideTrip")(
                    CreateRideTripRequest(
                        pickupLocation = pickupAddress,
                        pickupLat = pickupLat,
                        pickupLng = pickupLng,
                        dropoffLocation = dropoffAddress,
                        dropoffLat = dropoffLat,
                        dropoffLng = dropoffLng,
                        passengerName = passengerName.ifBlank { "Passenger" },
                        passengerPhone = passengerPhone,
                        targetDriverUid = targetDriver?.uid,
                        PaymentMethodType = PaymentMethodType.name
                    )
                ).data<CreateRideTripResponse>()
                require(response.tripId.isNotBlank()) { "The server did not return a ride ID." }
                onSuccess(response.tripId)
            } catch (error: Exception) {
                onFailure(error.message ?: "Could not request the ride. Please try again.")
            }
        }
    }

    fun cancelTrip(tripId: String) {
        screenModelScope.launch {
            db.child("trips").child(tripId).child("status").setValue("CANCELLED")
        }
    }
}
@Serializable
private data class CreateRideTripRequest(
    val pickupLocation: String,
    val pickupLat: Double,
    val pickupLng: Double,
    val dropoffLocation: String,
    val dropoffLat: Double,
    val dropoffLng: Double,
    val passengerName: String,
    val passengerPhone: String,
    val targetDriverUid: String?,
    val PaymentMethodType: String
)

@Serializable
private data class CreateRideTripResponse(
    val tripId: String = "",
    val fareMinorUnits: Long = 0L,
    val currencyCode: String = "USD"
)
