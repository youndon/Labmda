package com.zouitel.presentaion.screens.delivery

import android.widget.Toast

import com.zouitel.presentaion.common.LabmdaAlertDialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.screens.finance.ProviderEarningsScreen
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.common.AppCard
import com.zouitel.presentaion.common.AppPrimaryButton
import com.zouitel.presentaion.common.AppOutlinedButton
import com.zouitel.presentaion.common.AppSectionTitle
import com.zouitel.presentaion.common.AppSpacing

class DeliveryWorkplaceScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { DeliveryWorkplaceScreenModel() }
        val profile by screenModel.profile.collectAsState()
        val deliveryAccount by screenModel.deliveryAccount.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        val onlineError by screenModel.onlineError.collectAsState()
        val context = LocalContext.current

        LaunchedEffect(onlineError) {
            if (!onlineError.isNullOrBlank()) Toast.makeText(context, onlineError, Toast.LENGTH_LONG).show()
        }

        var showAddEditDialog by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Delivery Workplace", fontWeight = FontWeight.Bold) },
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
                    .padding(AppSpacing.lg)
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
                        Spacer(modifier = Modifier.height(AppSpacing.lg))
                        AppSectionTitle(
                            title = "Set up your delivery profile",
                            subtitle = "Register your service to apply for delivery tasks and manage your earnings.",
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        Text(
                            "Register your delivery profile to start accepting delivery tasks and earning.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        AppPrimaryButton(
                            text = "Apply for Delivery Service",
                            onClick = { showAddEditDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.lg)
                    ) {
                        item {
                            val approved = deliveryAccount?.status == Status.APPROVED
                            if (approved) {
                                AppSectionTitle(
                                    title = "Delivery operations",
                                    subtitle = "Your service is approved. Manage delivery availability and earnings."
                                )
                                AppPrimaryButton(
                                    text = "Open Delivery Dashboard",
                                    onClick = { navigator?.push(DeliveryDashboardScreen()) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                AppOutlinedButton(
                                    text = "Earnings & Payouts",
                                    onClick = { navigator?.push(ProviderEarningsScreen()) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                val message = when (deliveryAccount?.status) {
                                    Status.PENDING -> "Your application is waiting for administrator review. Delivery tasks and earnings become available after approval."
                                    Status.REJECTED -> "Your application was not approved. Update your service details or contact support before applying again."
                                    else -> "Your delivery service is not active yet."
                                }
                                AppCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.extraLarge
                                ) {
                                    Column(Modifier.padding(AppSpacing.lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                                        AppSectionTitle(
                                            title = if (deliveryAccount?.status == Status.PENDING) "Application under review" else "Service not active",
                                            subtitle = message
                                        )
                                    }
                                }
                            }
                        }

                        // Courier Profile Summary Card
                        item {
                            AppCard(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.extraLarge
                            ) {
                                Column(modifier = Modifier.padding(AppSpacing.lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                                    Text(text = profile.fullName.ifBlank { "Courier Profile" }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                    Text(text = "Phone: ${profile.phoneNumber?.takeIf { it.isNotBlank() } ?: "Not provided"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        item {
                            AppSectionTitle(
                                title = "Delivery service details",
                                subtitle = "Review your submitted vehicle and verification information."
                            )
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
    private fun DeliveryServiceCard(
        service: DeliveryAccountInfo,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
        onOnlineChanged: (Boolean) -> Unit
    ) {
        AppCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
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
                            text = when (service.status) {
                                Status.APPROVED -> "APPROVED"
                                Status.PENDING -> "PENDING REVIEW"
                                Status.REJECTED -> "NOT APPROVED"
                                else -> service.status.name
                            },
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

        LabmdaAlertDialog(
            onDismissRequest = onDismiss,
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