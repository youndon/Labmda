package com.zouitel.presentaion.screens.delivery

import com.zouitel.presentaion.model.formatPhpPesos
import com.zouitel.presentaion.model.formatPhpCentavos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.model.Order
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeliveryDashboardScreen : Screen {
    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel { DeliveryDashboardScreenModel() }

        val activeDeliveries by screenModel.activeDeliveries.collectAsState()
        val completedDeliveries by screenModel.completedDeliveries.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        val trackingRoute by screenModel.trackingRoute.collectAsState()
        val routeLoading by screenModel.routeLoading.collectAsState()

        val cameraPositionState = rememberCameraPositionState()

        var courier by remember { mutableStateOf<LatLng?>(null) }
        var hasCentered by remember { mutableStateOf(false) }
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
                        val p = LatLng(loc.latitude, loc.longitude)
                        courier = p
                        screenModel.updateCourierLocation(p.latitude, p.longitude, loc.bearing)
                        scope.launch {
                            runCatching { cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(p, 14f), 600) }
                        }
                    }
                }
        }

        LaunchedEffect(hasLocationPermission) {
            if (hasLocationPermission) locateMe()
            else permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }

        DisposableEffect(hasLocationPermission, fusedClient) {
            if (!hasLocationPermission) return@DisposableEffect onDispose { }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
                .setMinUpdateIntervalMillis(3_000L)
                .build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    courier = LatLng(location.latitude, location.longitude)
                    screenModel.updateCourierLocation(location.latitude, location.longitude, location.bearing)
                }
            }
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            onDispose { fusedClient.removeLocationUpdates(callback) }
        }

        fun pointOf(o: Order): LatLng? =
            if (o.dropoffLat != 0.0 || o.dropoffLng != 0.0) LatLng(o.dropoffLat, o.dropoffLng) else null
        fun pickupOf(o: Order): LatLng? =
            if (o.pickupLat != 0.0 || o.pickupLng != 0.0) LatLng(o.pickupLat, o.pickupLng) else null

        val mappedCount = activeDeliveries.count { pointOf(it) != null }
        LaunchedEffect(mappedCount, courier) {
            if (hasCentered || courier != null || mappedCount == 0) return@LaunchedEffect
            val pts = activeDeliveries.mapNotNull { pointOf(it) }
            hasCentered = true
            runCatching {
                cameraPositionState.animate(
                    if (pts.size == 1) CameraUpdateFactory.newLatLngZoom(pts.first(), 14f)
                    else CameraUpdateFactory.newLatLngBounds(
                        LatLngBounds.Builder().apply { pts.forEach { include(it) } }.build(), 140
                    ),
                    800
                )
            }
        }
        var selectedOrderId by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(activeDeliveries) {
            if (selectedOrderId == null) {
                selectedOrderId = activeDeliveries.firstOrNull { it.deliveryId.isNotBlank() }?.id
            }
        }
        var selectedTab by remember { mutableIntStateOf(0) }
        val selectedOrder = activeDeliveries.firstOrNull { it.id == selectedOrderId }

        LaunchedEffect(selectedOrder?.id, selectedOrder?.deliveryStage) {
            val orderId = selectedOrder?.id ?: return@LaunchedEffect
            while (true) {
                screenModel.refreshCourierRoute(orderId)
                kotlinx.coroutines.delay(15_000L)
            }
        }
        LaunchedEffect(selectedOrderId) {
            if (selectedOrderId == null) screenModel.clearTrackingRoute()
        }

        val sheetScaffoldState = rememberBottomSheetScaffoldState()
        val peekHeight = 120.dp
        val currentList = if (selectedTab == 0) activeDeliveries else completedDeliveries

        BottomSheetScaffold(
            scaffoldState = sheetScaffoldState,
            sheetPeekHeight = peekHeight,
            sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Delivery Dashboard", style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            },
            sheetContent = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Modern Segmented Pill Tabs
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SegmentedButton(
                            selected = selectedTab == 0,
                            onClick = {
                                selectedTab = 0
                                scope.launch { sheetScaffoldState.bottomSheetState.expand() }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) {
                            Text("Available Tasks (${activeDeliveries.size})")
                        }
                        SegmentedButton(
                            selected = selectedTab == 1,
                            onClick = {
                                selectedTab = 1
                                scope.launch { sheetScaffoldState.bottomSheetState.expand() }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) {
                            Text("Completed (${completedDeliveries.size})")
                        }
                    }

                    when {
                        isLoading -> Box(
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }

                        currentList.isEmpty() -> Box(
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (selectedTab == 0) "No delivery tasks available." else "No completed deliveries yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(currentList, key = { it.id }) { orderItem ->
                                DeliveryCardItem(
                                    order = orderItem,
                                    isCompleted = selectedTab == 1,
                                    onAccept = { screenModel.claimCourierTask(orderItem) },
                                    onReject = { screenModel.rejectCourierTask(orderItem) },
                                    onMarkPickedUp = { screenModel.markDeliveryPickedUp(orderItem) },
                                    onMarkDelivered = { screenModel.markCourierOrderDelivered(orderItem) }
                                )
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
                    uiSettings = MapUiSettings(
                        myLocationButtonEnabled = false,
                        zoomControlsEnabled = false
                    ),
                    contentPadding = PaddingValues(bottom = peekHeight)
                ) {
                    activeDeliveries.forEach { order ->
                        val pos = pointOf(order) ?: return@forEach
                        key(order.id) {
                            Marker(
                                state = rememberUpdatedMarkerState(position = pos),
                                title = order.sourceName.ifBlank { "Delivery" },
                                icon = BitmapDescriptorFactory.defaultMarker(
                                    when {
                                        selectedOrderId == order.id -> BitmapDescriptorFactory.HUE_AZURE
                                        order.deliveryId.isBlank() -> BitmapDescriptorFactory.HUE_ORANGE
                                        else -> BitmapDescriptorFactory.HUE_GREEN
                                    }
                                ),
                                onClick = {
                                    selectedOrderId = order.id
                                    true
                                }
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 6.dp,
                    shadowElevation = 4.dp
                ) {
                    Text(
                        "$mappedCount of ${activeDeliveries.size} orders mapped",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                SmallFloatingActionButton(
                    onClick = { locateMe() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = peekHeight + 16.dp)
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "My location")
                }

                if (selectedOrder != null) {
                    ModalBottomSheet(onDismissRequest = { selectedOrderId = null }) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val from = courier
                            val routePoints = remember(trackingRoute?.encodedPolyline) {
                                decodeRoutePolyline(trackingRoute?.encodedPolyline.orEmpty())
                            }
                            val routeTarget = if (trackingRoute?.routeTo == "PICKUP") pickupOf(selectedOrder) else pointOf(selectedOrder)
                            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Column {
                                            Text(if (trackingRoute?.routeTo == "PICKUP") "Route to pickup" else "Route to recipient", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                            Text(
                                                if (routeLoading && trackingRoute == null) "Calculating road route…" else
                                                    trackingRoute?.let { "ETA ${formatRouteEta(it.duration)} · ${"%.1f".format(it.distanceMeters / 1000.0)} km" }
                                                        ?: "Waiting for route and live location",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        if (routeLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    }
                                    val routeCameraState = rememberCameraPositionState()
                            LaunchedEffect(from, routeTarget, routePoints) {
                                val boundsPoints = buildList {
                                    from?.let { add(it) }
                                    addAll(routePoints)
                                    routeTarget?.let { add(it) }
                                }.distinct()
                                if (boundsPoints.size >= 2) {
                                    runCatching {
                                        routeCameraState.animate(
                                            CameraUpdateFactory.newLatLngBounds(
                                                LatLngBounds.Builder().apply { boundsPoints.forEach { include(it) } }.build(), 72
                                            ), 500
                                        )
                                    }
                                } else boundsPoints.firstOrNull()?.let {
                                    routeCameraState.animate(CameraUpdateFactory.newLatLngZoom(it, 14f), 500)
                                }
                            }
                            GoogleMap(
                                        modifier = Modifier.fillMaxWidth().height(230.dp),
                                        cameraPositionState = routeCameraState,
                                        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false)
                                    ) {
                                        from?.let { Marker(state = rememberUpdatedMarkerState(it), title = "You", icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)) }
                                        routeTarget?.let { Marker(state = rememberUpdatedMarkerState(it), title = if (trackingRoute?.routeTo == "PICKUP") "Pickup" else "Recipient") }
                                        if (routePoints.size > 1) Polyline(points = routePoints, width = 9f, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        val phone = trackingRoute?.recipientPhone.orEmpty()
                                        OutlinedButton(
                                            onClick = { if (phone.isNotBlank()) uriHandler.openUri("tel:$phone") },
                                            enabled = phone.isNotBlank(),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Call recipient") }
                                        OutlinedButton(
                                            onClick = { if (phone.isNotBlank()) uriHandler.openUri("sms:$phone") },
                                            enabled = phone.isNotBlank(),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Message") }
                                    }
                                    Text(
                                        if (trackingRoute?.recipientName.orEmpty().isNotBlank()) "Recipient: ${trackingRoute?.recipientName}" else "Recipient contact",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        when (trackingRoute?.routeTo) {
                                            "PICKUP" -> "Progress: heading to pickup"
                                            "DROPOFF" -> "Progress: pickup confirmed · heading to recipient"
                                            else -> "Progress: waiting for live route"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            DeliveryCardItem(
                                order = selectedOrder,
                                isCompleted = false,
                                onAccept = {
                                    screenModel.claimCourierTask(selectedOrder)
                                    selectedOrderId = null
                                },
                                onReject = {
                                    screenModel.rejectCourierTask(selectedOrder)
                                    selectedOrderId = null
                                },
                                onMarkPickedUp = { screenModel.markDeliveryPickedUp(selectedOrder) },
                                onMarkDelivered = {
                                    screenModel.markCourierOrderDelivered(selectedOrder)
                                    selectedOrderId = null
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DeliveryCardItem(
        order: Order,
        isCompleted: Boolean,
        onAccept: () -> Unit,
        onReject: () -> Unit,
        onMarkPickedUp: () -> Unit,
        onMarkDelivered: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.DirectionsBike,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = order.sourceName.ifBlank { "Store / Restaurant" },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Order #${order.id.takeLast(6).uppercase()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "+₱${formatPhpPesos(order.deliveryFee)}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Pickup & Drop-off Flow Layout
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Pickup", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(order.pickupLocation.ifBlank { order.sourceName }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Drop-off", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(order.dropoffLocation.ifBlank { "Address not specified" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.ShoppingBag,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Package Contents (${order.items.sumOf { it.quantity }} items)",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        if (order.items.isEmpty()) {
                            Text(
                                "No item details available.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            order.items.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "${item.quantity}x ${item.name}", style = MaterialTheme.typography.bodySmall)
                                    Text(text = "₱${formatPhpPesos(item.totalPrice)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val prepaidOnline = order.paymentMethod.equals("GCASH", ignoreCase = true) &&
                    order.paymentStatus.equals("SUCCEEDED", ignoreCase = true)
                Text(
                    "Payment: ${order.paymentMethod.ifBlank { "CASH" }} · ${order.paymentStatus.replace('_', ' ')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (order.cashCollectionStatus == "COLLECTED") {
                    Text(
                        "Cash received: ₱${formatPhpCentavos(order.cashCollectedAmountCentavos)} · Merchant owed: ₱${formatPhpCentavos(order.cashMerchantOwedCentavos)} · Platform: ₱${formatPhpCentavos(order.cashPlatformOwedCentavos)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Your courier earnings: ₱${formatPhpCentavos(order.cashCollectorEarningsCentavos)} · Remittance outstanding: ₱${formatPhpCentavos(order.cashOutstandingRemittanceCentavos)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (prepaidOnline) "Order total (prepaid):" else "Cash to collect:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "₱${formatPhpPesos(order.totalPrice)}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (!isCompleted) {
                    if (order.deliveryId.isBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onAccept,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Accept Task")
                            }

                            OutlinedButton(
                                onClick = onReject,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Decline")
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (order.deliveryStage != "PICKED_UP") {
                                Button(
                                    onClick = onMarkPickedUp,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Confirm Pickup")
                                }
                            } else {
                                Button(
                                    onClick = onMarkDelivered,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Mark as Delivered")
                                }
                            }

                            if (order.deliveryStage != "PICKED_UP") {
                                OutlinedButton(
                                    onClick = onReject,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Release Task")
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "Delivered Successfully",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

private fun formatRouteEta(duration: String): String {
    val seconds = duration.removeSuffix("s").toDoubleOrNull() ?: return duration
    val minutes = ((seconds + 59.0) / 60.0).toInt().coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
}

private fun decodeRoutePolyline(encoded: String): List<LatLng> {
    if (encoded.isBlank()) return emptyList()
    val points = ArrayList<LatLng>()
    var index = 0
    var latitude = 0
    var longitude = 0
    while (index < encoded.length) {
        var result = 0
        var shift = 0
        var value: Int
        do {
            value = encoded[index++].code - 63
            result = result or ((value and 0x1f) shl shift)
            shift += 5
        } while (value >= 0x20 && index < encoded.length)
        latitude += if ((result and 1) != 0) (result shr 1).inv() else result shr 1
        result = 0
        shift = 0
        do {
            value = encoded[index++].code - 63
            result = result or ((value and 0x1f) shl shift)
            shift += 5
        } while (value >= 0x20 && index < encoded.length)
        longitude += if ((result and 1) != 0) (result shr 1).inv() else result shr 1
        points += LatLng(latitude / 1e5, longitude / 1e5)
    }
    return points
}

private fun distanceKm(a: LatLng, b: LatLng): Double {
    val result = FloatArray(1)
    Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
    return result[0] / 1000.0
}