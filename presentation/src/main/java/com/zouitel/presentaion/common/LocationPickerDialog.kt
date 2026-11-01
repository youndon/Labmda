package com.zouitel.presentaion.common

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
import kotlinx.coroutines.launch

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

    val start = initial ?: referencePoint
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(start ?: LatLng(0.0, 0.0), if (start != null) 14f else 2f)
    }

    LaunchedEffect(picked) {
        picked?.let {
            address = ""
            address = reverseGeocode(context, it)
        }
    }

    fun search() {
        if (query.isBlank()) return
        scope.launch {
            forwardGeocode(context, query)?.let {
                picked = it
                runCatching { cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(it, 16f), 600) }
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

                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search address") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { search() }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp)
                    )
                }
            }
        }
    }
}
