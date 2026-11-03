package com.zouitel.presentaion.screens.ride

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.Trip
import com.zouitel.presentaion.screens.payment.CustomerPaymentStatusCard
import java.util.Locale

private val ACTIVE_RIDE_STATUSES = setOf("PENDING_PAYMENT", "REQUESTED", "ACCEPTED", "IN_PROGRESS")

class RideHistoryScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { PassengerScreenModel() }
        val trips by screenModel.trips.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        var selectedFilter by remember { mutableStateOf("ALL") }

        val completedCount = trips.count { it.status == "COMPLETED" }
        val activeCount = trips.count { it.status in ACTIVE_RIDE_STATUSES }
        val cancelledCount = trips.count { it.status == "CANCELLED" }
        val filteredTrips = trips.filter { trip ->
            when (selectedFilter) {
                "ACTIVE" -> trip.status in ACTIVE_RIDE_STATUSES
                "COMPLETED" -> trip.status == "COMPLETED"
                "CANCELLED" -> trip.status == "CANCELLED"
                else -> true
            }
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Ride history", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(Modifier.size(8.dp))
                                Text("Your ride activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                HistoryMetric("Total rides", trips.size)
                                HistoryMetric("Completed", completedCount)
                                HistoryMetric("Active", activeCount)
                                HistoryMetric("Cancelled", cancelledCount)
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ALL", "ACTIVE", "COMPLETED", "CANCELLED").forEach { filter ->
                            FilterChip(
                                selected = selectedFilter == filter,
                                onClick = { selectedFilter = filter },
                                label = {
                                    Text(when (filter) {
                                        "ALL" -> "All"
                                        "ACTIVE" -> "Active"
                                        "COMPLETED" -> "Completed"
                                        else -> "Cancelled"
                                    })
                                }
                            )
                        }
                    }
                }
                when {
                    isLoading -> item {
                        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    trips.isEmpty() -> item {
                        EmptyRideHistory("No rides yet", "Your ride bookings will appear here after you request your first trip.")
                    }
                    filteredTrips.isEmpty() -> item {
                        EmptyRideHistory("No matching rides", "There are no rides in the ${selectedFilter.lowercase()} category.")
                    }
                    else -> items(filteredTrips, key = { it.id }) { trip -> RideHistoryCard(trip) }
                }
            }
        }
    }
}

@Composable
private fun HistoryMetric(label: String, value: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun EmptyRideHistory(title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RideHistoryCard(trip: Trip) {
    val statusColor = when (trip.status) {
        "COMPLETED" -> MaterialTheme.colorScheme.primary
        "CANCELLED" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("TRIP #${trip.id.takeLast(6).uppercase()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("₱${String.format(Locale.US, "%.2f", trip.fare)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Surface(color = statusColor.copy(alpha = 0.12f), shape = RoundedCornerShape(9.dp)) {
                    Text(
                        trip.status.replace('_', ' '),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                RouteLine("Pickup", trip.pickupLocation, MaterialTheme.colorScheme.primary)
                RouteLine("Destination", trip.dropoffLocation, MaterialTheme.colorScheme.error)
            }
            if (trip.driverName.isNotBlank() || trip.vehicleDetails.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Driver", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        listOf(trip.driverName, trip.vehicleDetails).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            CustomerPaymentStatusCard(
                paymentMethod = trip.paymentMethod,
                paymentStatus = trip.paymentStatus,
                checkoutUrl = trip.checkoutUrl
            )
        }
    }
}

@Composable
private fun RouteLine(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Surface(modifier = Modifier.size(9.dp), shape = RoundedCornerShape(50), color = color) {}
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.ifBlank { "Location unavailable" }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
    }
}
