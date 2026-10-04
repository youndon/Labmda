package com.zouitel.presentaion.screens.ride.utils

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zouitel.presentaion.model.Trip
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingRideRequestBottomSheet(
    trip: Trip,
    totalTimeoutSeconds: Int = 15,
    onAccept: (Trip) -> Unit,
    onDecline: (Trip) -> Unit,
    onDismissRequest: () -> Unit
) {
    var timeLeft by remember(trip.id) { mutableIntStateOf(totalTimeoutSeconds) }

    // Countdown timer loop
    LaunchedEffect(trip.id, timeLeft) {
        if (timeLeft > 0) {
            delay(1000L)
            timeLeft--
        } else {
            // Automatically decline when timer hits zero
            onDecline(trip)
        }
    }

    // Animate progress bar from 1.0f (full) to 0.0f (empty)
    val progressAnimated by animateFloatAsState(
        targetValue = timeLeft.toFloat() / totalTimeoutSeconds.toFloat(),
        animationSpec = tween(durationMillis = 1000),
        label = "timer_progress"
    )

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // --- Header: Service Badge & Countdown ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color(0xFF2196F3).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = trip.serviceType.uppercase(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2196F3)
                    )
                }

                // Timer Pill
                Surface(
                    color = if (timeLeft <= 5) Color(0xFFFFEBEE) else Color(0xFFE8F5E9),
                    shape = CircleShape
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${timeLeft}s",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (timeLeft <= 5) Color.Red else Color(0xFF2E7D32)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Fare & Distance ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(text = "Estimated Fare", fontSize = 12.sp, color = Color.Gray)
                    Text(
                        text = "$${trip.fareAmount}",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "2.4 km away", // Can be calculated dynamically via GeoPoint.distanceToMeters
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(text = "~ 5 mins pickup", fontSize = 12.sp, color = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // --- Pickup & Dropoff Route Card ---
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Pickup Row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(Color(0xFF4CAF50), shape = CircleShape)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(text = "Pickup Location", fontSize = 11.sp, color = Color.Gray)
                            Text(
                                text = "${trip.pickupLocation.latitude}, ${trip.pickupLocation.longitude}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Route Line Connector
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                            .width(2.dp)
                            .height(20.dp)
                            .background(Color.LightGray)
                    )

                    // Dropoff Row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(Color(0xFFF44336), shape = CircleShape)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(text = "Dropoff Location", fontSize = 11.sp, color = Color.Gray)
                            Text(
                                text = "${trip.dropoffLocation.latitude}, ${trip.dropoffLocation.longitude}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // --- Timer Depletion Bar ---
            LinearProgressIndicator(
                progress = { progressAnimated },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = if (timeLeft <= 5) Color.Red else Color(0xFF4CAF50),
                trackColor = Color.LightGray.copy(alpha = 0.3f),
            )

            Spacer(modifier = Modifier.height(20.dp))

            // --- Action Buttons ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Decline Button
                OutlinedButton(
                    onClick = { onDecline(trip) },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                ) {
                    Text("Decline", fontWeight = FontWeight.Bold)
                }

                // Accept Button
                Button(
                    onClick = { onAccept(trip) },
                    modifier = Modifier
                        .weight(2f)
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("Accept Ride", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}