package com.zouitel.presentaion.screens.ride

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
    @SuppressLint("MissingPermission")
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

        val context = LocalContext.current
        val isOnline by screenModel.isOnline.collectAsState()
        val hasActiveTrip = activeTrips.isNotEmpty()
        var myLocation by remember { mutableStateOf<LatLng?>(null) }

        var hasLocationPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
            )
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            hasLocationPermission = result.values.any { it }
            if (hasLocationPermission) screenModel.setOnline(true)
        }
        val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }

        DisposableEffect(isOnline, hasLocationPermission) {
            if (!isOnline || !hasLocationPermission) {
                return@DisposableEffect onDispose { }
            }

            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
                .setMinUpdateDistanceMeters(10f)
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { loc ->
                        myLocation = LatLng(loc.latitude, loc.longitude)
                        screenModel.updateDriverLocation(loc.latitude, loc.longitude, loc.bearing)
                    }
                }
            }

            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

            onDispose { fusedClient.removeLocationUpdates(callback) }
        }

        LaunchedEffect(myLocation == null) {
            myLocation?.let {
                runCatching { cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(it, 15f), 600) }
            }
        }

        val requests = if (isOnline) availableTrips else emptyList()
        val currentList = if (selectedTab == 0) requests else activeTrips
        val focusedTrip = currentList.firstOrNull { it.id == focusedTripId }

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

        LaunchedEffect(activeTrips.firstOrNull()?.id) {
            if (selectedTab == 1) focusedTripId = activeTrips.firstOrNull()?.id
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Driver Dashboard", fontWeight = FontWeight.Bold) },
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
                    myLocation?.let {
                        Marker(
                            state = rememberUpdatedMarkerState(position = it),
                            title = "You",
                            icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
                        )
                    }

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

                // Online Status Banner Card
                Card(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (isOnline) "You're online" else "You're offline",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                when {
                                    isOnline && hasActiveTrip -> "Finish trip to go offline"
                                    isOnline -> "Receiving ride requests"
                                    else -> "Go online to receive requests"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isOnline,
                            enabled = !(isOnline && hasActiveTrip),
                            onCheckedChange = { wantOnline ->
                                if (wantOnline && !hasLocationPermission) {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                } else {
                                    screenModel.setOnline(wantOnline)
                                }
                            }
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.5f),
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Modern Segmented Pill Tabs
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.fillMaxWidth().padding(16.dp)
                            ) {
                                SegmentedButton(
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0; focusedTripId = null },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                                ) {
                                    Text("Requests (${requests.size})")
                                }
                                SegmentedButton(
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1; focusedTripId = activeTrips.firstOrNull()?.id },
                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                                ) {
                                    Text("Active Trip (${activeTrips.size})")
                                }
                            }

                            if (isLoading) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else if (currentList.isEmpty()) {
                                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        if (selectedTab == 0) {
                                            if (isOnline) "No ride requests available right now."
                                            else "You're offline. Switch online to receive requests."
                                        }
                                        else "You have no active trips right now.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isFocused) 4.dp else 1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(trip.passengerName, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "₱${"%.2f".format(trip.fare)}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Pickup: ${trip.pickupLocation}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("Destination: ${trip.dropoffLocation}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "Phone: ${trip.passengerPhone.ifBlank { "N/A" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    "Payment: ${trip.paymentMethod.ifBlank { "CASH" }} · ${trip.paymentStatus.replace('_', ' ')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (trip.cashCollectionStatus == "COLLECTED") {
                    Text(
                        "Cash received: ₱${"%.2f".format(trip.cashCollectedAmountCentavos / 100.0)} · Driver earnings: ₱${"%.2f".format(trip.cashCollectorEarningsCentavos / 100.0)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Platform commission: ₱${"%.2f".format(trip.cashPlatformOwedCentavos / 100.0)} · Remittance outstanding: ₱${"%.2f".format(trip.cashOutstandingRemittanceCentavos / 100.0)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                Spacer(Modifier.height(16.dp))

                if (!isActiveTab) {
                    Button(
                        onClick = onAccept,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Accept Trip", fontWeight = FontWeight.Bold)
                    }
                } else {
                    when (trip.status) {
                        "ACCEPTED" -> Button(
                            onClick = { onUpdateStatus("IN_PROGRESS") },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Start Trip", fontWeight = FontWeight.Bold) }

                        "IN_PROGRESS" -> Button(
                            onClick = { onUpdateStatus("COMPLETED") },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Complete Trip", fontWeight = FontWeight.Bold) }

                        else -> Text("Trip Status: ${trip.status}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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