package com.zouitel.presentaion.screens.ride

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
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
import com.zouitel.presentaion.model.UserProfile
import com.zouitel.presentaion.model.VehicleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// Simple fare model: base + per-km, never below the minimum
private const val BASE_FARE = 2.0
private const val PER_KM_FARE = 1.2
private const val MIN_FARE = 4.0

private val ACTIVE_STATUSES = setOf("REQUESTED", "ACCEPTED", "IN_PROGRESS")

class PassengerScreen : Screen {

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel { PassengerScreenModel() }

        val trips by screenModel.trips.collectAsState()
        val onlineDrivers by screenModel.onlineDrivers.collectAsState()
        val passengerInfo by screenModel.passengerInfo.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        val cameraPositionState = rememberCameraPositionState()
        val snackbarHostState = remember { SnackbarHostState() }
        val routeColor = MaterialTheme.colorScheme.primary

        // --- Request state ---
        var pickup by remember { mutableStateOf<LatLng?>(null) }
        var destination by remember { mutableStateOf<LatLng?>(null) }
        var pickupAddress by remember { mutableStateOf("") }
        var destinationAddress by remember { mutableStateOf("") }
        var vehicleType by remember { mutableStateOf(VehicleType.CAR) }
        var selectedDriver by remember { mutableStateOf<UserProfile?>(null) }
        var requestSheetDriver by remember { mutableStateOf<UserProfile?>(null) }
        var showHistory by remember { mutableStateOf(false) }
        var hasCenteredOnPickup by remember { mutableStateOf(false) }

        // --- Location permission + automatic pickup ---
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
        ) { result -> hasLocationPermission = result.values.any { it } }

        val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }
        var tappedDriverId by remember { mutableStateOf<String?>(null) }
        val tappedDriver = onlineDrivers.firstOrNull { it.uid == tappedDriverId }

        fun locateMe() {
            if (!hasLocationPermission) {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
                return
            }
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        pickup = LatLng(loc.latitude, loc.longitude)
                        scope.launch {
                            runCatching {
                                cameraPositionState.animate(
                                    CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15f), 600
                                )
                            }
                        }
                    } else {
                        scope.launch { snackbarHostState.showSnackbar("Couldn't get your location. Is GPS turned on?") }
                    }
                }
                .addOnFailureListener {
                    scope.launch { snackbarHostState.showSnackbar("Couldn't get your location.") }
                }
        }

        LaunchedEffect(Unit) {
            if (!hasLocationPermission) {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
        }
        LaunchedEffect(hasLocationPermission) {
            if (hasLocationPermission) locateMe()
        }

        // Resolve readable addresses
        LaunchedEffect(pickup) {
            pickup?.let { pickupAddress = reverseGeocode(context, it) }
        }
        LaunchedEffect(destination) {
            destination?.let { destinationAddress = reverseGeocode(context, it) }
        }

        // Frame pickup + destination once both are known
        LaunchedEffect(pickup, destination) {
            val p = pickup
            val d = destination
            if (p != null && d != null) {
                runCatching {
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngBounds(
                            LatLngBounds.Builder().include(p).include(d).build(), 140
                        ),
                        800
                    )
                }
            } else if (p != null && !hasCenteredOnPickup) {
                hasCenteredOnPickup = true
                runCatching {
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(p, 15f), 600)
                }
            }
        }

        val activeTrip = trips.firstOrNull { it.status in ACTIVE_STATUSES }
        val distanceKm = if (pickup != null && destination != null) distanceKm(pickup!!, destination!!) else 0.0
        val fare = estimateFare(distanceKm)
        val visibleDrivers = onlineDrivers.filter { (it.vehicleInfo?.type ?: VehicleType.CAR) == vehicleType }

        fun submitTrip(name: String, phone: String, driver: UserProfile?) {
            val p = pickup
            val d = destination
            if (p == null || d == null) return
            screenModel.requestTrip(
                pickupAddress = pickupAddress.ifBlank { "%.5f, %.5f".format(p.latitude, p.longitude) },
                pickupLat = p.latitude,
                pickupLng = p.longitude,
                dropoffAddress = destinationAddress.ifBlank { "%.5f, %.5f".format(d.latitude, d.longitude) },
                dropoffLat = d.latitude,
                dropoffLng = d.longitude,
                fare = fare,
                passengerName = name,
                passengerPhone = phone,
                requestedVehicleType = driver?.vehicleInfo?.type ?: vehicleType,
                targetDriver = driver
            )
            destination = null
            destinationAddress = ""
            selectedDriver = null
            tappedDriverId = null
            scope.launch { snackbarHostState.showSnackbar("Trip requested. Looking for a driver…") }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Where to?") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showHistory = true }) {
                            Icon(Icons.Default.History, contentDescription = "My trips")
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    uiSettings = MapUiSettings(
                        myLocationButtonEnabled = false,
                        zoomControlsEnabled = false
                    ),
                    contentPadding = PaddingValues(bottom = 300.dp),
                    onMapClick = { latLng ->
                        // A tap on the map is the destination (only while not on a trip)
                        if (activeTrip == null) destination = latLng
                    }
                ) {
                    // While on a trip show its stored route; otherwise the one being planned
                    val shownPickup = activeTrip?.pickupLatLngOrNull() ?: pickup
                    val shownDestination = activeTrip?.dropoffLatLngOrNull() ?: destination

                    shownPickup?.let {
                        Marker(
                            state = rememberUpdatedMarkerState(position = it),
                            title = "Pickup",
                            snippet = if (activeTrip == null) "Your location" else activeTrip.pickupLocation,
                            icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
                        )
                    }
                    shownDestination?.let {
                        Marker(
                            state = rememberUpdatedMarkerState(position = it),
                            title = "Destination",
                            snippet = if (activeTrip == null) destinationAddress else activeTrip.dropoffLocation,
                            icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                        )
                    }
                    if (shownPickup != null && shownDestination != null) {
                        Polyline(
                            points = listOf(shownPickup, shownDestination),
                            color = routeColor,
                            width = 8f
                        )
                    }

                    if (activeTrip == null) {
                        onlineDrivers.forEach { driver ->
                            val loc = driver.currentLocation ?: return@forEach
                            if (loc.latitude == 0.0 && loc.longitude == 0.0) return@forEach
                            key(driver.uid) {
                                Marker(
                                    state = rememberUpdatedMarkerState(position = LatLng(loc.latitude, loc.longitude)),
                                    title = driver.fullName,
                                    icon = BitmapDescriptorFactory.defaultMarker(
                                        if (tappedDriverId == driver.uid) BitmapDescriptorFactory.HUE_GREEN
                                        else BitmapDescriptorFactory.HUE_ORANGE
                                    ),
                                    onClick = {
                                        tappedDriverId = driver.uid
                                        true // consume the click so the default info window doesn't pop up
                                    }
                                )
                            }
                        }
                        // Pick a specific driver by tapping his marker
//                        visibleDrivers.forEach { driver ->
//                            val loc = driver.currentLocation ?: return@forEach
//                            if (loc.latitude == 0.0 && loc.longitude == 0.0) return@forEach
//                            Marker(
//                                state = rememberUpdatedMarkerState(position = LatLng(loc.latitude, loc.longitude)),
//                                title = driver.fullName,
//                                snippet = "${driver.vehicleInfo?.color} ${driver.vehicleInfo?.make}",
//                                icon = BitmapDescriptorFactory.defaultMarker(
//                                    if (selectedDriver?.uid == driver.uid) BitmapDescriptorFactory.HUE_GREEN
//                                    else BitmapDescriptorFactory.HUE_ORANGE
//                                ),
//                                onClick = {
//                                    selectedDriver = driver
//                                    requestSheetDriver = driver
//                                    false
//                                }
//                            )
//                        }
                    } else {
                        // Follow the assigned driver live
                        onlineDrivers.firstOrNull { it.uid == activeTrip.driverId }?.currentLocation?.let { loc ->
                            Marker(
                                state = rememberUpdatedMarkerState(position = LatLng(loc.latitude, loc.longitude)),
                                title = activeTrip.driverName.ifBlank { "Your driver" },
                                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        SmallFloatingActionButton(onClick = { locateMe() }) {
                            Icon(Icons.Default.MyLocation, contentDescription = "My location")
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge.copy(
                            bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
                            bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp)
                        ),
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            if (activeTrip != null) {
                                Text("Your current trip", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                PassengerTripCard(
                                    trip = activeTrip,
                                    onCancel = { screenModel.cancelTrip(activeTrip.id) }
                                )
                            } else {
                                RequestPanel(
                                    pickupAddress = pickupAddress,
                                    hasPickup = pickup != null,
                                    destinationAddress = destinationAddress,
                                    hasDestination = destination != null,
                                    distanceKm = distanceKm,
                                    fare = fare,
                                    vehicleType = vehicleType,
                                    drivers = visibleDrivers,
                                    selectedDriver = selectedDriver,
                                    initialName = passengerInfo.name,
                                    initialPhone = passengerInfo.phone,
                                    onVehicleTypeChange = {
                                        vehicleType = it
                                        selectedDriver = null
                                    },
                                    onSelectDriver = { selectedDriver = it },
                                    onClearDestination = {
                                        destination = null
                                        destinationAddress = ""
                                    },
                                    onSubmit = { name, phone -> submitTrip(name, phone, selectedDriver) }
                                )
                            }
                        }
                    }
                }
            }

            // Tap a driver marker -> request that driver directly
            requestSheetDriver?.let { driver ->
                ModalBottomSheet(onDismissRequest = {
                    requestSheetDriver = null
                    selectedDriver = null
                }) {
                    DriverRequestSheet(
                        driver = driver,
                        hasPickup = pickup != null,
                        pickupAddress = pickupAddress,
                        hasDestination = destination != null,
                        destinationAddress = destinationAddress,
                        distanceKm = distanceKm,
                        fare = fare,
                        initialName = passengerInfo.name,
                        initialPhone = passengerInfo.phone,
                        onRequest = { name, phone ->
                            val p = pickup
                            val d = destination
                            if (p != null && d != null) {
                                screenModel.requestTrip(
                                    pickupAddress = pickupAddress.ifBlank { "%.5f, %.5f".format(p.latitude, p.longitude) },
                                    pickupLat = p.latitude,
                                    pickupLng = p.longitude,
                                    dropoffAddress = destinationAddress.ifBlank { "%.5f, %.5f".format(d.latitude, d.longitude) },
                                    dropoffLat = d.latitude,
                                    dropoffLng = d.longitude,
                                    fare = fare,
                                    passengerName = name,
                                    passengerPhone = phone,
                                    requestedVehicleType = vehicleType,
                                    targetDriver = driver
                                )
                                destination = null
                                destinationAddress = ""
                                selectedDriver = null
                                requestSheetDriver = null
                                scope.launch { snackbarHostState.showSnackbar("Trip requested with ${driver.fullName}") }
                            }
                        }
                    )
                }
            }

            if (showHistory) {
                ModalBottomSheet(onDismissRequest = { showHistory = false }) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                        Text("My Trips", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        when {
                            isLoading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                            trips.isEmpty() -> Text("No trips yet.", modifier = Modifier.padding(vertical = 24.dp))
                            else -> LazyColumn(
                                modifier = Modifier.heightIn(max = 480.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(trips, key = { it.id }) { trip ->
                                    PassengerTripCard(trip = trip, onCancel = { screenModel.cancelTrip(trip.id) })
                                }
                            }
                        }
                    }
                }
            }
            if (tappedDriver != null && activeTrip == null) {
                ModalBottomSheet(onDismissRequest = { tappedDriverId = null }) {
                    DriverRequestSheet(
                        driver = tappedDriver,
                        driverDistanceKm = pickup?.let { p ->
                            tappedDriver.currentLocation?.let { distanceKm(p, LatLng(it.latitude, it.longitude)) }
                        },
                        hasDestination = destination != null,
                        destinationAddress = destinationAddress,
                        tripDistanceKm = distanceKm,
                        fare = fare,
                        initialName = passengerInfo.name,
                        initialPhone = passengerInfo.phone,
                        onPickDestination = { tappedDriverId = null },
                        onRequest = { name, phone -> submitTrip(name, phone, tappedDriver) }
                    )
                }
            }
        }
    }

    @Composable
    private fun RequestPanel(
        pickupAddress: String,
        hasPickup: Boolean,
        destinationAddress: String,
        hasDestination: Boolean,
        distanceKm: Double,
        fare: Double,
        vehicleType: VehicleType,
        drivers: List<UserProfile>,
        selectedDriver: UserProfile?,
        initialName: String,
        initialPhone: String,
        onVehicleTypeChange: (VehicleType) -> Unit,
        onSelectDriver: (UserProfile?) -> Unit,
        onClearDestination: () -> Unit,
        onSubmit: (String, String) -> Unit
    ) {
        var name by remember(initialName) { mutableStateOf(initialName) }
        var phone by remember(initialPhone) { mutableStateOf(initialPhone) }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Pickup (automatic)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Pickup (your location)", style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (hasPickup) pickupAddress.ifBlank { "Resolving address…" } else "Locating you…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Destination (tap on map)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Destination", style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (hasDestination) destinationAddress.ifBlank { "Resolving address…" }
                        else "Tap anywhere on the map to set your destination",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (hasDestination) {
                    IconButton(onClick = onClearDestination) {
                        Icon(Icons.Default.Close, contentDescription = "Clear destination")
                    }
                }
            }

            if (hasDestination && hasPickup) {
                HorizontalDivider()

                // Ride type
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(VehicleType.entries.toList()) { type ->
                        FilterChip(
                            selected = type == vehicleType,
                            onClick = { onVehicleTypeChange(type) },
                            label = { Text(type.name) }
                        )
                    }
                }

                // Driver choice
                if (selectedDriver != null) {
                    InputChip(
                        selected = true,
                        onClick = { onSelectDriver(null) },
                        label = { Text("Driver: ${selectedDriver.fullName}") },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove driver", modifier = Modifier.size(16.dp)) }
                    )
                } else if (drivers.isNotEmpty()) {
                    Text(
                        "${drivers.size} ${vehicleType.name.lowercase()} driver(s) online. Tap a driver on the map or below, or let us match you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(drivers, key = { it.uid }) { driver ->
                            AssistChip(
                                onClick = { onSelectDriver(driver) },
                                label = { Text("${driver.fullName} · ${driver.vehicleInfo?.make ?: ""}") }
                            )
                        }
                    }
                } else {
                    Text(
                        "No ${vehicleType.name.lowercase()} drivers online right now. You can still request and the first available driver will accept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Distance: ${"%.1f".format(distanceKm)} km", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "$${"%.2f".format(fare)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Button(
                    onClick = { onSubmit(name.trim(), phone.trim()) },
                    enabled = name.isNotBlank() && distanceKm > 0.05,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (selectedDriver != null) "Request ${selectedDriver.fullName}" else "Request ride")
                }
            }
        }
    }

    @Composable
    private fun DriverRequestSheet(
        driver: UserProfile,
        driverDistanceKm: Double?,
        hasDestination: Boolean,
        destinationAddress: String,
        tripDistanceKm: Double,
        fare: Double,
        initialName: String,
        initialPhone: String,
        onPickDestination: () -> Unit,
        onRequest: (String, String) -> Unit
    ) {
        var name by remember(initialName) { mutableStateOf(initialName) }
        var phone by remember(initialPhone) { mutableStateOf(initialPhone) }
        val v = driver.vehicleInfo

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(driver.fullName, style = MaterialTheme.typography.titleLarge)

            if (v != null) {
                Text(
                    "${v.color} ${v.make} ${v.model}" +
                            if (v.licensePlate.isNotBlank()) " · ${v.licensePlate}" else "",
                    style = MaterialTheme.typography.bodyMedium
                )
                v.type?.let { AssistChip(onClick = {}, label = { Text(it.name) }) }
            }

            driverDistanceKm?.let {
                val eta = maxOf(1, Math.ceil(it / 30.0 * 60).toInt()) // assumes ~30 km/h
                Text(
                    "${"%.1f".format(it)} km away · ~$eta min",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            if (!hasDestination) {
                Text(
                    "Tap the map to choose your destination, then tap this driver again.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onPickDestination, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose destination")
                }
            } else {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text(destinationAddress.ifBlank { "Resolving address…" })
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it },
                        label = { Text("Phone") }, singleLine = true, modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Distance: ${"%.1f".format(tripDistanceKm)} km")
                    Text(
                        "$${"%.2f".format(fare)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Button(
                    onClick = { onRequest(name.trim(), phone.trim()) },
                    enabled = name.isNotBlank() && tripDistanceKm > 0.05,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Request ${driver.fullName}")
                }
            }
        }
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DriverRequestSheet(
        driver: UserProfile,
        hasPickup: Boolean,
        pickupAddress: String,
        hasDestination: Boolean,
        destinationAddress: String,
        distanceKm: Double,
        fare: Double,
        initialName: String,
        initialPhone: String,
        onRequest: (String, String) -> Unit
    ) {
        var name by remember(initialName) { mutableStateOf(initialName) }
        var phone by remember(initialPhone) { mutableStateOf(initialPhone) }

        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text("Request this driver", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            // Driver summary
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            driver.fullName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(driver.fullName, style = MaterialTheme.typography.titleSmall)
                    driver.vehicleInfo?.let { v ->
                        Text(
                            "${v.color} ${v.make} ${v.model} · ${v.licensePlate}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Route summary
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (hasPickup) pickupAddress.ifBlank { "Resolving address…" } else "Locating you…",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (hasDestination) destinationAddress.ifBlank { "Resolving address…" }
                    else "Tap the map to set a destination first",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasDestination) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error
                )
            }

            if (hasDestination && hasPickup) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Distance: ${"%.1f".format(distanceKm)} km", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "$${"%.2f".format(fare)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { onRequest(name.trim(), phone.trim()) },
                enabled = hasPickup && hasDestination && name.isNotBlank() && distanceKm > 0.05,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Request ${driver.fullName}")
            }
        }
    }

    @Composable
    private fun PassengerTripCard(trip: Trip, onCancel: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Trip #${trip.id.takeLast(6).uppercase()}", style = MaterialTheme.typography.titleSmall)
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text(trip.status, modifier = Modifier.padding(4.dp))
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("From: ${trip.pickupLocation}", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("To: ${trip.dropoffLocation}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Fare: $${"%.2f".format(trip.fare)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                if (trip.driverId.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Driver: ${trip.driverName} (${trip.driverPhone})", style = MaterialTheme.typography.bodySmall)
                    if (trip.vehicleDetails.isNotBlank()) {
                        Text(
                            "Vehicle: ${trip.vehicleDetails}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                if (trip.status == "REQUESTED" || trip.status == "ACCEPTED") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel trip")
                    }
                }
            }
        }
    }
}

private fun Trip.pickupLatLngOrNull(): LatLng? =
    if (pickupLat != 0.0 || pickupLng != 0.0) LatLng(pickupLat, pickupLng) else null

private fun Trip.dropoffLatLngOrNull(): LatLng? =
    if (dropoffLat != 0.0 || dropoffLng != 0.0) LatLng(dropoffLat, dropoffLng) else null

private fun distanceKm(a: LatLng, b: LatLng): Double {
    val result = FloatArray(1)
    Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
    return result[0] / 1000.0
}

private fun estimateFare(distanceKm: Double): Double =
    if (distanceKm <= 0.0) 0.0 else maxOf(MIN_FARE, BASE_FARE + distanceKm * PER_KM_FARE)

private suspend fun reverseGeocode(context: Context, point: LatLng): String =
    withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocation(point.latitude, point.longitude, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }.getOrNull() ?: "%.5f, %.5f".format(point.latitude, point.longitude)
    }