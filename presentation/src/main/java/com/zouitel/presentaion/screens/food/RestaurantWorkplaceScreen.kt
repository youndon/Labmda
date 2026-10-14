package com.zouitel.presentaion.screens.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.common.UserMode

class RestaurantWorkplaceScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { RestaurantWorkplaceScreenModel() }
        val profile by screenModel.profile.collectAsState()
        val restaurantInfo by screenModel.restaurantInfo.collectAsState()
        val meals by screenModel.meals.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        var selectedRoleMode by remember(profile.foodMode) {
            mutableStateOf(profile.foodMode)
        }

        var isEditing by remember { mutableStateOf(false) }
        val showFab = selectedRoleMode != UserMode.CLIENT && profile.foodStatus == Status.APPROVED && !isEditing

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (isEditing) "Edit Restaurant Info" else "Food Portal Mode") },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isEditing) isEditing = false else navigator?.pop()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (selectedRoleMode != UserMode.CLIENT && profile.foodStatus == Status.APPROVED && !isEditing) {
                            IconButton(onClick = { isEditing = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit Restaurant")
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                if (showFab) {
                    FloatingActionButton(
                        onClick = {
                            navigator?.push(MealWorkplaceScreen(restaurantId = restaurantInfo.id.ifBlank { profile.uid }))
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Meal")
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!isEditing) {
                    Text(
                        text = "Select Portal Mode",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SegmentedButton(
                            selected = selectedRoleMode == UserMode.CLIENT,
                            onClick = {
                                selectedRoleMode = UserMode.CLIENT
                                screenModel.updateFoodMode(UserMode.CLIENT)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            icon = { Icon(Icons.Default.Person, contentDescription = null) }
                        ) {
                            Text("Client")
                        }

                        SegmentedButton(
                            selected = selectedRoleMode == UserMode.MANAGER || selectedRoleMode != UserMode.CLIENT,
                            onClick = {
                                selectedRoleMode = UserMode.MANAGER
                                screenModel.updateFoodMode(UserMode.MANAGER)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            icon = { Icon(Icons.Default.Restaurant, contentDescription = null) }
                        ) {
                            Text("Manager")
                        }
                    }

                    HorizontalDivider()
                }

                when {
                    isEditing -> {
                        RestaurantApplicationForm(
                            initialName = restaurantInfo.name,
                            initialImage = restaurantInfo.imageUrl,
                            initialFee = restaurantInfo.deliveryFee.toString(),
                            initialTime = restaurantInfo.estimatedTimeMinutes.toString(),
                            initialPhone = restaurantInfo.managerPhone.ifBlank { profile.phoneNumber ?: "" },
                            submitButtonText = "Save Changes",
                            onSubmit = { name, img, fee, time, phone ->
                                screenModel.submitApplication(name, img, fee, time, phone)
                                isEditing = false
                            }
                        )
                    }
                    selectedRoleMode == UserMode.CLIENT -> ClientModeCard {
                        navigator?.push(RestaurantsScreen())
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        ) {
                            if (isLoading) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                when (profile.foodStatus) {
                                    Status.NONE -> RestaurantApplicationForm(
                                        initialPhone = profile.phoneNumber ?: "",
                                        onSubmit = { name, img, fee, time, phone ->
                                            screenModel.submitApplication(name, img, fee, time, phone)
                                        }
                                    )
                                    Status.PENDING -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PendingStatusView() }
                                    Status.REJECTED -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { RejectedStatusView() }
                                    Status.APPROVED -> ApprovedWorkplaceView(
                                        restaurantInfo = restaurantInfo,
                                        meals = meals,
                                        isOnline = profile.isFoodServiceOnline,
                                        onOnlineChanged = { screenModel.updateOnlineStatus(it) }
                                    )
                                    else -> {
                                        Text("Unknown Status")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ClientModeCard(onExploreFood: () -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExploreFood),
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
                    imageVector = Icons.Default.Fastfood,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Client Mode Active", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Browse restaurants, manage your food cart, and place orders in real-time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onExploreFood,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Explore Food Catalog")
                }
            }
        }
    }

    @Composable
    private fun RestaurantApplicationForm(
        initialName: String = "",
        initialImage: String = "",
        initialFee: String = "",
        initialTime: String = "",
        initialPhone: String,
        submitButtonText: String = "Submit Application",
        onSubmit: (String, String, Double, Int, String) -> Unit
    ) {
        var restaurantName by remember { mutableStateOf(initialName) }
        var imageUrl by remember { mutableStateOf(initialImage) }
        var deliveryFee by remember { mutableStateOf(initialFee) }
        var estimatedTime by remember { mutableStateOf(initialTime) }
        var managerPhone by remember { mutableStateOf(initialPhone) }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Restaurant Details", style = MaterialTheme.typography.headlineSmall)
            Text("Provide your restaurant information for management and review.", style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(
                value = restaurantName,
                onValueChange = { restaurantName = it },
                label = { Text("Restaurant Name") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = imageUrl,
                onValueChange = { imageUrl = it },
                label = { Text("Image URL") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = managerPhone,
                onValueChange = { managerPhone = it },
                label = { Text("Manager Phone Number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = deliveryFee,
                onValueChange = { deliveryFee = it },
                label = { Text("Delivery Fee ($)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = estimatedTime,
                onValueChange = { estimatedTime = it },
                label = { Text("Estimated Time (Minutes)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    onSubmit(
                        restaurantName,
                        imageUrl,
                        deliveryFee.toDoubleOrNull() ?: 0.0,
                        estimatedTime.toIntOrNull() ?: 20,
                        managerPhone
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = restaurantName.isNotBlank() && managerPhone.isNotBlank()
            ) {
                Text(submitButtonText)
            }
        }
    }

    @Composable
    private fun PendingStatusView() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.HourglassEmpty, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Application Pending", style = MaterialTheme.typography.titleLarge)
            Text("An admin is reviewing your restaurant application. Please check back later.", style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun RejectedStatusView() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Application Rejected", style = MaterialTheme.typography.titleLarge)
            Text("Unfortunately, your restaurant application was not approved.", style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun ApprovedWorkplaceView(
        restaurantInfo: ManagedRestaurantInfo,
        meals: List<Meal>,
        isOnline: Boolean,
        onOnlineChanged: (Boolean) -> Unit
    ) {
        val navigator = LocalNavigator.current
        val profile by rememberScreenModel { RestaurantWorkplaceScreenModel() }.profile.collectAsState()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            item {
                // Restaurant Online Availability Switch Card
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Restaurant, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Restaurant Status", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (isOnline) "Accepting Orders" else "Offline / Closed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Switch(checked = isOnline, onCheckedChange = onOnlineChanged)
                    }
                }
            }

            item {
                // Restaurant Information Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = restaurantInfo.name.ifBlank { "My Restaurant" },
                            style = MaterialTheme.typography.headlineSmall
                        )
                        HorizontalDivider()
                        Text(
                            text = "Delivery Fee: $${"%.2f".format(restaurantInfo.deliveryFee)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Prep / Estimated Time: ${restaurantInfo.estimatedTimeMinutes} minutes",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (restaurantInfo.managerPhone.isNotBlank()) {
                            Text(
                                text = "Manager Contact: ${restaurantInfo.managerPhone}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Restaurant Menu (${meals.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (meals.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No meals added yet. Tap + to add items to your menu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(meals, key = { it.id }) { meal ->
                    MealItemCard(
                        meal = meal,
                        onClick = {
                            navigator?.push(
                                MealWorkplaceScreen(
                                    restaurantId = restaurantInfo.id.ifBlank { profile.uid },
                                    meal = meal
                                )
                            )
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun MealItemCard(meal: Meal, onClick: () -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = meal.name, style = MaterialTheme.typography.titleMedium)
                    if (meal.description.isNotBlank()) {
                        Text(text = meal.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Badge {
                        Text(text = meal.category.name, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    text = "$${"%.2f".format(meal.basePrice)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
