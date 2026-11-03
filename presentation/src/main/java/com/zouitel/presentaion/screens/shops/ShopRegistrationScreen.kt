package com.zouitel.presentaion.screens.shops

import android.content.Context
import android.location.Geocoder
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
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
import com.zouitel.presentaion.common.LocationPickerDialog
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

        var storeLat by remember { mutableStateOf(existingShop?.latitude?.takeIf { it != 0.0 }) }
        var storeLng by remember { mutableStateOf(existingShop?.longitude?.takeIf { it != 0.0 }) }
        var showMapPicker by remember { mutableStateOf(false) }

        val imageUpload by screenModel.imageUpload.collectAsState()
        val isUploading = imageUpload is ImageUploadState.Uploading
        val isSaving by screenModel.isSaving.collectAsState()
        val saveError by screenModel.saveError.collectAsState()

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
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (existingShop == null) "Set up your shop" else "Shop information",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (existingShop == null) "Add accurate business details and pin your physical store location. Your application will be reviewed before the shop is listed." else "Keep your public shop details and pickup location up to date.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("BUSINESS DETAILS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }

                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Store name *") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Store description") },
                        minLines = 3,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Store category *") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Business phone number *") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("Physical store address *") },
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
                            Text(
                                if (storeLat != null && storeLng != null) "Update pinned store location" else "Pin store location on map",
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            if (storeLat != null && storeLng != null) "Location pinned and ready for submission." else "A map pin is required so the delivery pickup point can be identified.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (storeLat != null && storeLng != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    Text("STORE PHOTO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    ImagePicker(
                        imageUrl = imageUrl,
                        uploadState = imageUpload,
                        onImagePicked = { image, token -> screenModel.uploadShopImage(image, token) },
                        emptyLabel = "Add a store photo or logo",
                        contentDescription = "Store photo"
                    )
                }

                item {
                    saveError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
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
                                    latitude = storeLat ?: existingShop.latitude,
                                    longitude = storeLng ?: existingShop.longitude,
                                    onSuccess = { navigator?.pop() }
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
                                    latitude = storeLat ?: Double.NaN,
                                    longitude = storeLng ?: Double.NaN,
                                    onSuccess = { navigator?.pop() }
                                )
                            }
                        },
                        enabled = name.isNotBlank() && phone.isNotBlank() && address.isNotBlank() && storeLat != null && storeLng != null && !isUploading && !isSaving,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text(
                            when {
                                isUploading -> "Uploading photo…"
                                isSaving -> "Saving…"
                                existingShop == null -> "Submit Application"
                                else -> "Save Changes"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (showMapPicker) {
                LocationPickerDialog(
                    title = "Select Store Location",
                    confirmLabel = "Confirm location",
                    initial = if (storeLat != null && storeLng != null) LatLng(storeLat!!, storeLng!!) else null,
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