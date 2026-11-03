package com.zouitel.presentaion.common

import android.content.Context
import android.location.Geocoder
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.CameraUpdateFactory
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

/**
 * Full-screen map: tap to drop a pin, or search an address. Never uses the user's own location.
 * [referencePoint] is an optional blue marker for context (e.g. the store when choosing a drop-off).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPickerDialog(
    title: String,
    confirmLabel: String,
    initial: LatLng?,
    referencePoint: LatLng? = null,
    referenceLabel: String = "",
    onConfirm: (LatLng, String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var picked by remember { mutableStateOf(initial) }
    var address by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    val start = initial ?: referencePoint
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(start ?: LatLng(12.8797, 121.7740), if (start != null) 14f else 5.5f)
    }

    LaunchedEffect(picked) {
        picked?.let {
            address = ""
            address = reverseGeocode(context, it)
        }
    }

    fun search() {
        if (query.isBlank() || isSearching) return
        scope.launch {
            isSearching = true
            searchError = null
            try {
                val result = forwardGeocode(context, query)
                if (result == null) {
                    searchError = "No matching address found. Try a nearby street or landmark."
                } else {
                    picked = result
                    runCatching { cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(result, 16f), 600) }
                }
            } finally {
                isSearching = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(title) },
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
                                    picked == null -> "Tap the map or search an address to place the pin"
                                    address.isBlank() -> "Resolving address…"
                                    else -> address
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Button(
                                onClick = { picked?.let { onConfirm(it, address) } },
                                enabled = picked != null && address.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(confirmLabel) }
                        }
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        uiSettings = MapUiSettings(myLocationButtonEnabled = false, zoomControlsEnabled = false),
                        onMapClick = { picked = it }
                    ) {
                        referencePoint?.let {
                            Marker(
                                state = rememberUpdatedMarkerState(position = it),
                                title = referenceLabel.ifBlank { "Reference" },
                                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
                            )
                        }
                        picked?.let {
                            Marker(
                                state = rememberUpdatedMarkerState(position = it),
                                title = "Selected location",
                                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search address") },
                        singleLine = true,
                        trailingIcon = {
                            if (isSearching) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = { search() }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search")
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                        searchError?.let { error ->
                            Text(
                                text = error,
                                modifier = Modifier.padding(horizontal = 8.dp),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

suspend fun reverseGeocode(context: Context, point: LatLng): String =
    withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocation(point.latitude, point.longitude, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }.getOrNull() ?: "%.5f, %.5f".format(point.latitude, point.longitude)
    }

suspend fun forwardGeocode(context: Context, address: String): LatLng? =
    withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocationName(address, 1)
                ?.firstOrNull()
                ?.let { LatLng(it.latitude, it.longitude) }
        }.getOrNull()
    }