package com.zouitel.presentaion.screens.ride

import com.zouitel.presentaion.data.repository.TripsRepository
import com.zouitel.presentaion.payments.ServiceActionsRepository

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Trip
import com.zouitel.presentaion.payments.PaymentRepository
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class DriverDashboardScreenModel(
    private val tripsRepository: TripsRepository
) : ScreenModel {
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
            tripsRepository.observeDriverOnline(uid).collect { online ->
                _isOnline.value = online
            }
        }
    }

    fun setOnline(online: Boolean) {
        val uid = currentUser?.uid ?: return
        screenModelScope.launch {
            tripsRepository.setDriverOnline(uid, online)
        }
    }

    private fun listenToDriverDashboardTrips() {
        val uid = currentUser?.uid ?: run {
            _isLoading.value = false
            return
        }
        screenModelScope.launch {
            tripsRepository.observeDriverTrips(uid).collect { feed ->
                _availableTrips.value = feed.availableTrips
                _activeTrips.value = feed.activeTrips
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
                val paymentMethod = tripsRepository.observeTripPaymentMethod(tripId).first()

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
            tripsRepository.updateDriverLocation(uid, latitude, longitude, heading)
        }
    }

    fun setDriverOffline() = setOnline(false)

    @OptIn(DelicateCoroutinesApi::class)
    override fun onDispose() {
        val uid = currentUser?.uid ?: return
        GlobalScope.launch {
            runCatching { tripsRepository.setDriverOffline(uid) }
        }
    }
}