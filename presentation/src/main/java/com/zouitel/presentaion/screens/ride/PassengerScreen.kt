package com.zouitel.presentaion.screens.ride

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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.koin.koinScreenModel
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
import com.zouitel.presentaion.common.Trip
import com.zouitel.presentaion.model.UserProfile
import com.zouitel.presentaion.model.VehicleType
import com.zouitel.presentaion.payments.PayButton
import com.zouitel.presentaion.payments.PaymentEventEffect
import com.zouitel.presentaion.payments.PaymentMethodSelector
import com.zouitel.presentaion.payments.PaymentRepository
import com.zouitel.presentaion.payments.PaymentResultSheet
import com.zouitel.presentaion.payments.PaymentScreenModel
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val BASE_FARE = 2.0
private const val PER_KM_FARE = 1.2
private const val MIN_FARE = 4.0

private val ACTIVE_STATUSES = setOf("AWAITING_PAYMENT", "REQUESTED", "ACCEPTED", "IN_PROGRESS")

class PassengerScreen(private val openHistoryOnStart: Boolean = false) : Screen {

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val uriHandler = LocalUriHandler.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val paymentRepository = remember { GlobalContext.get().get<PaymentRepository>() }
        val screenModel = rememberScreenModel { PassengerScreenModel(paymentRepository) }
        val paymentScreenModel = koinScreenModel<PaymentScreenModel>()
        val paymentUiState by paymentScreenModel.uiState.collectAsState()

        val trips by screenModel.trips.collectAsState()
        val onlineDrivers by screenModel.onlineDrivers.collectAsState()
        val passengerInfo by screenModel.passengerInfo.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        val cameraPositionState = rememberCameraPositionState()
        val snackbarHostState = remember { SnackbarHostState() }
        PaymentEventEffect(paymentScreenModel, uriHandler)
        val routeColor = MaterialTheme.colorScheme.primary

        var pickup by remember { mutableStateOf<LatLng?>(null) }
        var destination by remember { mutableStateOf<LatLng?>(null) }
        var pickupAddress by remember { mutableStateOf("") }
        var destinationAddress by remember { mutableStateOf("") }
        var vehicleType by remember { mutableStateOf(VehicleType.CAR) }
        var selectedDriver by remember { mutableStateOf<UserProfile?>(null) }
        var requestSheetDriver by remember { mutableStateOf<UserProfile?>(null) }
        var showHistory by remember(openHistoryOnStart) { mutableStateOf(openHistoryOnStart) }
        var showCheckout by remember { mutableStateOf(false) }
        var checkoutName by remember { mutableStateOf("") }
        var checkoutPhone by remember { mutableStateOf("") }
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

        val activeTrip = trips.firstOrNull { it.status in ACTIVE_STATUSES }
        val distanceKm = if (pickup != null && destination != null) distanceKm(pickup!!, destination!!) else 0.0
        val fare = estimateFare(distanceKm)

        fun submitTrip(name: String, phone: String, driver: UserProfile?) {
            val p = pickup
            val d = destination
            if (p == null || d == null) return
            paymentScreenModel.checkoutStarted()
            screenModel.requestTrip(
                pickupAddress = pickupAddress.ifBlank { "%.5f, %.5f".format(p.latitude, p.longitude) },
                pickupLat = p.latitude,
                pickupLng = p.longitude,
                dropoffAddress = destinationAddress.ifBlank { "%.5f, %.5f".format(d.latitude, d.longitude) },
                dropoffLat = d.latitude,
                dropoffLng = d.longitude,
                passengerName = name,
                passengerPhone = phone,
                targetDriver = driver,
                paymentMethod = paymentUiState.selectedMethod.name,
                onSuccess = { result ->
                    paymentScreenModel.checkoutCreated(result)
                    destination = null
                    destinationAddress = ""
                    selectedDriver = null
                    tappedDriverId = null
                    requestSheetDriver = null
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "Ride requested. Server fare: ₱${"%.2f".format(result.amount)}"
                        )
                    }
                },
                onFailure = { message ->
                    paymentScreenModel.checkoutFailed(message)
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
            )
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
                                    isSubmitting = paymentUiState.isSubmitting,
                                    vehicleType = vehicleType,
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
                                    onSubmit = { name, phone ->
                                        checkoutName = name
                                        checkoutPhone = phone
                                        showCheckout = true
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (showCheckout && activeTrip == null) {
                ModalBottomSheet(onDismissRequest = { showCheckout = false }) {
                    CheckoutReviewSheet(
                        pickupAddress = pickupAddress,
                        destinationAddress = destinationAddress,
                        vehicleType = vehicleType,
                        distanceKm = distanceKm,
                        indicativeFare = fare,
                        selectedMethod = paymentUiState.selectedMethod,
                        onMethodSelected = paymentScreenModel::selectMethod,
                        isSubmitting = paymentUiState.isSubmitting,
                        onConfirm = {
                            showCheckout = false
                            submitTrip(checkoutName, checkoutPhone, selectedDriver)
                        }
                    )
                }
            }

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
                        isSubmitting = paymentUiState.isSubmitting,
                        initialName = passengerInfo.name,
                        initialPhone = passengerInfo.phone,
                        onRequest = { name, phone -> submitTrip(name, phone, driver) }
                    )
                }
            }

            if (showHistory) {
                ModalBottomSheet(onDismissRequest = { showHistory = false }) {
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
                ModalBottomSheet(onDismissRequest = { tappedDriverId = null }) {
                    DriverRequestSheet(
                        driver = tappedDriver!!,
                        driverDistanceKm = pickup?.let { p ->
                            tappedDriver!!.driverService?.currentLocation?.let { distanceKm(p, LatLng(it.latitude, it.longitude)) }
                        },
                        hasDestination = destination != null,
                        destinationAddress = destinationAddress,
                        tripDistanceKm = distanceKm,
                        fare = fare,
                        isSubmitting = paymentUiState.isSubmitting,
                        initialName = passengerInfo.name,
                        initialPhone = passengerInfo.phone,
                        onPickDestination = { tappedDriverId = null },
                        onRequest = { name, phone -> submitTrip(name, phone, tappedDriver) }
                    )
                }
            }
        }

        PaymentResultSheet(
            state = paymentUiState,
            onDismiss = {
                paymentScreenModel.dismissResult()
                showHistory = true
            },
            onOpenCheckout = { payment ->
                (payment.checkoutUrl ?: payment.webCheckoutUrl)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { url -> runCatching { uriHandler.openUri(url) } }
            }
        )
    }

    @Composable
    private fun RequestPanel(
        pickupAddress: String,
        hasPickup: Boolean,
        destinationAddress: String,
        hasDestination: Boolean,
        distanceKm: Double,
        fare: Double,
        isSubmitting: Boolean,
        vehicleType: VehicleType,
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

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Pickup location row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("PICKUP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
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
                    Text("DROP-OFF", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
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
                    Text("Approx. ${"%.1f".format(distanceKm)} km", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Preview ₱${"%.2f".format(fare)}",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                PayButton(
                    label = "Review ride",
                    enabled = name.isNotBlank() && distanceKm > 0.05,
                    isLoading = isSubmitting,
                    onClick = { onSubmit(name.trim(), phone.trim()) }
                )
            }
        }
    }

    @Composable
    private fun CheckoutReviewSheet(
        pickupAddress: String,
        destinationAddress: String,
        vehicleType: VehicleType,
        distanceKm: Double,
        indicativeFare: Double,
        selectedMethod: com.zouitel.presentaion.model.PaymentMethod,
        onMethodSelected: (com.zouitel.presentaion.model.PaymentMethod) -> Unit,
        isSubmitting: Boolean,
        onConfirm: () -> Unit
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Review your ride", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            Text("Check your route and payment method before requesting a driver.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("PICKUP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(pickupAddress.ifBlank { "Current location" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("DROP-OFF", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(destinationAddress.ifBlank { "Selected map destination" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ride type", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(vehicleType.name.replace('_', ' '), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Map distance (approx.)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text("${"%.1f".format(distanceKm)} km", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Indicative fare", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("Not the final amount", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("₱${"%.2f".format(indicativeFare)}", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text("Final fare is calculated securely by Labmda using the road route and current administrator pricing. The server-confirmed amount may differ from this map preview.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PaymentMethodSelector(selectedMethod = selectedMethod, onMethodSelected = onMethodSelected, enabled = !isSubmitting)
            PayButton(label = "Confirm and request ride", enabled = distanceKm > 0.05, isLoading = isSubmitting, onClick = onConfirm)
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
        isSubmitting: Boolean,
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
                    Text(
                        "Est. ₱${"%.2f".format(fare)}",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                PayButton(
                    label = "Request ${driver.fullName}",
                    enabled = name.isNotBlank() && tripDistanceKm > 0.05,
                    isLoading = isSubmitting,
                    onClick = { onRequest(name.trim(), phone.trim()) }
                )
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
        isSubmitting: Boolean,
        initialName: String,
        initialPhone: String,
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
                    Text(
                        "Est. ₱${"%.2f".format(fare)}",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
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

            PayButton(
                label = "Request ${driver.fullName}",
                enabled = hasPickup && hasDestination && name.isNotBlank() && distanceKm > 0.05,
                isLoading = isSubmitting,
                onClick = { onRequest(name.trim(), phone.trim()) }
            )
        }
    }

    @Composable
    private fun PassengerTripCard(trip: Trip, onCancel: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Trip #${trip.id.takeLast(6).uppercase()}", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            trip.status,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("From: ${trip.pickupLocation}", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text("To: ${trip.dropoffLocation}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Server fare", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "₱${"%.2f".format(trip.fare)}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    "Payment: ${trip.paymentMethod.ifBlank { "CASH" }} · ${trip.paymentStatus.replace('_', ' ')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (trip.driverId.isNotBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text("Driver: ${trip.driverName} (${trip.driverPhone})", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                    if (trip.vehicleDetails.isNotBlank()) {
                        Text(
                            "Vehicle: ${trip.vehicleDetails}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                if (trip.status == "REQUESTED" || trip.status == "ACCEPTED") {
                    Spacer(Modifier.height(4.dp))
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