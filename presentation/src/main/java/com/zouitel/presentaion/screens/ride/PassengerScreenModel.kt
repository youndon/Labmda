package com.zouitel.presentaion.screens.ride

import com.zouitel.presentaion.data.repository.TripsRepository
import com.zouitel.presentaion.data.repository.UsersRepository
import com.zouitel.presentaion.payments.ServiceActionsRepository

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Trip
import com.zouitel.presentaion.model.UserProfile
import com.zouitel.presentaion.payments.PaymentCreationResult
import com.zouitel.presentaion.payments.PricingQuoteResult
import com.zouitel.presentaion.payments.PaymentRepository
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Name and phone used to prefill the request form. */
data class PassengerInfo(val name: String = "", val phone: String = "")

class PassengerScreenModel(
    private val tripsRepository: TripsRepository,
    private val usersRepository: UsersRepository,
    private val payments: PaymentRepository = PaymentRepository()
) : ScreenModel {
    private val serviceActions = ServiceActionsRepository()
    private val currentUser = Firebase.auth.currentUser
    private var pendingTripFingerprint: String? = null
    private var pendingTripIdempotencyKey: String? = null
    private var isRequestingTrip = false

    private val _trips = MutableStateFlow<List<Trip>>(emptyList())
    val trips: StateFlow<List<Trip>> = _trips.asStateFlow()

    private val _onlineDrivers = MutableStateFlow<List<UserProfile>>(emptyList())
    val onlineDrivers: StateFlow<List<UserProfile>> = _onlineDrivers.asStateFlow()

    private val _passengerInfo = MutableStateFlow(PassengerInfo())
    val passengerInfo: StateFlow<PassengerInfo> = _passengerInfo.asStateFlow()

    private val _refundNotice = MutableStateFlow<String?>(null)
    val refundNotice: StateFlow<String?> = _refundNotice.asStateFlow()

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
            usersRepository.observePassengerInfo(uid).collect { (name, phone) ->
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
            tripsRepository.observePassengerTrips(uid).collect { list ->
                _trips.value = list
                _isLoading.value = false
            }
        }
    }

    private fun listenToOnlineDrivers() {
        screenModelScope.launch {
            usersRepository.observeOnlineDrivers().collect { drivers ->
                _onlineDrivers.value = drivers
            }
        }
    }

    suspend fun quoteTrip(
        pickupLat: Double,
        pickupLng: Double,
        dropoffLat: Double,
        dropoffLng: Double,
        serviceType: String
    ): PricingQuoteResult = payments.quoteTrip(
        pickupLat = pickupLat,
        pickupLng = pickupLng,
        dropoffLat = dropoffLat,
        dropoffLng = dropoffLng,
        serviceType = serviceType
    )

    fun requestTrip(
        pickupAddress: String,
        pickupLat: Double,
        pickupLng: Double,
        dropoffAddress: String,
        dropoffLat: Double,
        dropoffLng: Double,
        passengerName: String,
        passengerPhone: String,
        serviceType: String,
        pricingQuoteId: String,
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
            passengerName, passengerPhone, targetDriver?.uid.orEmpty(),
            serviceType.trim().lowercase(), pricingQuoteId
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
                // No client fare is accepted by this request. Cloud Functions calculate the amount
                // from server-managed service pricing and the submitted route coordinates.
                val result = payments.createTrip(
                    idempotencyKey = idempotencyKey,
                    serviceType = serviceType.trim().lowercase(),
                    pickupLocation = pickupAddress,
                    pickupLat = pickupLat,
                    pickupLng = pickupLng,
                    dropoffLocation = dropoffAddress,
                    dropoffLat = dropoffLat,
                    dropoffLng = dropoffLng,
                    passengerName = passengerName.ifBlank { "Passenger" },
                    passengerPhone = passengerPhone,
                    targetDriverId = targetDriver?.uid,
                    paymentMethod = paymentMethod,
                    pricingQuoteId = pricingQuoteId
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
            runCatching { serviceActions.transitionTripStatus(tripId, "CANCELLED") }
                .onFailure { _refundNotice.value = it.message ?: "Could not cancel this trip." }
        }
    }

    fun requestRefund(tripId: String) {
        screenModelScope.launch {
            runCatching { serviceActions.requestPaymentRefund("TRIP", tripId, UUID.randomUUID().toString()) }
                .onSuccess { _refundNotice.value = "Refund request submitted for administrator review. Your trip has not been cancelled." }
                .onFailure { _refundNotice.value = it.message ?: "Could not submit refund request." }
        }
    }
}