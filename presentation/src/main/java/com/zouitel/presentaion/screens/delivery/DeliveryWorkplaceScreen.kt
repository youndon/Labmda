package com.zouitel.presentaion.screens.delivery

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.Status

class DeliveryWorkplaceScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { DeliveryWorkplaceScreenModel() }
        val profile by screenModel.profile.collectAsState()
        val deliveryAccount by screenModel.deliveryAccount.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        var showAddEditDialog by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Delivery Workplace") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (deliveryAccount == null) {
                            IconButton(onClick = { showAddEditDialog = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "Register Delivery Service")
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (deliveryAccount == null) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No Delivery Service Registered", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Register your delivery profile to start accepting delivery tasks.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { showAddEditDialog = true }) {
                            Text("Apply Now")
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = { navigator?.push(DeliveryDashboardScreen()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Go to Active Orders Dashboard")
                        }

                        HorizontalDivider()

                        // User profile info summary
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(text = profile.fullName.ifBlank { "User Profile" }, style = MaterialTheme.typography.titleMedium)
                                Text(text = "Phone: ${profile.phoneNumber?.ifBlank { "N/A" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Text("Delivery Account Info", style = MaterialTheme.typography.titleMedium)

                        DeliveryServiceCard(
                            service = deliveryAccount!!,
                            onEdit = { showAddEditDialog = true },
                            onDelete = { screenModel.deleteDeliveryService() },
                            onOnlineChanged = { screenModel.updateOnlineStatus(it) }
                        )
                    }
                }

                if (showAddEditDialog) {
                    AddEditDeliveryServiceDialog(
                        service = deliveryAccount,
                        onDismiss = { showAddEditDialog = false },
                        onConfirm = { serviceType, vehicle, doc ->
                            screenModel.saveDeliveryService(
                                deliveryType = serviceType,
                                vehicleType = vehicle,
                                identityDocumentLink = doc,
                                keepStatus = deliveryAccount?.status
                            )
                            showAddEditDialog = false
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun DeliveryServiceCard(
        service: DeliveryAccountInfo,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
        onOnlineChanged: (Boolean) -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DirectionsBike, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = service.deliveryType.name, style = MaterialTheme.typography.titleMedium)
                    }

                    Badge(
                        containerColor = when (service.status) {
                            Status.APPROVED -> MaterialTheme.colorScheme.primaryContainer
                            Status.PENDING -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.errorContainer
                        }
                    ) {
                        Text(
                            text = service.status.name,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                HorizontalDivider()

                Text(text = "Vehicle Type: ${service.vehicleInfo?.type}", style = MaterialTheme.typography.bodyMedium)
                Text(text = "Document Link: ${service.identityDocumentLink.ifBlank { "N/A" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

                if (service.status == Status.APPROVED) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (service.isOnline) "Ready for deliveries" else "Offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (service.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Switch(checked = service.isOnline, onCheckedChange = onOnlineChanged)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Service")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Service", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AddEditDeliveryServiceDialog(
        service: DeliveryAccountInfo?,
        onDismiss: () -> Unit,
        onConfirm: (DeliveryType, String, String) -> Unit
    ) {
        var selectedServiceType by remember { mutableStateOf(service?.deliveryType ?: DeliveryType.COURIER) }
        var vehicleType by remember { mutableStateOf(service?.vehicleInfo?.type?.name ?: "") }
        var documentLink by remember { mutableStateOf(service?.identityDocumentLink ?: "") }
        var expandedDropdown by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (service == null) "Register Delivery Service" else "Edit Delivery Service") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExposedDropdownMenuBox(
                        expanded = expandedDropdown,
                        onExpandedChange = { expandedDropdown = !expandedDropdown }
                    ) {
                        OutlinedTextField(
                            value = selectedServiceType.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Delivery Service Type") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                            modifier = Modifier
                                .menuAnchor(
                                    type = ExposedDropdownMenuAnchorType.PrimaryEditable,
                                    enabled = true
                                )
                                .fillMaxWidth()
                        )

                        ExposedDropdownMenu(
                            expanded = expandedDropdown,
                            onDismissRequest = { expandedDropdown = false }
                        ) {
                            DeliveryType.entries.filter { it != DeliveryType.UNKNOWN && it != DeliveryType.UNDEFINED }.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.name) },
                                    onClick = {
                                        selectedServiceType = type
                                        expandedDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = vehicleType,
                        onValueChange = { vehicleType = it },
                        label = { Text("Vehicle Type (e.g., Bike, Car)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = documentLink,
                        onValueChange = { documentLink = it },
                        label = { Text("ID / Document Link") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConfirm(selectedServiceType, vehicleType, documentLink)
                    },
                    enabled = vehicleType.isNotBlank()
                ) {
                    Text(if (service == null) "Submit Application" else "Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}