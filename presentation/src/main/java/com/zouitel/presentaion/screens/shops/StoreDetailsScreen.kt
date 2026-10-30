package com.zouitel.presentaion.screens.shops

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import android.widget.Toast
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.LiveOrderTrackingScreen
import kotlinx.coroutines.launch
import android.content.Context
import android.location.Geocoder
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
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
        val uriHandler = LocalUriHandler.current
        val screenModel = rememberScreenModel { StoreDetailsScreenModel(shop) }
        val products by screenModel.products.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        val cartItems = remember { mutableStateMapOf<String, ShopCartItem>() }
        var showOrderSheet by remember { mutableStateOf(false) }

        var deliveryAddress by remember { mutableStateOf("") }
        var paymentMethod by remember { mutableStateOf("CASH") }
        var isSubmitting by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        val context = LocalContext.current
        val modelShopPoint by screenModel.shopLocation.collectAsState()

        var shopPoint by remember { mutableStateOf<LatLng?>(null) }
        var dropoff by remember { mutableStateOf<LatLng?>(null) }
        var showMapPicker by remember { mutableStateOf(false) }

        LaunchedEffect(modelShopPoint) {
            shopPoint = modelShopPoint?.let { LatLng(it.lat, it.lng) }
                ?: forwardGeocode(context, shop.address)
        }

        val totalItemsCount = cartItems.values.sumOf { it.quantity }
        val subtotal = cartItems.values.sumOf { it.totalPrice }

        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = {
                        Column {
                            Text(shop.name, fontWeight = FontWeight.Bold)
                            if (scrollBehavior.state.collapsedFraction < 0.5f) {
                                Text(
                                    text = "${shop.category} • ${shop.address}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            },
            bottomBar = {
                if (totalItemsCount > 0) {
                    Surface(
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "$totalItemsCount product(s) selected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "₱${"%.2f".format(subtotal)}",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Button(
                                onClick = { showOrderSheet = true },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.height(50.dp)
                            ) {
                                Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("View Demand Card", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                Text(
                    text = "Store Products Catalog",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary
                )

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (products.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().weight(1f).padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No items listed in this store currently.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(products, key = { it.id }) { product ->
                            val currentItem = cartItems[product.id]
                            val currentQty = currentItem?.quantity ?: 0

                            Card(
                                modifier = Modifier.fillMaxSize(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(0.dp)
                                ) {
                                    AsyncImage(
                                        model = product.imageUrl,
                                        contentDescription = product.name,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(140.dp),
                                        contentScale = ContentScale.Crop
                                    )

                                    Column(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = product.name,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            color = MaterialTheme.colorScheme.primary
                                        )

                                        Text(
                                            text = "₱${"%.2f".format(product.price)}",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    if (currentQty == 0) {
                                        Button(
                                            onClick = {
                                                cartItems[product.id] = ShopCartItem(product.id, product.name, product.price, 1)
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("Add to Card", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceEvenly
                                        ) {
                                            IconButton(onClick = { if (currentQty > 1) cartItems[product.id] = currentItem!!.copy(quantity = currentQty - 1) else cartItems.remove(product.id) }) {
                                                Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(20.dp))
                                            }
                                            Text("$currentQty", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                            IconButton(onClick = { cartItems[product.id] = currentItem!!.copy(quantity = currentQty + 1) }) {
                                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
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
                        Text("Store Demand Card Summary", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

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
                                        Text(text = item.productName, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                                        Text(text = "₱${"%.2f".format(item.price)} × ${item.quantity} = ₱${"%.2f".format(item.totalPrice)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { cartItems.remove(item.productId) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Pickup (Store)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${shop.name} · ${shop.address}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                showOrderSheet = false
                                showMapPicker = true
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (dropoff == null) "Choose drop-off on map" else "Change drop-off location", fontWeight = FontWeight.Bold)
                        }

                        if (dropoff != null) {
                            OutlinedTextField(
                                value = deliveryAddress,
                                onValueChange = { deliveryAddress = it },
                                label = { Text("Drop-off address details") },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Text("Payment Method", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = paymentMethod == "GCASH",
                                onClick = { paymentMethod = "GCASH" },
                                label = { Text("GCash") }
                            )
                            FilterChip(
                                selected = paymentMethod == "CASH",
                                onClick = { paymentMethod = "CASH" },
                                label = { Text("Cash on Delivery") }
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Item Subtotal", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text("₱${"%.2f".format(subtotal)}", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        }

                        Button(
                            enabled = dropoff != null && deliveryAddress.isNotBlank() && cartItems.isNotEmpty() && !isSubmitting,
                            onClick = {
                                val drop = dropoff ?: return@Button
                                isSubmitting = true
                                scope.launch {
                                    screenModel.placeShopOrder(
                                        cartItems = cartItems.values.toList(),
                                        pickupLat = shopPoint?.latitude ?: 0.0,
                                        pickupLng = shopPoint?.longitude ?: 0.0,
                                        dropoffAddress = deliveryAddress,
                                        dropoffLat = drop.latitude,
                                        dropoffLng = drop.longitude,
                                        paymentMethod = paymentMethod,
                                        onSuccess = { result ->
                                            isSubmitting = false
                                            showOrderSheet = false
                                            cartItems.clear()
                                            dropoff = null
                                            deliveryAddress = ""
                                            result.checkoutUrl?.takeIf { it.isNotBlank() }?.let { url ->
                                                val opened = runCatching { uriHandler.openUri(url) }.isSuccess
                                                if (!opened) {
                                                    result.webCheckoutUrl?.takeIf { it.isNotBlank() && it != url }?.let { fallback ->
                                                        runCatching { uriHandler.openUri(fallback) }
                                                    }
                                                }
                                            }
                                            navigator?.push(LiveOrderTrackingScreen(result.entityId))
                                        },
                                        onFailure = { message ->
                                            isSubmitting = false
                                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                        }
                                    )
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Send Demand Card", fontWeight = FontWeight.Bold)
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
                        CenterAlignedTopAppBar(
                            title = { Text("Choose drop-off location", fontWeight = FontWeight.Bold) },
                            navigationIcon = {
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                            }
                        )
                    },
                    bottomBar = {
                        Surface(
                            tonalElevation = 8.dp,
                            shadowElevation = 8.dp,
                            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    when {
                                        picked == null -> "Tap the map to place your drop-off pin"
                                        address.isBlank() -> "Resolving address…"
                                        else -> address
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Button(
                                    onClick = { picked?.let { onConfirm(it, address) } },
                                    enabled = picked != null && address.isNotBlank(),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().height(50.dp)
                                ) {
                                    Text("Confirm Drop-off", fontWeight = FontWeight.Bold)
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