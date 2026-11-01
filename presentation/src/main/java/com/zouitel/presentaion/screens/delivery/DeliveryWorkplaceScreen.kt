package com.zouitel.presentaion.screens.delivery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.common.AppTheme
import androidx.compose.ui.graphics.Color

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
                CenterAlignedTopAppBar(
                    title = { Text("Delivery hub", fontWeight = FontWeight.Bold) },
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
                    .padding(horizontal = 16.dp, vertical = 12.dp)
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
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.DirectionsBike,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text("Start delivering with Labmda", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Create your courier profile to apply for delivery access, receive task assignments, and start earning.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { showAddEditDialog = true },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Create delivery profile")
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            DeliveryWorkplaceHero(
                                status = deliveryAccount!!.status,
                                isOnline = deliveryAccount!!.isOnline
                            )
                        }

                        item {
                            Button(
                                onClick = { navigator?.push(DeliveryDashboardScreen()) },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.DirectionsBike, contentDescription = null)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Open active delivery dashboard", fontWeight = FontWeight.Bold)
                            }
                        }

                        item {
                            CourierProfileCard(
                                name = profile.fullName.ifBlank { "Courier profile" },
                                phone = profile.phoneNumber?.ifBlank { "Phone number not added" } ?: "Phone number not added"
                            )
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Your delivery service",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    "Manage your vehicle details and availability.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        item {
                            DeliveryServiceCard(
                                service = deliveryAccount!!,
                                onEdit = { showAddEditDialog = true },
                                onDelete = { screenModel.deleteDeliveryService() },
                                onOnlineChanged = { screenModel.updateOnlineStatus(it) }
                            )
                        }
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
    private fun DeliveryWorkplaceHero(
        status: Status,
        isOnline: Boolean
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.extras.gradient, RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        "Your delivery workspace",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        "Keep your courier profile and availability up to date.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.88f)
                    )
                }
                Icon(
                    Icons.Default.DirectionsBike,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = Color.White.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        "Application: ${status.name.replace('_', ' ')}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                if (status == Status.APPROVED) {
                    Surface(
                        color = Color.White.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            if (isOnline) "Online" else "Offline",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun CourierProfileCard(
        name: String,
        phone: String
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text(phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.DirectionsBike, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = service.deliveryType.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    }

                    val (statusColor, containerColor) = when (service.status) {
                        Status.APPROVED -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
                        Status.PENDING -> MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.errorContainer
                    }

                    Surface(
                        color = containerColor,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = service.status.name,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = statusColor
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

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
                            text = if (service.isOnline) "Online & Ready for Deliveries" else "Offline",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (service.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Switch(checked = service.isOnline, onCheckedChange = onOnlineChanged)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Service", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
            shape = RoundedCornerShape(20.dp),
            title = { Text(if (service == null) "Register Delivery Service" else "Edit Delivery Service", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
                            shape = RoundedCornerShape(12.dp),
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
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = documentLink,
                        onValueChange = { documentLink = it },
                        label = { Text("ID / Document Link") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConfirm(selectedServiceType, vehicleType, documentLink)
                    },
                    enabled = vehicleType.isNotBlank(),
                    shape = RoundedCornerShape(12.dp)
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