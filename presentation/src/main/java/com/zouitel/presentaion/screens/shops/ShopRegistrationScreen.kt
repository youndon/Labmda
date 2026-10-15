package com.zouitel.presentaion.screens.shops

import android.content.Context
import android.location.Geocoder
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class ShopRegistrationScreen(val existingShop: Shop? = null) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { ShopWorkplaceScreenModel() }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var name by remember { mutableStateOf(existingShop?.name ?: "") }
        var description by remember { mutableStateOf(existingShop?.description ?: "") }
        var category by remember { mutableStateOf(existingShop?.category ?: "Grocery & Supermarket") }
        var phone by remember { mutableStateOf(existingShop?.phone ?: "") }
        var address by remember { mutableStateOf(existingShop?.address ?: "") }
        var imageUrl by remember { mutableStateOf(existingShop?.imageUrl ?: "") }
        var isOnline by remember { mutableStateOf(existingShop?.isOnline ?: false) }

        // Store coordinates state
        var storeLat by remember { mutableStateOf<Double?>(null) }
        var storeLng by remember { mutableStateOf<Double?>(null) }
        var showMapPicker by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (existingShop == null) "Register Store Business" else "Edit Store Details") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = if (existingShop == null) "Submit your store details for admin verification." else "Update your store information.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Store Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Store Description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Store Category") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Business Phone Number") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("Physical Store Address") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedButton(
                            onClick = { showMapPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Mark Store Location on Map")
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = imageUrl,
                        onValueChange = { imageUrl = it },
                        label = { Text("Store Image / Logo URL (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Button(
                        onClick = {
                            if (existingShop != null) {
                                screenModel.updateShopApplication(
                                    shopId = existingShop.id,
                                    name = name,
                                    description = description,
                                    category = category,
                                    phone = phone,
                                    address = address,
                                    imageUrl = imageUrl
                                )
                            } else {
                                screenModel.submitShopApplication(
                                    name = name,
                                    description = description,
                                    category = category,
                                    phone = phone,
                                    address = address,
                                    imageUrl = imageUrl,
                                    isOnline = isOnline
                                )
                            }
                            navigator?.pop()
                        },
                        enabled = name.isNotBlank() && phone.isNotBlank() && address.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (existingShop == null) "Submit Application" else "Save Changes")
                    }
                }
            }

            // Map Picker Dialog for Store Location
            if (showMapPicker) {
                StoreLocationMapPicker(
                    initialPosition = if (storeLat != null && storeLng != null) LatLng(storeLat!!, storeLng!!) else null,
                    onConfirm = { point, resolvedAddress ->
                        storeLat = point.latitude
                        storeLng = point.longitude
                        address = resolvedAddress
                        showMapPicker = false
                    },
                    onDismiss = { showMapPicker = false }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun StoreLocationMapPicker(
        initialPosition: LatLng?,
        onConfirm: (LatLng, String) -> Unit,
        onDismiss: () -> Unit
    ) {
        val context = LocalContext.current
        var pickedLocation by remember { mutableStateOf(initialPosition) }
        var resolvedAddress by remember { mutableStateOf("") }

        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(initialPosition ?: LatLng(28.0339, 1.6596), if (initialPosition != null) 15f else 5f)
        }

        LaunchedEffect(pickedLocation) {
            pickedLocation?.let {
                resolvedAddress = reverseGeocode(context, it)
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
                            title = { Text("Select Store Location") },
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
                                    text = when {
                                        pickedLocation == null -> "Tap on the map to pin your store location"
                                        resolvedAddress.isBlank() -> "Resolving address..."
                                        else -> resolvedAddress
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Button(
                                    onClick = { pickedLocation?.let { onConfirm(it, resolvedAddress) } },
                                    enabled = pickedLocation != null && resolvedAddress.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Confirm Location")
                                }
                            }
                        }
                    }
                ) { padding ->
                    GoogleMap(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        cameraPositionState = cameraPositionState,
                        uiSettings = MapUiSettings(zoomControlsEnabled = true, myLocationButtonEnabled = true),
                        onMapClick = { clickedPoint ->
                            pickedLocation = clickedPoint
                        }
                    ) {
                        pickedLocation?.let { point ->
                            Marker(
                                state = rememberUpdatedMarkerState(position = point),
                                title = "Store Location",
                                snippet = resolvedAddress,
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
}