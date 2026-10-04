package com.zouitel.presentaion.screens.ride

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
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
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.zouitel.presentaion.model.Trip
import com.zouitel.presentaion.model.TripStatus
import com.zouitel.presentaion.screens.ride.driver.DriverScreenModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DriverActiveTripScreen(
    private val initialTrip: Trip
) : Screen {

    @SuppressLint("UnrememberedMutableState")
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val coroutineScope = rememberCoroutineScope()

        // Inject the Voyager ScreenModel via Koin
        val screenModel = koinScreenModel<DriverScreenModel>()

        // Observe the trip in real-time from Firebase RTDB to catch passenger cancellations
        var currentTrip by remember { mutableStateOf(initialTrip) }

        LaunchedEffect(initialTrip.id) {
            screenModel.observeTrip(initialTrip.id).collect { updatedTrip ->
                if (updatedTrip != null) {
                    currentTrip = updatedTrip
                    if (updatedTrip.status == TripStatus.CANCELLED) {
                        navigator.pop() // Passenger cancelled, exit active trip
                    }
                }
            }
        }

        // --- Live Location Broadcasting (Simulation) ---
        // In production, this would be tied to a FusedLocationProviderClient foreground service.
        LaunchedEffect(currentTrip.status) {
            val driverId = currentTrip.driverId ?: return@LaunchedEffect

            // Only broadcast location if we are driving to the passenger or destination
            if (currentTrip.status == TripStatus.ACCEPTED || currentTrip.status == TripStatus.IN_PROGRESS) {
                while (isActive) {
                    // Simulating a minor GPS position shift for demonstration
                    val simulatedLat = currentTrip.pickupLocation.latitude + (Math.random() * 0.0001)
                    val simulatedLng = currentTrip.pickupLocation.longitude + (Math.random() * 0.0001)

                    screenModel.updateDriverLocation(driverId, simulatedLat, simulatedLng)

                    delay(3000) // Broadcast every 3 seconds
                }
            }
        }

        val pickupLatLng = LatLng(currentTrip.pickupLocation.latitude, currentTrip.pickupLocation.longitude)
        val dropoffLatLng = LatLng(currentTrip.dropoffLocation.latitude, currentTrip.dropoffLocation.longitude)

        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(pickupLatLng, 15f)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                uiSettings = MapUiSettings(zoomControlsEnabled = false)
            ) {
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

                Polyline(
                    points = listOf(pickupLatLng, dropoffLatLng),
                    color = MaterialTheme.colorScheme.primary,
                    width = 12f
                )
            }

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

            ActiveTripBottomPanel(
                trip = currentTrip,
                modifier = Modifier.align(Alignment.BottomCenter),
                onStatusChange = { newStatus ->
                    coroutineScope.launch {
                        val result = screenModel.updateTripStatus(currentTrip.id, newStatus)
                        if (result.isSuccess && newStatus == TripStatus.COMPLETED) {
                            navigator.pop()
                        }
                    }
                }
            )
        }
    }

    @Composable
    private fun ActiveTripBottomPanel(
        trip: Trip,
        onStatusChange: (TripStatus) -> Unit,
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Passenger", color = Color.Gray, fontSize = 12.sp)
                        Text(
                            text = trip.passengerId.ifBlank { "Guest Passenger" },
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                    Text(
                        text = "$${trip.fareAmount}",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isDrivingToDestination = trip.status == TripStatus.IN_PROGRESS
                    val icon = if (isDrivingToDestination) Icons.Default.LocationOn else Icons.Default.DirectionsCar
                    val label = if (isDrivingToDestination) "Dropoff Location" else "Pickup Location"
                    val address = if (isDrivingToDestination) trip.dropoffLocation.addressName else trip.pickupLocation.addressName

                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(label, fontSize = 12.sp, color = Color.Gray)
                        Text(
                            text = address.ifEmpty { "Location Coordinates Attached" },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                AnimatedContent(targetState = trip.status, label = "trip_action_button") { status ->
                    when (status) {
                        TripStatus.ACCEPTED -> {
                            TripActionButton(
                                text = "I've Arrived",
                                color = MaterialTheme.colorScheme.primary,
                                onClick = { onStatusChange(TripStatus.ARRIVED) }
                            )
                        }
                        TripStatus.ARRIVED -> {
                            TripActionButton(
                                text = "Start Trip",
                                color = Color(0xFF4CAF50),
                                onClick = { onStatusChange(TripStatus.IN_PROGRESS) }
                            )
                        }
                        TripStatus.IN_PROGRESS -> {
                            TripActionButton(
                                text = "Complete Trip",
                                color = Color(0xFFE91E63),
                                onClick = { onStatusChange(TripStatus.COMPLETED) }
                            )
                        }
                        else -> {
                            Button(
                                onClick = { },
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) {
                                Text("Processing...")
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TripActionButton(
        text: String,
        color: Color,
        onClick: () -> Unit
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color)
        ) {
            Text(
                text = text.uppercase(),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                letterSpacing = 1.sp
            )
        }
    }
}