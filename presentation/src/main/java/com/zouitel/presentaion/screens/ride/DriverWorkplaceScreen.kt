package com.zouitel.presentaion.screens.ride

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.model.VehicleType

class DriverWorkplaceScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = koinScreenModel<DriverWorkplaceScreenModel>()

        val profile by screenModel.profile.collectAsState()
        val driverAccount by screenModel.driverAccount.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        var showAddEditDialog by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Driver Workplace") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (driverAccount == null) {
                            IconButton(onClick = { showAddEditDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = "Register Driver Service")
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
                } else if (driverAccount == null) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No Driver Service Registered", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Register your vehicle profile to start accepting ride requests.",
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
                            onClick = { navigator?.push(DriverDashboardScreen()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Go to Driver Dashboard")
                        }

                        HorizontalDivider()

                        // User profile info summary
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(text = profile.fullName.ifBlank { "User Profile" }, style = MaterialTheme.typography.titleMedium)
                                Text(text = "Phone: ${profile.phoneNumber?.ifBlank { "N/A" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Text("Driver Account Info", style = MaterialTheme.typography.titleMedium)

                        DriverServiceCard(
                            account = driverAccount!!,
                            onEdit = { showAddEditDialog = true },
                            onDelete = { screenModel.deleteDriverService() },
                            onOnlineChanged = { screenModel.updateOnlineStatus(it) }
                        )
                    }
                }

                if (showAddEditDialog) {
                    AddEditDriverDialog(
                        account = driverAccount,
                        onDismiss = { showAddEditDialog = false },
                        onConfirm = { type, make, model, color, plate ->
                            screenModel.saveDriverService(
                                vehicleType = type,
                                make = make,
                                model = model,
                                color = color,
                                licensePlate = plate,
                                keepStatus = driverAccount?.status
                            )
                            showAddEditDialog = false
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun DriverServiceCard(
        account: DriverAccountInfo,
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
                        Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Ride Service", style = MaterialTheme.typography.titleMedium)
                    }

                    Badge(
                        containerColor = when (account.status) {
                            Status.APPROVED -> MaterialTheme.colorScheme.primaryContainer
                            Status.PENDING -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.errorContainer
                        }
                    ) {
                        Text(
                            text = account.status.name,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                HorizontalDivider()

                val v = account.vehicleInfo
                Text(text = "Vehicle: ${v?.color ?: ""} ${v?.make ?: ""} ${v?.model ?: ""} (${v?.type?.name ?: ""})", style = MaterialTheme.typography.bodyMedium)
                Text(text = "License Plate: ${v?.licensePlate ?: "N/A"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

                if (account.status == Status.APPROVED) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (account.isOnline) "Ready for rides" else "Offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (account.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Switch(checked = account.isOnline, onCheckedChange = onOnlineChanged)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Driver Service")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Driver Service", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AddEditDriverDialog(
        account: DriverAccountInfo?,
        onDismiss: () -> Unit,
        onConfirm: (VehicleType, String, String, String, String) -> Unit
    ) {
        var selectedVehicleType by remember { mutableStateOf(account?.vehicleInfo?.type ?: VehicleType.CAR) }
        var make by remember { mutableStateOf(account?.vehicleInfo?.make ?: "Toyota") }
        var model by remember { mutableStateOf(account?.vehicleInfo?.model ?: "Corolla") }
        var color by remember { mutableStateOf(account?.vehicleInfo?.color ?: "Silver") }
        var licensePlate by remember { mutableStateOf(account?.vehicleInfo?.licensePlate ?: "") }
        var expandedDropdown by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (account == null) "Register Driver Service" else "Edit Driver Service") },
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
                            value = selectedVehicleType.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Vehicle Type") },
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
                            VehicleType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.name) },
                                    onClick = {
                                        selectedVehicleType = type
                                        expandedDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = make,
                        onValueChange = { make = it },
                        label = { Text("Vehicle Make (e.g. Toyota)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Vehicle Model (e.g. Corolla)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = color,
                        onValueChange = { color = it },
                        label = { Text("Vehicle Color") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = licensePlate,
                        onValueChange = { licensePlate = it },
                        label = { Text("License Plate Number") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConfirm(selectedVehicleType, make, model, color, licensePlate)
                    },
                    enabled = licensePlate.isNotBlank() && make.isNotBlank() && model.isNotBlank()
                ) {
                    Text(if (account == null) "Submit Application" else "Save Changes")
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