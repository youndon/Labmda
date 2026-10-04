package com.zouitel.presentaion.screens.ride

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.zouitel.presentaion.model.Trip
import com.zouitel.presentaion.screens.account.SettingsScreen
import com.zouitel.presentaion.screens.ride.driver.DriverEarningsScreen
import com.zouitel.presentaion.screens.ride.driver.DriverScreenModel
import com.zouitel.presentaion.screens.ride.utils.IncomingRideRequestBottomSheet
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class DriverScreen(
    private val currentDriverId: String = "driver_123"
) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val tripRepository: DriverScreenModel = koinInject()

        // --- Location Permission State ---
        var hasLocationPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            )
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        }

        LaunchedEffect(Unit) {
            if (!hasLocationPermission) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }

        // --- Online/Offline Availability State ---
        var isOnline by remember { mutableStateOf(false) }
        var incomingTrip by remember { mutableStateOf<Trip?>(null) }

        // Observe incoming "SEARCHING" trips when online
        LaunchedEffect(isOnline) {
            if (isOnline) {
                tripRepository.observeSearchingTrips().collect { availableTrips ->
                    if (incomingTrip == null && availableTrips.isNotEmpty()) {
                        incomingTrip = availableTrips.first()
                    }
                }
            } else {
                incomingTrip = null
            }
        }

        val driverPosition = remember { LatLng(36.7538, 3.0588) }
        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(driverPosition, 14f)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
                uiSettings = MapUiSettings(
                    myLocationButtonEnabled = true,
                    zoomControlsEnabled = false
                )
            )

            // Top Status Toggle Card
            OnlineStatusCard(
                isOnline = isOnline,
                onOnlineToggled = { isOnline = it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(16.dp)
            )

            // Unified Bottom Dashboard Panel
            DriverBottomPanel(
                isOnline = isOnline,
                onEarningsClick = { navigator.push(DriverEarningsScreen()) },
// Inside DriverBottomPanel in DriverScreen.kt
                onSettingsClick = { navigator.push(SettingsScreen()) },                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // --- Real-time Incoming Ride Sheet ---
            incomingTrip?.let { trip ->
                IncomingRideRequestBottomSheet(
                    trip = trip,
                    totalTimeoutSeconds = 15,
                    onAccept = { acceptedTrip ->
                        coroutineScope.launch {
                            val result = tripRepository.acceptTrip(acceptedTrip.id, currentDriverId)
                            if (result.isSuccess) {
                                incomingTrip = null
                                navigator.push(DriverActiveTripScreen(acceptedTrip.copy(driverId = currentDriverId)))
                            }
                        }
                    },
                    onDecline = { incomingTrip = null },
                    onDismissRequest = { incomingTrip = null }
                )
            }
        }

    }

    @Composable
    private fun OnlineStatusCard(
        isOnline: Boolean,
        onOnlineToggled: (Boolean) -> Unit,
        modifier: Modifier = Modifier
    ) {
        ElevatedCard(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(
                                color = if (isOnline) Color(0xFF4CAF50) else Color.Gray,
                                shape = CircleShape
                            )
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = if (isOnline) "You're Online" else "You're Offline",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isOnline) "Finding nearby passengers" else "Go online to receive trips",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }

                Switch(
                    checked = isOnline,
                    onCheckedChange = onOnlineToggled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF4CAF50),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color.LightGray
                    )
                )
            }
        }
    }

    @Composable
    private fun DriverBottomPanel(
        isOnline: Boolean,
        onEarningsClick: () -> Unit,
        onSettingsClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            shadowElevation = 24.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Drag Handle
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(5.dp)
                        .background(Color.LightGray.copy(alpha = 0.5f), CircleShape)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Profile & Actions Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Profile",
                                modifier = Modifier.padding(12.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Driver Profile", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = "Rating", tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("4.92", fontSize = 14.sp, color = Color.Gray)
                            }
                        }
                    }

                    // Settings Navigation
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Dashboard Metrics Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DriverStatItem(
                        label = "Earnings",
                        value = "$84.50",
                        icon = Icons.Default.AttachMoney,
                        onClick = onEarningsClick,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    DriverStatItem(
                        label = "Trips",
                        value = "6",
                        icon = Icons.Default.LocalTaxi,
                        onClick = { /* Navigate to Trip History */ },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    DriverStatItem(
                        label = "Acceptance",
                        value = "98%",
                        icon = Icons.Default.CheckCircle,
                        onClick = { },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Search Radar Indicator
                AnimatedVisibility(
                    visible = isOnline,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 }
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Scanning for ride requests...",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DriverStatItem(
        label: String,
        value: String,
        icon: ImageVector,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(text = label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

}