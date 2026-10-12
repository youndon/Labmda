package com.zouitel.presentaion.screens.ride

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.zouitel.presentaion.common.Trip

class DriverDashboardScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { DriverDashboardScreenModel() }
        val availableTrips by screenModel.availableTrips.collectAsState()
        val activeTrips by screenModel.activeTrips.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        var selectedTab by remember { mutableIntStateOf(0) }
        var focusedTripId by remember { mutableStateOf<String?>(null) }
        val cameraPositionState = rememberCameraPositionState()
        val routeColor = MaterialTheme.colorScheme.primary

        val currentList = if (selectedTab == 0) availableTrips else activeTrips
        val focusedTrip = currentList.firstOrNull { it.id == focusedTripId }

        // Frame the pickup and the destination of the trip the driver tapped
        LaunchedEffect(focusedTrip?.id) {
            val trip = focusedTrip ?: return@LaunchedEffect
            val p = trip.pickupPoint()
            val d = trip.dropoffPoint()
            runCatching {
                when {
                    p != null && d != null -> cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().include(p).include(d).build(), 140),
                        800
                    )
                    p != null -> cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(p, 15f), 600)
                }
            }
        }

        // When a new active trip appears, focus it automatically
        LaunchedEffect(activeTrips.firstOrNull()?.id) {
            if (selectedTab == 1) focusedTripId = activeTrips.firstOrNull()?.id
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Driver Dashboard") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    uiSettings = MapUiSettings(zoomControlsEnabled = false),
                    contentPadding = PaddingValues(bottom = 340.dp)
                ) {
                    currentList.forEach { trip ->
                        val p = trip.pickupPoint()
                        val d = trip.dropoffPoint()
                        val isFocused = trip.id == focusedTripId

                        p?.let {
                            Marker(
                                state = rememberUpdatedMarkerState(position = it),
                                title = "Pickup: ${trip.passengerName}",
                                snippet = trip.pickupLocation,
                                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
                                alpha = if (isFocused || focusedTripId == null) 1f else 0.5f,
                                onClick = {
                                    focusedTripId = trip.id
                                    false
                                }
                            )
                        }
                        d?.let {
                            Marker(
                                state = rememberUpdatedMarkerState(position = it),
                                title = "Destination",
                                snippet = trip.dropoffLocation,
                                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
                                alpha = if (isFocused || focusedTripId == null) 1f else 0.5f,
                                onClick = {
                                    focusedTripId = trip.id
                                    false
                                }
                            )
                        }
                        if (isFocused && p != null && d != null) {
                            Polyline(points = listOf(p, d), color = routeColor, width = 8f)
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.5f),
                        shape = MaterialTheme.shapes.large,
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            TabRow(selectedTabIndex = selectedTab) {
                                Tab(
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0; focusedTripId = null },
                                    text = { Text("Requests (${availableTrips.size})") }
                                )
                                Tab(
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1; focusedTripId = activeTrips.firstOrNull()?.id },
                                    text = { Text("Active Trip (${activeTrips.size})") }
                                )
                            }

                            if (isLoading) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else if (currentList.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        if (selectedTab == 0) "No ride requests available."
                                        else "You have no active trips right now."
                                    )
                                }
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(currentList, key = { it.id }) { trip ->
                                        DashboardTripCard(
                                            trip = trip,
                                            isFocused = trip.id == focusedTripId,
                                            isActiveTab = selectedTab == 1,
                                            onFocus = { focusedTripId = trip.id },
                                            onAccept = {
                                                screenModel.acceptTrip(trip)
                                                selectedTab = 1
                                                focusedTripId = trip.id
                                            },
                                            onUpdateStatus = { status -> screenModel.updateTripStatus(trip.id, status) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DashboardTripCard(
        trip: Trip,
        isFocused: Boolean,
        isActiveTab: Boolean,
        onFocus: () -> Unit,
        onAccept: () -> Unit,
        onUpdateStatus: (String) -> Unit
    ) {
        Card(
            onClick = onFocus,
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isFocused) 8.dp else 3.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(trip.passengerName, style = MaterialTheme.typography.titleMedium)
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text("$${"%.2f".format(trip.fare)}", modifier = Modifier.padding(4.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("Pickup: ${trip.pickupLocation}", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("Destination: ${trip.dropoffLocation}", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Phone: ${trip.passengerPhone.ifBlank { "N/A" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                if (!isActiveTab) {
                    Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Accept Trip")
                    }
                } else {
                    when (trip.status) {
                        "ACCEPTED" -> Button(
                            onClick = { onUpdateStatus("IN_PROGRESS") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Start Trip") }

                        "IN_PROGRESS" -> Button(
                            onClick = { onUpdateStatus("COMPLETED") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Complete Trip") }

                        else -> Text("Trip Status: ${trip.status}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun Trip.pickupPoint(): LatLng? =
    if (pickupLat != 0.0 || pickupLng != 0.0) LatLng(pickupLat, pickupLng) else null

private fun Trip.dropoffPoint(): LatLng? =
    if (dropoffLat != 0.0 || dropoffLng != 0.0) LatLng(dropoffLat, dropoffLng) else null