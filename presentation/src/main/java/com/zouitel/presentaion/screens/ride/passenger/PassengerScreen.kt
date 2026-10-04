// Updated PassengerScreen snippet handling SEARCHING_DRIVER state with live bidding
package com.zouitel.presentaion.screens.ride

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.zouitel.presentaion.model.DriverOffer
import com.zouitel.presentaion.model.LocationInfo
import com.zouitel.presentaion.model.PassengerBookingState
import com.zouitel.presentaion.model.ServiceOption
import com.zouitel.presentaion.model.Trip
import com.zouitel.presentaion.model.TripStatus
import com.zouitel.presentaion.screens.ride.driver.DriverScreenModel
import com.zouitel.presentaion.screens.ride.passenger.PassengerBiddingBottomSheet
import kotlinx.coroutines.launch

class PassengerScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val screenModel = koinScreenModel<DriverScreenModel>()

        // Location Permission State
        var hasLocationPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            )
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        }

        LaunchedEffect(Unit) {
            if (!hasLocationPermission) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }

        // Booking State
        var bookingState by remember { mutableStateOf(PassengerBookingState.IDLE) }
        var activeTripId by remember { mutableStateOf<String?>(null) }
        var currentTripData by remember { mutableStateOf<Trip?>(null) }
        var incomingOffers by remember { mutableStateOf<List<DriverOffer>>(emptyList()) }
        var isRequestingTrip by remember { mutableStateOf(false) }

        // Observe Driver Offers when in SEARCHING_DRIVER state
        LaunchedEffect(activeTripId) {
            val tripId = activeTripId
            if (!tripId.isNullOrBlank()) {
                screenModel.observeTripOffers(tripId).collect { offers ->
                    incomingOffers = offers
                }
            }
        }

        val userLocation = remember { LatLng(36.7538, 3.0588) }
        val destinationLocation = remember { LatLng(36.7325, 3.0872) }

        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(userLocation, 14f)
        }

        val serviceOptions = remember {
            listOf(
                ServiceOption("std", "Standard", 12.50, 4, "🚗"),
                ServiceOption("cmf", "Comfort", 18.00, 2, "🚘"),
                ServiceOption("xl", "Ride XL", 25.00, 6, "🚐")
            )
        }
        var selectedService by remember { mutableStateOf(serviceOptions.first()) }

        Box(modifier = Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
                uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = true)
            ) {
                Marker(state = rememberMarkerState(position = userLocation), title = "Pickup")

                if (bookingState != PassengerBookingState.IDLE) {
                    Marker(state = rememberMarkerState(position = destinationLocation), title = "Destination")
                    Polyline(points = listOf(userLocation, destinationLocation), color = Color(0xFF2196F3), width = 12f)
                }
            }

            AnimatedContent(
                targetState = bookingState,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                label = "passenger_booking_panel"
            ) { state ->
                when (state) {
                    PassengerBookingState.IDLE -> {
                        QuickDestinationsCard(
                            onSelectDestination = {
                                bookingState = PassengerBookingState.SELECTING_SERVICE
                                coroutineScope.launch {
                                    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(destinationLocation, 13f))
                                }
                            }
                        )
                    }

                    PassengerBookingState.SELECTING_SERVICE -> {
                        ServiceSelectionCard(
                            options = serviceOptions,
                            selectedOption = selectedService,
                            isLoading = isRequestingTrip,
                            onOptionSelected = { selectedService = it },
                            onCancel = { bookingState = PassengerBookingState.IDLE },
                            onConfirmRide = {
                                isRequestingTrip = true
                                coroutineScope.launch {
                                    val newTrip = Trip(
                                        status = TripStatus.SEARCHING,
                                        serviceType = selectedService.name,
                                        fareAmount = selectedService.price,
                                        passengerId = "passenger_456",
                                        pickupLocation = LocationInfo(userLocation.latitude, userLocation.longitude, "Pickup Point"),
                                        dropoffLocation = LocationInfo(destinationLocation.latitude, destinationLocation.longitude, "Destination Point"),
                                        createdAt = System.currentTimeMillis()
                                    )

                                    val result = screenModel.createTrip(newTrip)
                                    isRequestingTrip = false

                                    result.onSuccess { tripId ->
                                        activeTripId = tripId
                                        currentTripData = newTrip.copy(id = tripId)
                                        bookingState = PassengerBookingState.SEARCHING_DRIVER
                                    }
                                }
                            }
                        )
                    }

                    PassengerBookingState.SEARCHING_DRIVER -> {
                        PassengerBiddingBottomSheet(
                            offers = incomingOffers,
                            onAcceptOffer = { selectedOffer ->
                                coroutineScope.launch {
                                    activeTripId?.let { tripId ->
                                        screenModel.acceptDriverOffer(tripId, selectedOffer)
                                        currentTripData?.let { trip ->
                                            val updatedTrip = trip.copy(
                                                driverId = selectedOffer.driverId,
                                                fareAmount = selectedOffer.offeredFare,
                                                status = TripStatus.ACCEPTED
                                            )
                                            bookingState = PassengerBookingState.IDLE
                                            navigator.push(PassengerActiveTripScreen(updatedTrip))
                                        }
                                    }
                                }
                            },
                            onDeclineOffer = { offerToDecline ->
                                incomingOffers = incomingOffers.filter { it.id != offerToDecline.id }
                            },
                            onCancelSearch = {
                                coroutineScope.launch {
                                    activeTripId?.let { screenModel.cancelTrip(it, "Passenger cancelled search") }
                                    bookingState = PassengerBookingState.IDLE
                                    activeTripId = null
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Helper Card components omitted for brevity (retained from original PassengerScreen)
    @Composable private fun QuickDestinationsCard(onSelectDestination: () -> Unit) { /* ... */ }
    @Composable private fun ServiceSelectionCard(options: List<ServiceOption>, selectedOption: ServiceOption, isLoading: Boolean, onOptionSelected: (ServiceOption) -> Unit, onCancel: () -> Unit, onConfirmRide: () -> Unit) { /* ... */ }
}