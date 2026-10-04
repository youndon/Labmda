package com.zouitel.presentaion.screens.ride

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.zouitel.presentaion.model.Trip
import com.zouitel.presentaion.model.TripStatus
import com.zouitel.presentaion.screens.ride.driver.DriverScreenModel
import com.zouitel.presentaion.screens.ride.passenger.TripRatingScreen
import kotlinx.coroutines.launch

class PassengerActiveTripScreen(
    private val initialTrip: Trip
) : Screen {

    @SuppressLint("UnrememberedMutableState")
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val coroutineScope = rememberCoroutineScope()
        val screenModel = koinScreenModel<DriverScreenModel>()

        // 1. Observe Trip Status
        var currentTrip by remember { mutableStateOf(initialTrip) }

        LaunchedEffect(initialTrip.id) {
            screenModel.observeTrip(initialTrip.id).collect { updatedTrip ->
                if (updatedTrip != null) {
                    currentTrip = updatedTrip

                    // Handle Trip Termination
                    when (updatedTrip.status) {
                        TripStatus.COMPLETED -> {
                            // TODO: Navigate to Rating/Tip Screen
                            navigator.pop()
                        }
                        TripStatus.CANCELLED -> {
                            // Driver canceled the trip
                            navigator.pop()
                        }
                        else -> {}
                    }
                }
            }
        }

        // 2. Observe Live Driver Location
        var driverLocation by remember { mutableStateOf<LatLng?>(null) }

        LaunchedEffect(currentTrip.driverId) {
            val driverId = currentTrip.driverId
            if (!driverId.isNullOrBlank()) {
                screenModel.observeDriverLocation(driverId).collect { location ->
                    if (location != null) {
                        driverLocation = location
                    }
                }
            }
        }

//        val pickupLatLng = LatLng(currentTrip.pickupLocation.latitude, currentTrip.pickupLocation.longitude)
//        val dropoffLatLng = LatLng(currentTrip.dropoffLocation.latitude, currentTrip.dropoffLocation.longitude)
        val pickupLatLng = LatLng(currentTrip.pickupLocation.latitude, currentTrip.pickupLocation.longitude)
        val dropoffLatLng = LatLng(currentTrip.dropoffLocation.latitude, currentTrip.dropoffLocation.longitude)

        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(pickupLatLng, 15f)
        }
// Auto-adjust map zoom bounds when driver location updates
        LaunchedEffect(driverLocation) {
            val builder = com.google.android.gms.maps.model.LatLngBounds.Builder()
            builder.include(pickupLatLng)
            builder.include(dropoffLatLng)

            driverLocation?.let { driverLatLng ->
                builder.include(driverLatLng)
            }

            val bounds = builder.build()
            // Animate camera to fit all points with 120px padding
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngBounds(bounds, 120),
                durationMs = 1000
            )
        }
        // Auto-pan camera when driver location updates (optional refinement)
//        LaunchedEffect(driverLocation) {
//            driverLocation?.let { loc ->
//                if (currentTrip.status == TripStatus.ACCEPTED || currentTrip.status == TripStatus.ARRIVED) {
//                    cameraPositionState.animate(CameraUpdateFactory.newLatLng(loc))
//                }
//            }
//        }
        LaunchedEffect(initialTrip.id) {
            screenModel.observeTrip(initialTrip.id).collect { updatedTrip ->
                if (updatedTrip != null) {
                    currentTrip = updatedTrip

                    when (updatedTrip.status) {
                        TripStatus.COMPLETED -> {
                            // Navigate to post-trip rating screen replacing active trip screen[cite: 16]
                            navigator.replace(TripRatingScreen(updatedTrip))
                        }
                        TripStatus.CANCELLED -> {
                            navigator.pop() // Driver cancelled[cite: 16]
                        }
                        else -> {}
                    }
                }
            }
        }
        // Add camera boundary auto-fit logic to PassengerActiveTripScreen.kt[cite: 16]



        Box(modifier = Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                uiSettings = MapUiSettings(zoomControlsEnabled = false)
            ) {
                // Static Trip Markers
                Marker(
                    state = MarkerState(position = pickupLatLng),
                    title = "Pickup",
                    snippet = currentTrip.pickupLocation.addressName
                )
                Marker(
                    state = MarkerState(position = dropoffLatLng),
                    title = "Dropoff",
                    snippet = currentTrip.dropoffLocation.addressName
                )

                // Live Driver Marker
                driverLocation?.let { loc ->
                    Marker(
                        state = MarkerState(position = loc),
                        title = "Driver",
                        // Note: To use a custom car icon, set icon = BitmapDescriptorFactory.fromResource(R.drawable.car_icon)
                    )
                }

                Polyline(
                    points = listOf(pickupLatLng, dropoffLatLng),
                    color = MaterialTheme.colorScheme.primary,
                    width = 12f
                )
            }

            // Top Bar
            SmallFloatingActionButton(
                onClick = { navigator.pop() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }

            // Bottom Status Panel
            PassengerTripBottomPanel(
                trip = currentTrip,
                modifier = Modifier.align(Alignment.BottomCenter),
                onCancelTrip = {
                    coroutineScope.launch {
                        screenModel.cancelTrip(currentTrip.id, "Passenger requested cancellation")
                        navigator.pop()
                    }
                }
            )
        }
    }

    @Composable
    private fun PassengerTripBottomPanel(
        trip: Trip,
        onCancelTrip: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            shadowElevation = 24.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(24.dp)
            ) {
                // Dynamic Status Message
                AnimatedContent(targetState = trip.status, label = "trip_status_text") { status ->
                    val (title, subtitle) = when (status) {
                        TripStatus.SEARCHING -> "Finding a Driver" to "Looking for nearby cars..."
                        TripStatus.ACCEPTED -> "Driver is on the way" to "Arriving shortly"
                        TripStatus.ARRIVED -> "Driver has arrived" to "Meet your driver at the pickup point"
                        TripStatus.IN_PROGRESS -> "En route to destination" to trip.dropoffLocation.addressName
                        else -> "Processing Trip" to "Please wait..."
                    }

                    Column {
                        Text(text = title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = subtitle, fontSize = 14.sp, color = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))

                // Driver Info (Appears once accepted)
                if (trip.driverId?.isNotBlank() == true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Your Driver", color = Color.Gray, fontSize = 12.sp)
                            Text(text = trip.driverId ?: "NON", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("4.9", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Cancel Button (Only available before the trip actually starts)
                if (trip.status == TripStatus.SEARCHING || trip.status == TripStatus.ACCEPTED || trip.status == TripStatus.ARRIVED) {
                    OutlinedButton(
                        onClick = onCancelTrip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.error))
                    ) {
                        Icon(Icons.Default.Cancel, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cancel Ride", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}