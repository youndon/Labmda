package com.zouitel.presentaion.screens.shops

import android.content.Context
import android.location.Geocoder
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.zouitel.presentaion.common.ImagePicker
import com.zouitel.presentaion.screens.food.ImageUploadState
import kotlinx.coroutines.Dispatchers
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

        var storeLat by remember { mutableStateOf(existingShop?.latitude) }
        var storeLng by remember { mutableStateOf(existingShop?.longitude) }
        var showMapPicker by remember { mutableStateOf(false) }

        val imageUpload by screenModel.imageUpload.collectAsState()
        val isUploading = imageUpload is ImageUploadState.Uploading

        LaunchedEffect(imageUpload) {
            val state = imageUpload
            if (state is ImageUploadState.Success) {
                imageUrl = state.url
                screenModel.resetImageUpload()
            }
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (existingShop == null) "Register Store Business" else "Edit Store Details", fontWeight = FontWeight.Bold) },
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
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
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Store Description") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Store Category") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Business Phone Number") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("Physical Store Address") },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedButton(
                            onClick = { showMapPicker = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Mark Store Location on Map", fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = if (storeLat != null && storeLng != null) {
                                "Pickup coordinates: %.6f, %.6f".format(storeLat, storeLng)
                            } else {
                                "Select the exact store location. It is required to calculate delivery fees."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (storeLat != null && storeLng != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }

                item {
                    ImagePicker(
                        imageUrl = imageUrl,
                        uploadState = imageUpload,
                        onImagePicked = { image -> screenModel.uploadShopImage(image) },
                        emptyLabel = "Add a store photo or logo",
                        contentDescription = "Store photo"
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
                                    imageUrl = imageUrl,
                                    latitude = storeLat,
                                    longitude = storeLng
                                )
                            } else {
                                screenModel.submitShopApplication(
                                    name = name,
                                    description = description,
                                    category = category,
                                    phone = phone,
                                    address = address,
                                    imageUrl = imageUrl,
                                    isOnline = isOnline,
                                    latitude = storeLat,
                                    longitude = storeLng
                                )
                            }
                            navigator?.pop()
                        },
                        enabled = name.isNotBlank() && phone.isNotBlank() && address.isNotBlank() &&
                            storeLat != null && storeLng != null && !isUploading,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text(
                            when {
                                isUploading -> "Uploading photo…"
                                existingShop == null -> "Submit Application"
                                else -> "Save Changes"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

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
            position = CameraPosition.fromLatLngZoom(initialPosition ?: LatLng(12.8797, 121.7740), if (initialPosition != null) 15f else 5f)
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
                        CenterAlignedTopAppBar(
                            title = { Text("Select Store Location", fontWeight = FontWeight.Bold) },
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
                                    text = when {
                                        pickedLocation == null -> "Tap on the map to pin your store location"
                                        resolvedAddress.isBlank() -> "Resolving address..."
                                        else -> resolvedAddress
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Button(
                                    onClick = { pickedLocation?.let { onConfirm(it, resolvedAddress) } },
                                    enabled = pickedLocation != null && resolvedAddress.isNotBlank(),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().height(50.dp)
                                ) {
                                    Text("Confirm Location", fontWeight = FontWeight.Bold)
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