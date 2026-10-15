package com.zouitel.presentaion.screens.delivery

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.model.Order
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.ui.platform.LocalContext
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
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DeliveryDashboardScreen : Screen {
    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel { DeliveryDashboardScreenModel() }

        val activeDeliveries by screenModel.activeDeliveries.collectAsState()
        val completedDeliveries by screenModel.completedDeliveries.collectAsState()
//        val orderPoints by screenModel.orderPoints.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()


        val cameraPositionState = rememberCameraPositionState()

        // --- Courier fromLocation ---
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

        // --- Order positions: stored coordinates first, geocoded address as fallback ---
//        val geocoded = remember { mutableStateMapOf<String, LatLng>() }
//        val triedGeocoding = remember { mutableSetOf<String>() }
//        LaunchedEffect(activeDeliveries, orderPoints) {
//            activeDeliveries.forEach { o ->
//                if (o.id in orderPoints || o.fromLocation.isBlank() || !triedGeocoding.add(o.id)) return@forEach
//                forwardGeocode(context, o.fromLocation)?.let { geocoded[o.id] = it }
//            }
//        }
//        fun pointOf(orderId: String): LatLng? =
//            orderPoints[orderId]?.let { LatLng(it.lat, it.lng) } ?: geocoded[orderId]

        fun pointOf(o: Order): LatLng? =
            if (o.dropoffLat != 0.0 || o.dropoffLng != 0.0) LatLng(o.dropoffLat, o.dropoffLng) else null
        fun pickupOf(o: Order): LatLng? =
            if (o.pickupLat != 0.0 || o.pickupLng != 0.0) LatLng(o.pickupLat, o.pickupLng) else null

        // If we never get the courier's fromLocation, frame the orders instead
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
        var selectedTab by remember { mutableIntStateOf(0) }
        val selectedOrder = activeDeliveries.firstOrNull { it.id == selectedOrderId }

        val sheetScaffoldState = rememberBottomSheetScaffoldState()
        val peekHeight = 110.dp
        val currentList = if (selectedTab == 0) activeDeliveries else completedDeliveries

        BottomSheetScaffold(
            scaffoldState = sheetScaffoldState,
            sheetPeekHeight = peekHeight,
            topBar = {
                TopAppBar(
                    title = { Text("Delivery Portal Dashboard") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            },
            sheetContent = {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = {
                            selectedTab = 0
                            scope.launch { sheetScaffoldState.bottomSheetState.expand() }
                        },
                        text = { Text("Available (${activeDeliveries.size})") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            scope.launch { sheetScaffoldState.bottomSheetState.expand() }
                        },
                        text = { Text("Delivered (${completedDeliveries.size})") }
                    )
                }

                when {
                    isLoading -> Box(
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    currentList.isEmpty() -> Box(
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (selectedTab == 0) "No orders currently out for delivery."
                            else "No completed deliveries."
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(currentList, key = { it.id }) { orderItem ->
                            DeliveryCardItem(
                                order = orderItem,
                                isCompleted = selectedTab == 1,
                                onAccept = { screenModel.claimCourierTask(orderItem) },
                                onReject = { screenModel.rejectCourierTask(orderItem) },
                                onMarkDelivered = { screenModel.markCourierOrderDelivered(orderItem) }
                            )
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
                    contentPadding = PaddingValues(bottom = peekHeight) // keep the Google logo above the sheet
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
                                        order.deliveryId.isBlank() -> BitmapDescriptorFactory.HUE_ORANGE // open
                                        else -> BitmapDescriptorFactory.HUE_GREEN                        // mine
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
                        "$mappedCount of ${activeDeliveries.size} orders on map",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                SmallFloatingActionButton(
                    onClick = { locateMe() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = peekHeight + 16.dp) // sits just above the collapsed sheet
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "My fromLocation")
                }

                // Detail popup when a marker is tapped
                if (selectedOrder != null) {
                    ModalBottomSheet(onDismissRequest = { selectedOrderId = null }) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val from = courier
                            val pickupPt = pickupOf(selectedOrder)
                            if (from != null && pickupPt != null) {
                                val km = distanceKm(from, pickupPt)
                                val eta = maxOf(1, Math.ceil(km / 25.0 * 60).toInt())
                                Text("${"%.1f".format(km)} km to pickup · ~$eta min", style = MaterialTheme.typography.labelLarge)
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
        onMarkDelivered: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.DirectionsBike,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = order.sourceName.ifBlank { "Store / Restaurant" },
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            "Earn $${"%.2f".format(order.deliveryFee)}",
                            modifier = Modifier.padding(4.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Order #${order.id.takeLast(6).uppercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.LocationOn, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text("Pickup", style = MaterialTheme.typography.labelLarge)
                        Text(order.pickupLocation.ifBlank { order.sourceName }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.LocationOn, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text("Drop-off", style = MaterialTheme.typography.labelLarge)
                        Text(order.dropoffLocation.ifBlank { "Address not specified" }, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.ShoppingBag,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Products (${order.items.sumOf { it.quantity }} items):",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (order.items.isEmpty()) {
                            Text(
                                "No item details available for this package.",
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
                                    Text(
                                        text = "• ${item.quantity}x ${item.name}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "$${"%.2f".format(item.totalPrice)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total Price to Collect:", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "$${"%.2f".format(order.totalPrice)}",
                        style = MaterialTheme.typography.titleMedium,
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
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Accept Task")
                            }

                            OutlinedButton(
                                onClick = onReject,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Decline")
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onMarkDelivered,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Mark Delivered")
                            }

                            OutlinedButton(
                                onClick = onReject,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Release Task")
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
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            "Delivered Successfully",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

private fun distanceKm(a: LatLng, b: LatLng): Double {
    val result = FloatArray(1)
    Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
    return result[0] / 1000.0
}

private suspend fun forwardGeocode(context: Context, address: String): LatLng? =
    withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocationName(address, 1)
                ?.firstOrNull()
                ?.let { LatLng(it.latitude, it.longitude) }
        }.getOrNull()
    }