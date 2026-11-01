package com.zouitel.presentaion.screens.ride

import com.zouitel.presentaion.common.decodeRoutePolyline

import com.zouitel.presentaion.model.formatPhpPesos
import com.zouitel.presentaion.model.formatPhpCentavos

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import java.util.Locale
import kotlin.math.ceil

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
        val isOnline by screenModel.isOnline.collectAsState()
        val actionError by screenModel.cashActionError.collectAsState()
        val actionMessage by screenModel.actionMessage.collectAsState()
        val actionInProgressTripIds by screenModel.actionInProgressTripIds.collectAsState()

        var selectedTab by remember { mutableIntStateOf(0) }
        var focusedTripId by remember { mutableStateOf<String?>(null) }
        var myLocation by remember { mutableStateOf<LatLng?>(null) }

        val context = LocalContext.current
        val cameraPositionState = rememberCameraPositionState()
        val snackbarHostState = remember { SnackbarHostState() }
        val routeColor = MaterialTheme.colorScheme.primary

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
                    result.lastLocation?.let { location ->
                        myLocation = LatLng(location.latitude, location.longitude)
                        screenModel.updateDriverLocation(location.latitude, location.longitude, location.bearing)
                    }
                }
            }

            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            onDispose { fusedClient.removeLocationUpdates(callback) }
        }

        val requests = if (isOnline) availableTrips else emptyList()
        val currentList = if (selectedTab == 0) requests else activeTrips
        val focusedTrip = currentList.firstOrNull { it.id == focusedTripId }
            ?: currentList.firstOrNull()
        val routePoints = remember(focusedTrip?.routePolyline) {
            decodeRoutePolyline(focusedTrip?.routePolyline.orEmpty())
        }

        LaunchedEffect(selectedTab, currentList.map { it.id }) {
            if (focusedTripId !in currentList.map { it.id }) {
                focusedTripId = currentList.firstOrNull()?.id
            }
        }

        LaunchedEffect(focusedTrip?.id, routePoints) {
            val trip = focusedTrip
            val points = buildList {
                addAll(routePoints)
                trip?.pickupPoint()?.let(::add)
                trip?.dropoffPoint()?.let(::add)
            }.distinct()

            if (points.size >= 2) {
                runCatching {
                    val bounds = LatLngBounds.Builder()
                    points.forEach(bounds::include)
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngBounds(bounds.build(), 120),
                        750
                    )
                }
            } else if (points.size == 1) {
                runCatching {
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(points.first(), 15f), 600)
                }
            } else {
                myLocation?.let { location ->
                    runCatching {
                        cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(location, 15f), 600)
                    }
                }
            }
        }

        LaunchedEffect(actionError) {
            actionError?.let { message ->
                snackbarHostState.showSnackbar(message)
                screenModel.clearCashActionError()
            }
        }
        LaunchedEffect(actionMessage) {
            actionMessage?.let { message ->
                snackbarHostState.showSnackbar(message)
                screenModel.clearActionMessage()
            }
        }

        val hasActiveTrip = activeTrips.isNotEmpty()

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Driver dashboard", fontWeight = FontWeight.Bold)
                            Text(
                                if (hasActiveTrip) "Your current ride" else "Find your next ride",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = false,
                        mapToolbarEnabled = false,
                        compassEnabled = true
                    ),
                    contentPadding = PaddingValues(top = 112.dp, bottom = 355.dp)
                ) {
                    myLocation?.let { location ->
                        Marker(
                            state = rememberUpdatedMarkerState(position = location),
                            title = "Your location",
                            icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
                        )
                    }

                    focusedTrip?.let { trip ->
                        trip.pickupPoint()?.let { pickup ->
                            Marker(
                                state = rememberUpdatedMarkerState(position = pickup),
                                title = "Pickup · ${trip.passengerName}",
                                snippet = trip.pickupLocation,
                                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
                            )
                        }
                        trip.dropoffPoint()?.let { destination ->
                            Marker(
                                state = rememberUpdatedMarkerState(position = destination),
                                title = "Destination",
                                snippet = trip.dropoffLocation,
                                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                            )
                        }
                        // Prefer the server-provided Google Routes geometry. No straight-line
                        // substitute is drawn when a historical trip has no encoded route.
                        if (routePoints.size >= 2) {
                            Polyline(points = routePoints, color = routeColor, width = 10f)
                        }
                    }
                }

                DriverAvailabilityCard(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    isOnline = isOnline,
                    hasActiveTrip = hasActiveTrip,
                    requestCount = availableTrips.size,
                    onOnlineChanged = { wantOnline ->
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

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.57f),
                    shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 10.dp
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    if (selectedTab == 0) "Ride requests" else "Active ride",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (selectedTab == 0) {
                                        if (isOnline) "Choose a request to view its route" else "Go online to receive requests"
                                    } else {
                                        "Follow each step until the ride is complete"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (selectedTab == 0) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        requests.size.toString(),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        activeTrips.size.toString(),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            SegmentedButton(
                                selected = selectedTab == 0,
                                onClick = {
                                    selectedTab = 0
                                    focusedTripId = requests.firstOrNull()?.id
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                            ) {
                                Text("Requests")
                            }
                            SegmentedButton(
                                selected = selectedTab == 1,
                                onClick = {
                                    selectedTab = 1
                                    focusedTripId = activeTrips.firstOrNull()?.id
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                            ) {
                                Text("Active ride")
                            }
                        }

                        if (isLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else if (currentList.isEmpty()) {
                            DriverEmptyState(
                                isRequestsTab = selectedTab == 0,
                                isOnline = isOnline,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 14.dp,
                                    bottom = 28.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(currentList, key = { it.id }) { trip ->
                                    val busy = trip.id in actionInProgressTripIds
                                    if (selectedTab == 0) {
                                        DriverRequestCard(
                                            trip = trip,
                                            isFocused = trip.id == focusedTrip?.id,
                                            isBusy = busy,
                                            onFocus = { focusedTripId = trip.id },
                                            onAccept = {
                                                focusedTripId = trip.id
                                                screenModel.acceptTrip(trip)
                                                selectedTab = 1
                                            }
                                        )
                                    } else {
                                        DriverActiveRideCard(
                                            trip = trip,
                                            isFocused = trip.id == focusedTrip?.id,
                                            isBusy = busy,
                                            onFocus = { focusedTripId = trip.id },
                                            onUpdateStatus = { nextStatus ->
                                                screenModel.updateTripStatus(trip.id, nextStatus)
                                            }
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
}

@Composable
private fun DriverAvailabilityCard(
    modifier: Modifier = Modifier,
    isOnline: Boolean,
    hasActiveTrip: Boolean,
    requestCount: Int,
    onOnlineChanged: (Boolean) -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isOnline) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = null,
                        tint = if (isOnline) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isOnline) "You're online" else "You're offline",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    when {
                        hasActiveTrip -> "Complete your active ride before going offline"
                        isOnline && requestCount > 0 -> "$requestCount ride request(s) available"
                        isOnline -> "Waiting for nearby ride requests"
                        else -> "Go online when you're ready to drive"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = isOnline,
                enabled = !hasActiveTrip,
                onCheckedChange = onOnlineChanged
            )
        }
    }
}

@Composable
private fun DriverEmptyState(
    isRequestsTab: Boolean,
    isOnline: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isRequestsTab) Icons.Default.Route else Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            when {
                isRequestsTab && !isOnline -> "You're offline"
                isRequestsTab -> "No ride requests yet"
                else -> "No active ride"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(5.dp))
        Text(
            when {
                isRequestsTab && !isOnline -> "Switch online to start receiving requests that match your vehicle."
                isRequestsTab -> "Stay online. New requests will appear here when passengers book a ride."
                else -> "When you accept a ride, its pickup, road route and next action will appear here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DriverRequestCard(
    trip: Trip,
    isFocused: Boolean,
    isBusy: Boolean,
    onFocus: () -> Unit,
    onAccept: () -> Unit
) {
    Card(
        onClick = onFocus,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = if (isFocused) androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary
        ) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isFocused) 3.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("New ride request", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        trip.passengerName.ifBlank { "Passenger" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Passenger fare", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        formatDriverPhp(trip.fare),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            DriverRouteSummary(trip = trip)
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DriverInfoChip(
                    icon = Icons.Default.DirectionsCar,
                    label = formatDriverVehicle(trip.serviceType)
                )
                DriverInfoChip(
                    icon = Icons.Default.Payments,
                    label = formatDriverPayment(trip.paymentMethod)
                )
                if (trip.routeDuration.isNotBlank()) {
                    DriverInfoChip(
                        icon = Icons.Default.AccessTime,
                        label = formatDriverEta(trip.routeDuration)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onAccept,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(9.dp))
                    Text("Accepting ride…")
                } else {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Accept ride", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DriverActiveRideCard(
    trip: Trip,
    isFocused: Boolean,
    isBusy: Boolean,
    onFocus: () -> Unit,
    onUpdateStatus: (String) -> Unit
) {
    val isCash = trip.paymentMethod.equals("CASH", ignoreCase = true)
    val cashCollected = trip.cashCollectionStatus.equals("COLLECTED", ignoreCase = true)
    val paymentConfirmed = trip.paymentStatus.equals("PAID", ignoreCase = true)
    val canComplete = isCash || paymentConfirmed
    val progress = when (trip.status.uppercase()) {
        "ACCEPTED" -> 0.25f
        "ARRIVED" -> 0.55f
        "IN_PROGRESS" -> 0.85f
        "COMPLETED" -> 1f
        else -> 0.1f
    }

    Card(
        onClick = onFocus,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = if (isFocused) androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary
        ) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.20f)
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isFocused) 3.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ACTIVE RIDE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        trip.passengerName.ifBlank { "Passenger" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        formatDriverStatus(trip.status),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Accepted", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Pickup", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("On trip", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))
            DriverRouteSummary(trip = trip, prominent = true)

            if (trip.routeDuration.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "Server route estimate · ${formatDriverEta(trip.routeDuration)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Passenger", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        trip.passengerName.ifBlank { "Passenger" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (trip.passengerPhone.isNotBlank()) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.width(5.dp))
                            Text(
                                trip.passengerPhone,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            DriverPaymentSummary(trip = trip)

            Spacer(Modifier.height(16.dp))
            when (trip.status.uppercase()) {
                "ACCEPTED" -> DriverActionButton(
                    label = "I've arrived at pickup",
                    busyLabel = "Updating ride…",
                    isBusy = isBusy,
                    onClick = { onUpdateStatus("ARRIVED") }
                )
                "ARRIVED" -> DriverActionButton(
                    label = "Start trip",
                    busyLabel = "Starting trip…",
                    isBusy = isBusy,
                    onClick = { onUpdateStatus("IN_PROGRESS") }
                )
                "IN_PROGRESS" -> {
                    DriverActionButton(
                        label = if (isCash) "Confirm cash & complete ride" else "Complete ride",
                        busyLabel = if (isCash) "Confirming collection…" else "Completing ride…",
                        isBusy = isBusy,
                        enabled = canComplete,
                        onClick = { onUpdateStatus("COMPLETED") }
                    )
                    if (!canComplete) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Completion is enabled after the server confirms the GCash payment.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (isCash && !cashCollected) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Confirm only after you have received the passenger's cash payment.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> Text(
                    "Current ride status: ${formatDriverStatus(trip.status)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DriverRouteSummary(
    trip: Trip,
    prominent: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp).size(if (prominent) 21.dp else 18.dp)
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("PICKUP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    trip.pickupLocation.ifBlank { "Pickup location unavailable" },
                    style = if (prominent) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.Flag,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp).size(if (prominent) 21.dp else 18.dp)
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("DESTINATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    trip.dropoffLocation.ifBlank { "Destination unavailable" },
                    style = if (prominent) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun DriverPaymentSummary(trip: Trip) {
    val isCash = trip.paymentMethod.equals("CASH", ignoreCase = true)
    val isCollected = trip.cashCollectionStatus.equals("COLLECTED", ignoreCase = true)
    val methodLabel = formatDriverPayment(trip.paymentMethod)
    val statusLabel = when {
        isCash && isCollected -> "Cash collected"
        isCash -> "Collect in person"
        trip.paymentStatus.equals("PAID", ignoreCase = true) -> "Paid"
        else -> formatDriverStatus(trip.paymentStatus)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isCash) Icons.Default.Payments else Icons.Default.AccountBalanceWallet,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(methodLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isCollected || trip.paymentStatus.equals("PAID", true)) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(Modifier.height(9.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Passenger fare", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    formatDriverPhp(trip.fare),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            if (isCollected) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                Spacer(Modifier.height(6.dp))
                DriverMoneyRow("Cash received", trip.cashCollectedAmountCentavos)
                DriverMoneyRow("Your earnings", trip.cashCollectorEarningsCentavos)
                DriverMoneyRow("Platform commission", trip.cashPlatformOwedCentavos)
                DriverMoneyRow("Remittance outstanding", trip.cashOutstandingRemittanceCentavos)
            }
        }
    }
}

@Composable
private fun DriverMoneyRow(label: String, amountCentavos: Long) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatDriverPhp(amountCentavos / 100.0), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DriverActionButton(
    label: String,
    busyLabel: String,
    isBusy: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isBusy,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(9.dp))
            Text(busyLabel)
        } else {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DriverInfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

private fun Trip.pickupPoint(): LatLng? =
    if (pickupLat != 0.0 || pickupLng != 0.0) LatLng(pickupLat, pickupLng) else null

private fun Trip.dropoffPoint(): LatLng? =
    if (dropoffLat != 0.0 || dropoffLng != 0.0) LatLng(dropoffLat, dropoffLng) else null

private fun formatDriverEta(duration: String): String {
    val seconds = duration.removeSuffix("s").toDoubleOrNull() ?: return "ETA unavailable"
    if (!seconds.isFinite() || seconds <= 0.0) return "ETA unavailable"
    val minutes = maxOf(1, ceil(seconds / 60.0).toInt())
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
}

private fun formatDriverPhp(amount: Double): String = "₱${formatPhpPesos(amount)}"

private fun formatDriverVehicle(value: String): String =
    value.takeIf { it.isNotBlank() && !it.equals("RIDE", true) }
        ?.lowercase()
        ?.replace('_', ' ')
        ?.replaceFirstChar { it.uppercase() }
        ?: "Ride"

private fun formatDriverPayment(value: String): String =
    when (value.uppercase()) {
        "CASH" -> "Cash"
        "GCASH", "QR_PH", "XENDIT" -> "GCash"
        else -> value.takeIf { it.isNotBlank() }?.replace('_', ' ') ?: "Payment pending"
    }

private fun formatDriverStatus(value: String): String =
    value.takeIf { it.isNotBlank() }
        ?.lowercase()
        ?.replace('_', ' ')
        ?.replaceFirstChar { it.uppercase() }
        ?: "Unknown"
