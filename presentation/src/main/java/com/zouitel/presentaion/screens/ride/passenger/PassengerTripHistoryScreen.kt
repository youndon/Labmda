package com.zouitel.presentaion.screens.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.zouitel.presentaion.model.Trip
import com.zouitel.presentaion.model.TripStatus
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.firestore
import dev.gitlive.firebase.firestore.where
import kotlinx.coroutines.flow.map

class PassengerTripHistoryScreen(
    private val passengerId: String = "current_passenger_id"
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        // Real-time observation of trip history for the current passenger from GitLive Firestore
        val tripsState by produceState<List<Trip>?>(initialValue = null, passengerId) {
            Firebase.firestore.collection("trips")
                .where("passengerId", passengerId)
                .snapshots
                .map { query ->
                    query.documents
                        .map { it.data<Trip>() }
                        .sortedByDescending { it.createdAt }
                }
                .collect { value = it }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Your Trips",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (val trips = tripsState) {
                    null -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    else -> {
                        if (trips.isEmpty()) {
                            EmptyHistoryView(modifier = Modifier.align(Alignment.Center))
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(trips, key = { it.id }) { trip ->
                                    TripHistoryItemCard(
                                        trip = trip,
                                        onClick = {
                                            // Action when clicking a historic trip (e.g. view detailed receipt)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TripHistoryItemCard(
        trip: Trip,
        onClick: () -> Unit
    ) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // --- Header: Service Type, Date & Status Badge ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.DirectionsCar,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = trip.serviceType.ifBlank { "Standard Ride" },
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = formatTimestamp(trip.createdAt),
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    TripStatusBadge(status = trip.status)
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(14.dp))

                // --- Locations Breakdown ---
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Pickup
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFF4CAF50), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = trip.pickupLocation.addressName.ifBlank { "Pickup Location" },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Dropoff
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFFE53935), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = trip.dropoffLocation.addressName.ifBlank { "Dropoff Location" },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // --- Bottom Row: Fare & Rating ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (trip.rating != null && trip.rating > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Rating",
                                tint = Color(0xFFFFB300),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${trip.rating}.0",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Text(
                        text = "$${"%.2f".format(trip.fareAmount)}",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    @Composable
    private fun TripStatusBadge(status: TripStatus) {
        val (bgColor, textColor, label) = when (status) {
            TripStatus.COMPLETED -> Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), "Completed")
            TripStatus.CANCELLED -> Triple(Color(0xFFFFEBEE), Color(0xFFC62828), "Cancelled")
            TripStatus.IN_PROGRESS -> Triple(Color(0xFFE3F2FD), Color(0xFF1565C0), "In Progress")
            TripStatus.ARRIVED -> Triple(Color(0xFFFFF8E1), Color(0xFFF57F17), "Arrived")
            TripStatus.ACCEPTED -> Triple(Color(0xFFEDE7F6), Color(0xFF512DA8), "Driver On Way")
            TripStatus.SEARCHING -> Triple(Color(0xFFF5F5F5), Color(0xFF616161), "Searching")
        }

        Surface(
            color = bgColor,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = label,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }

    @Composable
    private fun EmptyHistoryView(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "🚕", fontSize = 48.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No trips taken yet",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Your completed and past ride history will appear here.",
                fontSize = 13.sp,
                color = Color.Gray
            )
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0) return "Recent"
        // Simple fallback formatter for cross-platform usage
        return try {
            val date = java.util.Date(timestamp)
            val format = java.text.SimpleDateFormat("MMM dd, yyyy • HH:mm", java.util.Locale.getDefault())
            format.format(date)
        } catch (e: Exception) {
            "Past Trip"
        }
    }
}