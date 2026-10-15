package com.zouitel.presentaion.screens.food

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.google.android.gms.maps.model.LatLng
import com.zouitel.presentaion.common.LiveOrderTrackingScreen
import com.zouitel.presentaion.common.LocationPickerDialog

data class RestaurantScreen(val restaurant: Restaurant) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { RestaurantScreenModel(restaurant) }

        val meals by screenModel.meals.collectAsState()
        val quantities by screenModel.quantities.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        val isSubmitting by screenModel.isSubmitting.collectAsState()
        val error by screenModel.error.collectAsState()

        val snackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(error) {
            error?.let {
                snackbarHostState.showSnackbar(it)
                screenModel.clearError()
            }
        }

        // Checkout state
        var showOrderSheet by remember { mutableStateOf(false) }
        var showMapPicker by remember { mutableStateOf(false) }
        var dropoff by remember { mutableStateOf<LatLng?>(null) }
        var deliveryAddress by remember { mutableStateOf("") }
        val paymentOptions = remember(restaurant) {
            restaurant.paymentMethods.ifEmpty { listOf("Cash on Delivery") }
        }
        var paymentMethod by remember { mutableStateOf(paymentOptions.first()) }

        val restaurantPoint = remember(restaurant) {
            if (restaurant.latitude != 0.0 || restaurant.longitude != 0.0)
                LatLng(restaurant.latitude, restaurant.longitude) else null
        }

        // Cart derived from the live menu (items that became unavailable drop out)
        val availableMeals = remember(meals) { meals.filter { it.isAvailable } }
        val cartLines = availableMeals.filter { (quantities[it.id] ?: 0) > 0 }
        val itemCount = cartLines.sumOf { quantities[it.id] ?: 0 }
        val subtotal = cartLines.sumOf { it.basePrice * (quantities[it.id] ?: 0) }
        val grandTotal = if (itemCount > 0) subtotal + restaurant.deliveryFee else 0.0

        val groupedMeals = remember(availableMeals) { availableMeals.groupBy { it.category } }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(restaurant.name) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            },
            bottomBar = {
                if (itemCount > 0) {
                    Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "$itemCount item(s)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "$${"%.2f".format(grandTotal)}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Button(
                                onClick = { showOrderSheet = true },
                                enabled = restaurant.isOpen
                            ) {
                                Icon(Icons.Default.ReceiptLong, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (restaurant.isOpen) "Review Order" else "Restaurant Closed")
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

                // Restaurant info header
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(restaurant.name, style = MaterialTheme.typography.titleLarge)
                            Badge(
                                containerColor = if (restaurant.isOpen) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    if (restaurant.isOpen) "Online" else "Offline",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        Text(
                            "Delivery: $${"%.2f".format(restaurant.deliveryFee)} · ${restaurant.estimatedTimeMinutes} mins",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            restaurant.address.ifBlank { "Location not set" },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (restaurant.address.isBlank()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                when {
                    isLoading -> Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    availableMeals.isEmpty() -> Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        Text("No meals available right now.")
                    }

                    else -> LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        groupedMeals.forEach { (category, list) ->
                            item(key = "header_${category.name}") {
                                Text(
                                    category.name.lowercase().replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                            items(list, key = { it.id }) { meal ->
                                MealRow(
                                    meal = meal,
                                    quantity = quantities[meal.id] ?: 0,
                                    onAdd = { screenModel.addMeal(meal.id) },
                                    onRemove = { screenModel.removeMeal(meal.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------- Checkout bottom sheet ----------
        if (showOrderSheet) {
            ModalBottomSheet(onDismissRequest = { if (!isSubmitting) showOrderSheet = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Your Order", style = MaterialTheme.typography.titleLarge)
                    HorizontalDivider()

                    cartLines.forEach { meal ->
                        val qty = quantities[meal.id] ?: 0
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(meal.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "$${"%.2f".format(meal.basePrice)} x $qty = $${"%.2f".format(meal.basePrice * qty)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    screenModel.removeMeal(meal.id)
                                    if (itemCount <= 1) showOrderSheet = false
                                }) { Icon(Icons.Default.Remove, contentDescription = "Decrease") }
                                Text("$qty")
                                IconButton(onClick = { screenModel.addMeal(meal.id) }) {
                                    Icon(Icons.Default.Add, contentDescription = "Increase")
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    // Pickup = restaurant
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Pickup (restaurant)", style = MaterialTheme.typography.labelMedium)
                            Text(
                                restaurant.address.ifBlank { restaurant.name },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    if (restaurantPoint == null) {
                        Text(
                            "This restaurant hasn't set its location yet, so it can't take delivery orders.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // Drop-off = picked on the map
                    OutlinedButton(
                        onClick = {
                            showOrderSheet = false
                            showMapPicker = true
                        },
                        enabled = restaurantPoint != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (dropoff == null) "Choose drop-off on map" else "Change drop-off location")
                    }

                    if (dropoff != null) {
                        OutlinedTextField(
                            value = deliveryAddress,
                            onValueChange = { deliveryAddress = it },
                            label = { Text("Drop-off address (add details if needed)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Payment
                    Text("Payment method", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        paymentOptions.forEach { option ->
                            FilterChip(
                                selected = paymentMethod == option,
                                onClick = { paymentMethod = option },
                                label = { Text(option) }
                            )
                        }
                    }

                    HorizontalDivider()

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Subtotal")
                        Text("$${"%.2f".format(subtotal)}")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Delivery fee")
                        Text("$${"%.2f".format(restaurant.deliveryFee)}")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "$${"%.2f".format(grandTotal)}",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Button(
                        enabled = restaurant.isOpen && restaurantPoint != null && dropoff != null &&
                                deliveryAddress.isNotBlank() && cartLines.isNotEmpty() && !isSubmitting,
                        onClick = {
                            val drop = dropoff ?: return@Button
                            screenModel.placeOrder(
                                lines = cartLines,
                                paymentMethod = paymentMethod,
                                dropoffAddress = deliveryAddress,
                                dropoffLat = drop.latitude,
                                dropoffLng = drop.longitude,
                                onSuccess = { orderId ->
                                    showOrderSheet = false
                                    dropoff = null
                                    deliveryAddress = ""
                                    navigator?.push(LiveOrderTrackingScreen(orderId))
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Place Order")
                        }
                    }
                }
            }
        }

        // ---------- Map picker for the drop-off ----------
        if (showMapPicker) {
            LocationPickerDialog(
                title = "Choose drop-off location",
                confirmLabel = "Confirm drop-off",
                initial = dropoff,
                referencePoint = restaurantPoint,
                referenceLabel = restaurant.name,
                onConfirm = { point, address ->
                    dropoff = point
                    deliveryAddress = address
                    showMapPicker = false
                    showOrderSheet = true
                },
                onDismiss = {
                    showMapPicker = false
                    showOrderSheet = true
                }
            )
        }
    }

    @Composable
    private fun MealRow(
        meal: Meal,
        quantity: Int,
        onAdd: () -> Unit,
        onRemove: () -> Unit
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(meal.name, style = MaterialTheme.typography.titleMedium)
                    if (meal.description.isNotBlank()) {
                        Text(
                            meal.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "$${"%.2f".format(meal.basePrice)}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (quantity == 0) {
                    OutlinedButton(onClick = onAdd) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease")
                        }
                        Text("$quantity", style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = onAdd) {
                            Icon(Icons.Default.Add, contentDescription = "Increase")
                        }
                    }
                }
            }
        }
    }
}