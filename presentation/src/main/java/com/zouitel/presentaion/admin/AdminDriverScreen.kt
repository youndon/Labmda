package com.zouitel.presentaion.admin

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.model.UserProfile

class AdminDriverScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { AdminDriverModel() }
        val applications by screenModel.applications.collectAsState()
        var query by remember { mutableStateOf("") }
        val filtered = applications.filter {
            it.fullName.contains(query, ignoreCase = true) ||
                it.phoneNumber.orEmpty().contains(query, ignoreCase = true) ||
                it.driverService?.vehicleInfo?.licensePlate.orEmpty().contains(query, ignoreCase = true)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Driver applications", fontWeight = FontWeight.Bold) },
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
                    AdminApplicationHeader(
                        count = applications.size,
                        description = "Review driver identity and vehicle details before enabling ride services.",
                        query = query,
                        onQueryChange = { query = it },
                        searchPlaceholder = "Search name, phone or plate"
                    )
                }
                if (filtered.isEmpty()) {
                    item {
                        AdminApplicationEmptyState(
                            title = if (applications.isEmpty()) "No pending driver applications" else "No matching drivers",
                            description = if (applications.isEmpty()) "New driver applications will appear here." else "Try another name, phone number or plate."
                        )
                    }
                } else {
                    items(filtered, key = { it.uid }) { profile ->
                        DriverApplicationCard(
                            profile = profile,
                            onApprove = { screenModel.updateDriverStatus(profile.uid, Status.APPROVED) },
                            onReject = { screenModel.updateDriverStatus(profile.uid, Status.REJECTED) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun DriverApplicationCard(
        profile: UserProfile,
        onApprove: () -> Unit,
        onReject: () -> Unit
    ) {
        val vehicle = profile.driverService?.vehicleInfo
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(profile.fullName.ifBlank { "Driver applicant" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(profile.phoneNumber?.takeIf { it.isNotBlank() } ?: "Phone not provided", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AdminPendingLabel()
                }
                Text("Vehicle", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    listOfNotNull(vehicle?.color, vehicle?.make, vehicle?.model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Vehicle details not provided" },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Type: ${vehicle?.type?.name ?: "Not provided"}  •  Plate: ${vehicle?.licensePlate?.takeIf { it.isNotBlank() } ?: "Not provided"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Button(onClick = onApprove, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text("Approve")
                    }
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text("Reject")
                    }
                }
            }
        }
    }
}