package com.zouitel.presentaion.screens.ride

import com.zouitel.presentaion.common.LabmdaModalBottomSheet
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.zouitel.presentaion.common.LiveOrderTrackingScreen
import com.zouitel.presentaion.screens.payment.rememberCustomerPaymentAvailability
import com.zouitel.presentaion.screens.payment.CustomerPaymentStatusCard
import com.zouitel.presentaion.common.Trip
import com.zouitel.presentaion.model.UserProfile
import com.zouitel.presentaion.model.VehicleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val ACTIVE_STATUSES = setOf("PENDING_PAYMENT", "REQUESTED", "ACCEPTED", "IN_PROGRESS")

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
        val requestError by screenModel.requestError.collectAsState()
        val rideFareQuote by screenModel.rideFareQuote.collectAsState()
        val ridePaymentAvailability = rememberCustomerPaymentAvailability("RIDE")
        val deliveryPaymentAvailability = rememberCustomerPaymentAvailability("DELIVERY")
        val availablePaymentMethods = (ridePaymentAvailability.methods + deliveryPaymentAvailability.methods).distinct()
        val paymentAvailabilityMessage = listOfNotNull(ridePaymentAvailability.error, deliveryPaymentAvailability.error).distinct().joinToString("\n")

        val cameraPositionState = rememberCameraPositionState()
        val snackbarHostState = remember { SnackbarHostState() }
        val routeColor = MaterialTheme.colorScheme.primary
        LaunchedEffect(requestError) { requestError?.let { snackbarHostState.showSnackbar(it) } }

        var pickup by remember { mutableStateOf<LatLng?>(null) }
        var destination by remember { mutableStateOf<LatLng?>(null) }
        var pickupAddress by remember { mutableStateOf("") }
        var destinationAddress by remember { mutableStateOf("") }
        var vehicleType by remember { mutableStateOf(VehicleType.CAR) }
        var paymentMethod by remember { mutableStateOf("CASH") }
        LaunchedEffect(availablePaymentMethods) {
            if (paymentMethod !in availablePaymentMethods) paymentMethod = availablePaymentMethods.firstOrNull() ?: ""
        }
        var selectedDriver by remember { mutableStateOf<UserProfile?>(null) }
        var requestSheetDriver by remember { mutableStateOf<UserProfile?>(null) }
        var showHistory by remember { mutableStateOf(false) }
        var hasCenteredOnPickup by remember { mutableStateOf(false) }

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
                        scope.launch { snackbarHostState.showSnackbar("Couldn't get your location. Is GPS on?") }
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

        LaunchedEffect(pickup) {
            pickup?.let { pickupAddress = reverseGeocode(context, it) }
        }
        LaunchedEffect(destination) {
            destination?.let { destinationAddress = reverseGeocode(context, it) }
        }

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

        LaunchedEffect(pickup, destination, pickupAddress, destinationAddress, paymentMethod, vehicleType) {
            val p = pickup
            val d = destination
            if (p != null && d != null && pickupAddress.isNotBlank() && destinationAddress.isNotBlank()) {
                screenModel.previewRideQuote(
                    pickupAddress = pickupAddress,
                    pickupLat = p.latitude,
                    pickupLng = p.longitude,
                    dropoffAddress = destinationAddress,
                    dropoffLat = d.latitude,
                    dropoffLng = d.longitude,
                    paymentMethod = paymentMethod,
                    rideType = vehicleType.name
                )
            } else {
                screenModel.clearRideQuote()
            }
        }

        val activeTrip = trips.firstOrNull { it.status in ACTIVE_STATUSES }
        val distanceKm = if (pickup != null && destination != null) distanceKm(pickup!!, destination!!) else 0.0
        val fare = rideFareQuote?.customerTotal ?: 0.0

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
                quoteId = rideFareQuote?.quoteId ?: return,
                passengerName = name,
                passengerPhone = phone,
                targetDriver = driver,
                paymentMethod = paymentMethod,
                onSuccess = {
                    destination = null
                    destinationAddress = ""
                    selectedDriver = null
                    tappedDriverId = null
                    scope.launch { snackbarHostState.showSnackbar(if (paymentMethod == "GCASH") "Ride created. GCash payment is pending BDO Checkout verification." else "Trip requested. Looking for a driver…") }
                }
            )
        }

        fun submitDelivery() {
            val p = pickup
            val d = destination
            if (p == null || d == null) return
            screenModel.requestDelivery(
                pickupAddress = pickupAddress.ifBlank { "%.5f, %.5f".format(p.latitude, p.longitude) },
                pickupLat = p.latitude,
                pickupLng = p.longitude,
                dropoffAddress = destinationAddress.ifBlank { "%.5f, %.5f".format(d.latitude, d.longitude) },
                dropoffLat = d.latitude,
                dropoffLng = d.longitude,
                paymentMethod = paymentMethod
            ) { orderId ->
                destination = null
                destinationAddress = ""
                navigator?.push(LiveOrderTrackingScreen(orderId))
                scope.launch { snackbarHostState.showSnackbar(if (paymentMethod == "GCASH") "Delivery created. GCash payment is pending BDO Checkout verification." else "Delivery booking $orderId created.") }
            }
        }

        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    uiSettings = MapUiSettings(
                        myLocationButtonEnabled = false,
                        zoomControlsEnabled = false
                    ),
                    properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
                    contentPadding = PaddingValues(bottom = if (activeTrip != null) 200.dp else 360.dp),
                    onMapClick = { latLng ->
                        if (activeTrip == null) destination = latLng
                    }
                ) {
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
                            val loc = driver.driverService?.currentLocation ?: return@forEach
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
                                        true
                                    }
                                )
                            }
                        }
                    } else {
                        onlineDrivers.firstOrNull { it.uid == activeTrip.driverId }?.driverService?.currentLocation?.let { loc ->
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        SmallFloatingActionButton(
                            onClick = { showHistory = true },
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.History, contentDescription = "My trips")
                        }
                        SmallFloatingActionButton(
                            onClick = { locateMe() },
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = "My location")
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        tonalElevation = 8.dp,
                        shadowElevation = 16.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(32.dp)
                                    .height(4.dp)
                                    .background(
                                        MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(2.dp)
                                    )
                                    .align(Alignment.CenterHorizontally)
                            )
                            Spacer(Modifier.height(16.dp))

                            if (activeTrip != null) {
                                Text("Your current trip", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                Spacer(Modifier.height(12.dp))
                                PassengerTripCard(
                                    trip = activeTrip,
                                    driver = onlineDrivers.firstOrNull { it.uid == activeTrip.driverId },
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
                                    selectedDriver = selectedDriver,
                                    initialName = passengerInfo.name,
                                    initialPhone = passengerInfo.phone,
                                    paymentMethod = paymentMethod,
                                    paymentMethods = availablePaymentMethods,
                                    paymentAvailabilityMessage = paymentAvailabilityMessage,
                                    rideEnabled = ridePaymentAvailability.serviceEnabled && paymentMethod in ridePaymentAvailability.methods,
                                    deliveryEnabled = deliveryPaymentAvailability.serviceEnabled && paymentMethod in deliveryPaymentAvailability.methods,
                                    onPaymentMethodChange = { paymentMethod = it },
                                    onSubmitDelivery = { submitDelivery() },
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

            requestSheetDriver?.let { driver ->
                LabmdaModalBottomSheet(onDismissRequest = {
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
                        canRequestRide = ridePaymentAvailability.serviceEnabled && paymentMethod in ridePaymentAvailability.methods,
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
                                    quoteId = rideFareQuote?.quoteId ?: return@DriverRequestSheet,
                                    passengerName = name,
                                    passengerPhone = phone,
                                    targetDriver = driver,
                                    paymentMethod = paymentMethod
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
                LabmdaModalBottomSheet(onDismissRequest = { showHistory = false }) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("My Trips History", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        when {
                            isLoading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                            trips.isEmpty() -> Text("No previous trips found.", modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                LabmdaModalBottomSheet(onDismissRequest = { tappedDriverId = null }) {
                    DriverRequestSheet(
                        driver = tappedDriver!!,
                        driverDistanceKm = pickup?.let { p ->
                            tappedDriver!!.driverService?.currentLocation?.let { distanceKm(p, LatLng(it.latitude, it.longitude)) }
                        },
                        hasDestination = destination != null,
                        destinationAddress = destinationAddress,
                        tripDistanceKm = distanceKm,
                        fare = fare,
                        canRequestRide = ridePaymentAvailability.serviceEnabled && paymentMethod in ridePaymentAvailability.methods,
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
        selectedDriver: UserProfile?,
        initialName: String,
        initialPhone: String,
        paymentMethod: String,
        paymentMethods: List<String>,
        paymentAvailabilityMessage: String,
        rideEnabled: Boolean,
        deliveryEnabled: Boolean,
        onPaymentMethodChange: (String) -> Unit,
        onSubmitDelivery: () -> Unit,
        onVehicleTypeChange: (VehicleType) -> Unit,
        onSelectDriver: (UserProfile?) -> Unit,
        onClearDestination: () -> Unit,
        onSubmit: (String, String) -> Unit
    ) {
        var name by remember(initialName) { mutableStateOf(initialName) }
        var phone by remember(initialPhone) { mutableStateOf(initialPhone) }

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Pickup location row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Pickup location", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (hasPickup) pickupAddress.ifBlank { "Resolving address…" } else "Locating you…",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Destination row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Destination", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (hasDestination) destinationAddress.ifBlank { "Resolving address…" }
                        else "Tap anywhere on map to set destination",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (hasDestination) {
                    IconButton(onClick = onClearDestination) {
                        Icon(Icons.Default.Close, contentDescription = "Clear destination")
                    }
                }
            }

            if (hasDestination && hasPickup) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(VehicleType.entries.toList()) { type ->
                        FilterChip(
                            selected = type == vehicleType,
                            onClick = { onVehicleTypeChange(type) },
                            label = { Text(type.name) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                if (selectedDriver != null) {
                    InputChip(
                        selected = true,
                        onClick = { onSelectDriver(null) },
                        label = { Text("Driver: ${selectedDriver.fullName}") },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove driver", modifier = Modifier.size(16.dp)) }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Distance: ${"%.1f".format(distanceKm)} km", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (fare > 0.0) {
                        Text(
                            "₱${"%.2f".format(fare)}",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text("Getting server fare quote…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (paymentAvailabilityMessage.isNotBlank()) {
                    Text(paymentAvailabilityMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    paymentMethods.forEach { method ->
                        FilterChip(
                            selected = paymentMethod == method,
                            onClick = { onPaymentMethodChange(method) },
                            label = { Text(if (method == "GCASH") "GCash" else "Cash") }
                        )
                    }
                }
                if (paymentMethods.isEmpty() && paymentAvailabilityMessage.isBlank()) {
                    Text("Payment options are unavailable for this service.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSubmit(name.trim(), phone.trim()) },
                        enabled = name.isNotBlank() && distanceKm > 0.05 && rideEnabled && fare > 0.0,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        Text(if (selectedDriver != null) "Request Ride" else "Ride", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onSubmitDelivery,
                        enabled = distanceKm > 0.05 && deliveryEnabled,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        Text("Send Package", fontWeight = FontWeight.Bold)
                    }
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
        canRequestRide: Boolean,
        initialName: String,
        initialPhone: String,
        onPickDestination: () -> Unit,
        onRequest: (String, String) -> Unit
    ) {
        var name by remember(initialName) { mutableStateOf(initialName) }
        var phone by remember(initialPhone) { mutableStateOf(initialPhone) }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(driver.fullName, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))

            driverDistanceKm?.let {
                val eta = maxOf(1, Math.ceil(it / 30.0 * 60).toInt())
                Text(
                    "${"%.1f".format(it)} km away · ~$eta min",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (!hasDestination) {
                Text(
                    "Tap the map to choose your destination, then select this driver again.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onPickDestination,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Choose destination")
                }
            } else {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(destinationAddress.ifBlank { "Resolving address…" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Name") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it },
                        label = { Text("Phone") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Distance: ${"%.1f".format(tripDistanceKm)} km", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (fare > 0.0) {
                        Text(
                            "₱${"%.2f".format(fare)}",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text("Getting server fare quote…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Button(
                    onClick = { onRequest(name.trim(), phone.trim()) },
                    enabled = name.isNotBlank() && tripDistanceKm > 0.05 && canRequestRide && fare > 0.0,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("Request ${driver.fullName}", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

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
        canRequestRide: Boolean,
        onRequest: (String, String) -> Unit
    ) {
        var name by remember(initialName) { mutableStateOf(initialName) }
        var phone by remember(initialPhone) { mutableStateOf(initialPhone) }

        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Request this driver", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            driver.fullName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(driver.fullName, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (hasPickup) pickupAddress.ifBlank { "Resolving address…" } else "Locating you…",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (hasDestination) destinationAddress.ifBlank { "Resolving address…" }
                        else "Tap map to set destination first",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasDestination) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                    )
                }
            }

            if (hasDestination && hasPickup) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Distance: ${"%.1f".format(distanceKm)} km", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (fare > 0.0) {
                        Text(
                            "₱${"%.2f".format(fare)}",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text("Getting server fare quote…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
            }

            Button(
                onClick = { onRequest(name.trim(), phone.trim()) },
                enabled = hasPickup && hasDestination && name.isNotBlank() && distanceKm > 0.05 && canRequestRide && fare > 0.0,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Request ${driver.fullName}", fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun PassengerTripCard(
        trip: Trip,
        driver: UserProfile? = null,
        onCancel: () -> Unit
    ) {
        val statusSteps = listOf("REQUESTED", "ACCEPTED", "IN_PROGRESS", "COMPLETED")
        val currentStep = statusSteps.indexOf(trip.status).coerceAtLeast(0)
        val statusTitle = when (trip.status) {
            "PENDING_PAYMENT" -> "Payment required"
            "REQUESTED" -> "Finding your driver"
            "ACCEPTED" -> "Driver confirmed"
            "IN_PROGRESS" -> "Your ride is underway"
            "COMPLETED" -> "Ride completed"
            "CANCELLED" -> "Ride cancelled"
            else -> trip.status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        }
        val statusMessage = when (trip.status) {
            "PENDING_PAYMENT" -> "Complete the payment step to move your booking forward."
            "REQUESTED" -> "Your request is in the driver-matching queue. Driver details will appear here after a driver accepts."
            "ACCEPTED" -> "Your driver has accepted the ride. Get ready at the pickup location."
            "IN_PROGRESS" -> "Your driver has started the trip to your destination."
            "COMPLETED" -> "This trip has been marked as completed."
            "CANCELLED" -> "This trip is no longer active."
            else -> "Your trip status will update here."
        }
        val driverLocation = driver?.driverService?.currentLocation
        val hasLiveLocation = driverLocation != null &&
            (driverLocation.latitude != 0.0 || driverLocation.longitude != 0.0)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("TRIP #${trip.id.takeLast(6).uppercase()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(statusTitle, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    }
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                        Text(
                            trip.status.replace('_', ' '),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        statusMessage,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                if (trip.status != "PENDING_PAYMENT" && trip.status != "CANCELLED") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("Requested", "Accepted", "On trip", "Done").forEachIndexed { index, label ->
                            val reached = trip.status == "COMPLETED" || index <= currentStep
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().height(5.dp),
                                    color = if (reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(50)
                                ) {}
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (reached) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Pickup", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(trip.pickupLocation, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Destination", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(trip.dropoffLocation, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                if (trip.driverId.isNotBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                modifier = Modifier.size(44.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        trip.driverName.ifBlank { "D" }.take(1).uppercase(),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(trip.driverName.ifBlank { "Assigned driver" }, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                                if (trip.driverPhone.isNotBlank()) {
                                    Text(trip.driverPhone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (trip.vehicleDetails.isNotBlank()) {
                                    Text(trip.vehicleDetails, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    if (hasLiveLocation) "Live location available" else "Driver location is not currently available",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (hasLiveLocation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else if (trip.status == "REQUESTED") {
                    Text(
                        "No driver has accepted this ride yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Fare amount", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "₱${"%.2f".format(trip.fare)}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                CustomerPaymentStatusCard(
                    paymentMethod = trip.paymentMethod,
                    paymentStatus = trip.paymentStatus,
                    checkoutUrl = trip.checkoutUrl
                )

                if (trip.paymentStatus != "PAID" &&
                    (trip.status == "PENDING_PAYMENT" || trip.status == "REQUESTED" || trip.status == "ACCEPTED")) {
                    OutlinedButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel Trip")
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