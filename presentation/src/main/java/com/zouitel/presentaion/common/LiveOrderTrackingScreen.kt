package com.zouitel.presentaion.common

import com.zouitel.presentaion.model.formatPhpPesos
import com.zouitel.presentaion.model.formatPhpCentavos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.model.OrderStatus

data class LiveOrderTrackingScreen(val orderId: String) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val uriHandler = LocalUriHandler.current
        val screenModel = rememberScreenModel { LiveOrderTrackingScreenModel(orderId) }
        val order by screenModel.order.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        val courierLocation by screenModel.courierLocation.collectAsState()
        val trackingRoute by screenModel.trackingRoute.collectAsState()
        val routeLoading by screenModel.routeLoading.collectAsState()
        val cameraState = rememberCameraPositionState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Track Order") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (order == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Order not found or has been removed.")
                }
            } else {
                val currentOrder = order!!

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Live courier map and road route for food and shop orders.
                    if (currentOrder.deliveryId.isNotBlank() &&
                        currentOrder.status == OrderStatus.OUT_FOR_DELIVERY &&
                        currentOrder.dropoffLat != 0.0 && currentOrder.dropoffLng != 0.0
                    ) {
                        item {
                            val destination = LatLng(currentOrder.dropoffLat, currentOrder.dropoffLng)
                            val courier = courierLocation?.let { LatLng(it.latitude, it.longitude) }
                            val routePoints = remember(trackingRoute?.encodedPolyline) {
                                decodeTrackingPolyline(trackingRoute?.encodedPolyline.orEmpty())
                            }
                            val courierMarker = rememberUpdatedMarkerState(
                                position = courier ?: destination
                            )
                            LaunchedEffect(courier) {
                                if (courier != null) courierMarker.position = courier
                            }
                            LaunchedEffect(courier, routePoints, destination) {
                                val points = (routePoints + listOfNotNull(courier, destination)).distinct()
                                if (points.isNotEmpty()) {
                                    runCatching {
                                        val bounds = LatLngBounds.Builder()
                                        points.forEach(bounds::include)
                                        cameraState.animate(
                                            CameraUpdateFactory.newLatLngBounds(bounds.build(), 100),
                                            650
                                        )
                                    }
                                }
                            }

                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("Live delivery", style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                when {
                                                    courier == null -> "Waiting for courier location"
                                                    routeLoading -> "Updating road route…"
                                                    trackingRoute != null -> "Courier is on the way"
                                                    else -> "Courier location received"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (trackingRoute != null) {
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    formatTrackingEta(trackingRoute!!.duration),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    "%.1f km".format(trackingRoute!!.distanceMeters / 1000.0),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }

                                    Box(modifier = Modifier.fillMaxWidth().height(280.dp)) {
                                        GoogleMap(
                                            modifier = Modifier.fillMaxSize(),
                                            cameraPositionState = cameraState,
                                            properties = MapProperties(isBuildingEnabled = false),
                                            uiSettings = MapUiSettings(
                                                zoomControlsEnabled = false,
                                                myLocationButtonEnabled = false,
                                                mapToolbarEnabled = false
                                            )
                                        ) {
                                            if (routePoints.size >= 2) {
                                                Polyline(points = routePoints, color = MaterialTheme.colorScheme.primary, width = 10f)
                                            } else if (courier != null) {
                                                Polyline(
                                                    points = listOf(courier, destination),
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                                    width = 7f
                                                )
                                            }
                                            if (courier != null) {
                                                Marker(
                                                    state = courierMarker,
                                                    title = currentOrder.deliveryName.ifBlank { "Courier" },
                                                    snippet = "Live courier location",
                                                    rotation = courierLocation?.heading ?: 0f,
                                                    flat = true
                                                )
                                            }
                                            Marker(
                                                state = rememberUpdatedMarkerState(position = destination),
                                                title = "Your delivery address",
                                                snippet = currentOrder.dropoffLocation
                                            )
                                        }
                                    }

                                    Text(
                                        if (courier == null) "Waiting for the courier's GPS update."
                                        else "Delivery progress: Out for delivery · Your courier is travelling to your address.",
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (currentOrder.deliveryPhone.isNotBlank()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = { uriHandler.openUri("tel:${currentOrder.deliveryPhone}") },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Default.Phone, contentDescription = null)
                                                Spacer(Modifier.width(6.dp))
                                                Text("Call courier")
                                            }
                                            OutlinedButton(
                                                onClick = { uriHandler.openUri("sms:${currentOrder.deliveryPhone}") },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Message")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Pickup & drop-off
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column {
                                    Text("Pickup (store)", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = currentOrder.pickupLocation.ifBlank { currentOrder.sourceName },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                HorizontalDivider()
                                Column {
                                    Text("Drop-off (delivery address)", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = currentOrder.dropoffLocation.ifBlank { "Address not provided" },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Order #${currentOrder.id.takeLast(6).uppercase()}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "From: ${currentOrder.sourceName}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Payment: ${currentOrder.paymentMethod.ifBlank { "Cash on Delivery" }}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "Payment status: ${currentOrder.paymentStatus.replace('_', ' ')}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    // Status Timeline Stepper
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Order Status Progress",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                OrderStatusStepper(currentStatus = currentOrder.status)
                            }
                        }
                    }

                    // Delivery Information Section
                    if (currentOrder.status == OrderStatus.OUT_FOR_DELIVERY || currentOrder.status == OrderStatus.DELIVERED || currentOrder.deliveryName.isNotBlank()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "Delivery Assigned",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                        )
                                        Text(
                                            text = currentOrder.deliveryName.ifBlank { "Searching for nearby courier..." },
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        if (currentOrder.deliveryPhone.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Phone,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = currentOrder.deliveryPhone,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                            }
                                        }
                                    }
                                    Icon(
                                        imageVector = Icons.Default.DirectionsBike,
                                        contentDescription = "Delivery Icon",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Delivery Address Details
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Delivery Address", style = MaterialTheme.typography.titleSmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = currentOrder.dropoffLocation.ifBlank { "Address not provided" },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    // Itemized Order Summary
                    item {
                        Text("Ordered Items", style = MaterialTheme.typography.titleMedium)
                    }

                    items(currentOrder.items, key = { it.id }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${item.quantity}x ${item.name}", style = MaterialTheme.typography.bodyMedium)
                            Text("₱${formatPhpPesos(item.totalPrice)}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Delivery Fee", style = MaterialTheme.typography.bodyMedium)
                            Text("₱${formatPhpPesos(currentOrder.deliveryFee)}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Total", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "₱${formatPhpPesos(currentOrder.totalPrice)}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Allow Client Cancellation if Order is still in PLACED state
                    if (currentOrder.status == OrderStatus.PLACED) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { screenModel.cancelOrder() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Cancel Order")
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun OrderStatusStepper(currentStatus: OrderStatus) {
        val steps = listOf(
            OrderStatus.AWAITING_PAYMENT to "Awaiting Payment",
            OrderStatus.PLACED to "Placed",
            OrderStatus.ACCEPTED to "Confirmed",
            OrderStatus.PREPARING to "Kitchen",
            OrderStatus.OUT_FOR_DELIVERY to "Out for Delivery",
            OrderStatus.DELIVERED to "Delivered"
        )

        if (currentStatus == OrderStatus.CANCELLED) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "This order was cancelled.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            return
        }

        val currentStepIndex = steps.indexOfFirst { it.first == currentStatus }.let {
            if (it == -1) 0 else it
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            steps.forEachIndexed { index, (_, label) ->
                val isCompleted = index <= currentStepIndex
                val isCurrent = index == currentStepIndex

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isCompleted -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isCompleted) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = label,
                        style = if (isCurrent) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        color = if (isCompleted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

private fun decodeTrackingPolyline(encoded: String): List<LatLng> {
    if (encoded.isBlank()) return emptyList()
    val points = mutableListOf<LatLng>()
    var index = 0
    var lat = 0
    var lng = 0
    fun delta(): Int {
        var result = 0
        var shift = 0
        while (index < encoded.length) {
            val value = encoded[index++].code - 63
            result = result or ((value and 0x1f) shl shift)
            shift += 5
            if (value < 0x20) break
        }
        return if ((result and 1) != 0) (result shr 1).inv() else result shr 1
    }
    return runCatching {
        while (index < encoded.length) {
            lat += delta()
            lng += delta()
            points += LatLng(lat / 1e5, lng / 1e5)
        }
        points
    }.getOrDefault(emptyList())
}

private fun formatTrackingEta(duration: String): String {
    val seconds = duration.removeSuffix("s").toDoubleOrNull() ?: return "ETA unavailable"
    if (!seconds.isFinite() || seconds <= 0.0) return "ETA unavailable"
    val minutes = kotlin.math.ceil(seconds / 60.0).toInt().coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
}
