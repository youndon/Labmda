package com.zouitel.presentaion.screens.shops

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.LiveOrderTrackingScreen
import kotlinx.coroutines.launch
import android.content.Context
import android.location.Geocoder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class StoreDetailsScreen(val shop: Shop) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { StoreDetailsScreenModel(shop) }
        val products by screenModel.products.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        val cartItems = remember { mutableStateMapOf<String, ShopCartItem>() }
        var showOrderSheet by remember { mutableStateOf(false) }

        var deliveryAddress by remember { mutableStateOf("") }
        var paymentMethod by remember { mutableStateOf("Cash on Delivery") }
        var isSubmitting by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        val context = LocalContext.current
        val modelShopPoint by screenModel.shopLocation.collectAsState()

        var shopPoint by remember { mutableStateOf<LatLng?>(null) }
        var dropoff by remember { mutableStateOf<LatLng?>(null) }
        var showMapPicker by remember { mutableStateOf(false) }

// Store location: coordinates from the database, otherwise geocode its address
        LaunchedEffect(modelShopPoint) {
            shopPoint = modelShopPoint?.let { LatLng(it.lat, it.lng) }
                ?: forwardGeocode(context, shop.address)
        }

        val totalItemsCount = cartItems.values.sumOf { it.quantity }
        val subtotal = cartItems.values.sumOf { it.totalPrice }
        val courierFee = 3.00
        val grandTotal = if (totalItemsCount > 0) subtotal + courierFee else 0.0

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(shop.name) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            },
            bottomBar = {
                if (totalItemsCount > 0) {
                    Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "$totalItemsCount product(s)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "$${"%.2f".format(grandTotal)}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Button(onClick = { showOrderSheet = true }) {
                                Icon(Icons.Default.ReceiptLong, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("View Demand Card")
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                // Store Info Header
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = shop.name, style = MaterialTheme.typography.titleMedium)
                        Text(text = "Category: ${shop.category}", style = MaterialTheme.typography.bodySmall)
                        Text(text = "Address: ${shop.address}", style = MaterialTheme.typography.bodySmall)
                        if (shop.description.isNotBlank()) {
                            Text(text = shop.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Text(
                    text = "Store Products Catalog",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (products.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        Text("No items listed in this store currently.")
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(products, key = { it.id }) { product ->
                            val currentItem = cartItems[product.id]
                            val currentQty = currentItem?.quantity ?: 0

                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = product.name, style = MaterialTheme.typography.titleMedium)
                                        Text(text = product.description, style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            text = "$${"%.2f".format(product.price)}",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    if (currentQty == 0) {
                                        OutlinedButton(
                                            onClick = {
                                                cartItems[product.id] = ShopCartItem(
                                                    productId = product.id,
                                                    productName = product.name,
                                                    price = product.price,
                                                    quantity = 1
                                                )
                                            }
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Add")
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(onClick = {
                                                if (currentQty > 1) {
                                                    cartItems[product.id] = currentItem!!.copy(quantity = currentQty - 1)
                                                } else {
                                                    cartItems.remove(product.id)
                                                }
                                            }) {
                                                Icon(Icons.Default.Remove, contentDescription = "Decrease")
                                            }
                                            Text("$currentQty", style = MaterialTheme.typography.titleMedium)
                                            IconButton(onClick = {
                                                cartItems[product.id] = currentItem!!.copy(quantity = currentQty + 1)
                                            }) {
                                                Icon(Icons.Default.Add, contentDescription = "Increase")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Demand Card Bottom Sheet Popup
            if (showOrderSheet) {
                ModalBottomSheet(onDismissRequest = { if (!isSubmitting) showOrderSheet = false }) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Store Demand Card Summary", style = MaterialTheme.typography.titleLarge)
                        HorizontalDivider()

                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(cartItems.values.toList(), key = { it.productId }) { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = item.productName, style = MaterialTheme.typography.bodyLarge)
                                        Text(text = "$${"%.2f".format(item.price)} x ${item.quantity} = $${"%.2f".format(item.totalPrice)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    IconButton(onClick = { cartItems.remove(item.productId) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Pickup (store)", style = MaterialTheme.typography.labelMedium)
                                Text("${shop.name} · ${shop.address}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                showOrderSheet = false
                                showMapPicker = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (dropoff == null) "Choose drop-off on map" else "Change drop-off location")
                        }

                        if (dropoff != null) {
                            OutlinedTextField(
                                value = deliveryAddress,
                                onValueChange = { deliveryAddress = it },
                                label = { Text("Drop-off address (add details if needed)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        OutlinedTextField(
                            value = paymentMethod,
                            onValueChange = { paymentMethod = it },
                            label = { Text("Payment Method") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total Demand Price:", style = MaterialTheme.typography.titleMedium)
                            Text("$${"%.2f".format(grandTotal)}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        }

                        Button(
                            enabled = dropoff != null && deliveryAddress.isNotBlank() && cartItems.isNotEmpty() && !isSubmitting,
                            onClick = {
                                val drop = dropoff ?: return@Button
                                isSubmitting = true
                                scope.launch {
                                    screenModel.placeShopOrder(
                                        cartItems = cartItems.values.toList(),
                                        subtotal = subtotal,
                                        courierFee = courierFee,
                                        totalPrice = grandTotal,
                                        pickupLat = shopPoint?.latitude ?: 0.0,
                                        pickupLng = shopPoint?.longitude ?: 0.0,
                                        dropoffAddress = deliveryAddress,
                                        dropoffLat = drop.latitude,
                                        dropoffLng = drop.longitude,
                                        paymentMethod = paymentMethod,
                                        onSuccess = { orderId ->
                                            isSubmitting = false
                                            showOrderSheet = false
                                            cartItems.clear()
                                            dropoff = null
                                            deliveryAddress = ""
                                            navigator?.push(LiveOrderTrackingScreen(orderId))
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Icon(Icons.Default.Send, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Send Demand Card to Store Manager")
                            }
                        }
                    }
                }
            }

            if (showMapPicker) {
                DropoffMapPicker(
                    shopName = shop.name,
                    shopPoint = shopPoint,
                    initial = dropoff,
                    onConfirm = { point, address ->
                        dropoff = point
                        deliveryAddress = address
                        showMapPicker = false
                        showOrderSheet = true
                    },
                    onDismiss = {
                        showMapPicker = false
                        showOrderSheet = true
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DropoffMapPicker(
        shopName: String,
        shopPoint: LatLng?,
        initial: LatLng?,
        onConfirm: (LatLng, String) -> Unit,
        onDismiss: () -> Unit
    ) {
        val context = LocalContext.current
        var picked by remember { mutableStateOf(initial) }
        var address by remember { mutableStateOf("") }

        // Start on the store (or the previous pick), never on the client's own position
        val start = initial ?: shopPoint
        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(start ?: LatLng(0.0, 0.0), if (start != null) 14f else 2f)
        }

        LaunchedEffect(picked) {
            picked?.let {
                address = ""
                address = reverseGeocode(context, it)
            }
        }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Choose drop-off location") },
                            navigationIcon = {
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                            }
                        )
                    },
                    bottomBar = {
                        Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    when {
                                        picked == null -> "Tap the map to place your drop-off pin"
                                        address.isBlank() -> "Resolving address…"
                                        else -> address
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Button(
                                    onClick = { picked?.let { onConfirm(it, address) } },
                                    enabled = picked != null && address.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Confirm drop-off")
                                }
                            }
                        }
                    }
                ) { padding ->
                    GoogleMap(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        cameraPositionState = cameraPositionState,
                        uiSettings = MapUiSettings(myLocationButtonEnabled = false, zoomControlsEnabled = false),
                        onMapClick = { picked = it }
                    ) {
                        shopPoint?.let {
                            Marker(
                                state = rememberUpdatedMarkerState(position = it),
                                title = shopName,
                                snippet = "Pickup",
                                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
                            )
                        }
                        picked?.let {
                            Marker(
                                state = rememberUpdatedMarkerState(position = it),
                                title = "Drop-off",
                                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                            )
                        }
                    }
                }
            }
        }
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
}