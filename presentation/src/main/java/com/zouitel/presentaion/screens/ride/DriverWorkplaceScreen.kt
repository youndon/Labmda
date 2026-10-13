package com.zouitel.presentaion.screens.ride

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.common.UserMode
import com.zouitel.presentaion.model.VehicleType
import com.zouitel.presentaion.screens.account.AccountScreenModel

class DriverWorkplaceScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = koinScreenModel<DriverWorkplaceScreenModel>()

        val profile by screenModel.profile.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        val selectedRoleMode = remember(profile.driveMode) {
            profile.driveMode
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Ride Portal Mode") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Select Portal Mode",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SegmentedButton(
                        selected = selectedRoleMode == UserMode.PASSENGER,
                        onClick = {
                            screenModel.updateDriveMode(UserMode.PASSENGER)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = { Icon(Icons.Default.Person, contentDescription = null) }
                    ) {
                        Text("Passenger")
                    }

                    SegmentedButton(
                        selected = selectedRoleMode == UserMode.DRIVER,
                        onClick = {
                            screenModel.updateDriveMode(UserMode.DRIVER)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = { Icon(Icons.Default.DirectionsCar, contentDescription = null) }
                    ) {
                        Text("Driver")
                    }
                }

                HorizontalDivider()

                when (selectedRoleMode) {
                    UserMode.PASSENGER -> PassengerModeCard {
                        navigator?.push(PassengerScreen())
                    }
                    UserMode.DRIVER -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator()
                            } else {
                                when (profile.driverStatus) {
                                    Status.NONE -> DriverRegistrationForm(
                                        initialName = profile.fullName,
                                        initialPhone = profile.phoneNumber ?: "",
                                        initialVehicleType = profile.vehicleInfo?.type ?: VehicleType.CAR,
                                        onSubmit = { name, phone, type, make, model, color, plate ->
                                            screenModel.submitDriverApplication(name, phone, type, make, model, color, plate)
                                        }
                                    )
                                    Status.PENDING -> PendingDriverVerificationView()
                                    Status.REJECTED -> RejectedDriverView()
                                    Status.APPROVED -> ApprovedDriverView {
                                        navigator?.push(DriverDashboardScreen())
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    @Composable
    private fun PassengerModeCard(onExploreRides: () -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExploreRides),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsCar,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Passenger Mode Active", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Request rides, negotiate fares with drivers in real-time, and track your active drivers on the map.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onExploreRides,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Passenger Portal")
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DriverRegistrationForm(
        initialName: String,
        initialPhone: String,
        initialVehicleType: VehicleType,
        onSubmit: (String, String, VehicleType, String, String, String, String) -> Unit
    ) {
        var fullName by remember { mutableStateOf(initialName) }
        var phone by remember { mutableStateOf(initialPhone) }
        var selectedVehicleType by remember { mutableStateOf(initialVehicleType) }
        var make by remember { mutableStateOf("Toyota") }
        var model by remember { mutableStateOf("Corolla") }
        var color by remember { mutableStateOf("Silver") }
        var licensePlate by remember { mutableStateOf("") }

        var expanded by remember { mutableStateOf(false) }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Register as Driver", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Submit your vehicle information and type for admin review to get approved.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("Full Name") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone Number") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Vehicle Type Dropdown Selector
            item {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedVehicleType.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Vehicle Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor(
                            type = ExposedDropdownMenuAnchorType.PrimaryEditable,
                            enabled = true
                        ).fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        VehicleType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name) },
                                onClick = {
                                    selectedVehicleType = type
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = make,
                    onValueChange = { make = it },
                    label = { Text("Vehicle Make (e.g. Toyota)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Vehicle Model (e.g. Corolla)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = color,
                    onValueChange = { color = it },
                    label = { Text("Vehicle Color") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = licensePlate,
                    onValueChange = { licensePlate = it },
                    label = { Text("License Plate Number") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Button(
                    onClick = { onSubmit(fullName, phone, selectedVehicleType, make, model, color, licensePlate) },
                    enabled = fullName.isNotBlank() && phone.isNotBlank() && licensePlate.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Submit Application")
                }
            }
        }
    }

    @Composable
    private fun PendingDriverVerificationView() {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.HourglassEmpty,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("Driver Application Pending", style = MaterialTheme.typography.titleLarge)
            Text(
                "Your driver verification request has been submitted and is awaiting administrator approval.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun RejectedDriverView() {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text("Driver Application Declined", style = MaterialTheme.typography.titleLarge)
            Text(
                "Unfortunately, your driver application was not approved by the admin team.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun ApprovedDriverView(onOpenDashboard: () -> Unit) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DirectionsCar,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("Driver Active & Verified", style = MaterialTheme.typography.titleLarge)
            Text(
                "Your driver account is active. Open your driver dashboard to start accepting ride requests.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onOpenDashboard,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Driver Dashboard")
            }
        }
    }
}