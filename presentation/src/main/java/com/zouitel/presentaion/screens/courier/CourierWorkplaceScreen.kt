package com.zouitel.presentaion.screens.courier

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.Status

class CourierWorkplaceScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { CourierWorkplaceScreenModel() }
        val profile by screenModel.profile.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Courier Workplace") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    when (profile.courierStatus) {
                        Status.NONE -> CourierApplicationForm(
                            onSubmit = { name, phone, vehicle, doc ->
                                screenModel.submitApplication(name, phone, vehicle, doc)
                            }
                        )
                        Status.PENDING -> PendingStatusView()
                        Status.REJECTED -> RejectedStatusView()
                        Status.APPROVED -> ApprovedWorkplaceView(navigator)
                    }
                }
            }
        }
    }

    @Composable
    private fun CourierApplicationForm(onSubmit: (String, String, String, String) -> Unit) {
        var name by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var vehicle by remember { mutableStateOf("") }
        var documents by remember { mutableStateOf("") }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Apply to be a Courier", style = MaterialTheme.typography.headlineSmall)
            Text("Please fill out your information and provide links to your ID and vehicle registration.", style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = vehicle, onValueChange = { vehicle = it }, label = { Text("Vehicle Type (e.g., Bike, Car)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = documents, onValueChange = { documents = it }, label = { Text("ID / Document Link") }, modifier = Modifier.fillMaxWidth())

            Button(
                onClick = { onSubmit(name, phone, vehicle, documents) },
                modifier = Modifier.fillMaxWidth(),
                enabled = name.isNotBlank() && phone.isNotBlank() && vehicle.isNotBlank() && documents.isNotBlank()
            ) {
                Text("Submit Application")
            }
        }
    }

    @Composable
    private fun PendingStatusView() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.HourglassEmpty, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Application Pending", style = MaterialTheme.typography.titleLarge)
            Text("An admin is reviewing your documents. Please check back later.", style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun RejectedStatusView() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Application Rejected", style = MaterialTheme.typography.titleLarge)
            Text("Unfortunately, your courier application was not approved.", style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun ApprovedWorkplaceView(navigator: cafe.adriel.voyager.navigator.Navigator?) {
        var isOnline by remember { mutableStateOf(true) }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DirectionsBike, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Courier Availability", style = MaterialTheme.typography.titleMedium)
                            Text(if (isOnline) "Ready for deliveries" else "Offline", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Switch(checked = isOnline, onCheckedChange = { isOnline = it })
                }
            }
            Button(
                onClick = { navigator?.push(CourierDashboardScreen()) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Go to Active Orders Dashboard")
            }
        }
    }
}