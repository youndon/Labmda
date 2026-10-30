package com.zouitel.presentaion.screens.food

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import com.google.android.gms.maps.model.LatLng
import com.zouitel.presentaion.common.LiveOrderTrackingScreen
import com.zouitel.presentaion.common.LocationPickerDialog
import com.zouitel.presentaion.payments.PayButton
import com.zouitel.presentaion.payments.PaymentEventEffect
import com.zouitel.presentaion.payments.PaymentMethodSelector
import com.zouitel.presentaion.payments.PaymentRepository
import com.zouitel.presentaion.payments.PaymentResultSheet
import com.zouitel.presentaion.payments.PaymentScreenModel
import org.koin.core.context.GlobalContext

data class RestaurantScreen(val restaurant: Restaurant) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val uriHandler = LocalUriHandler.current
        val paymentRepository = remember { GlobalContext.get().get<PaymentRepository>() }
        val screenModel = rememberScreenModel {
            RestaurantScreenModel(restaurant, payments = paymentRepository)
        }
        val paymentScreenModel = koinScreenModel<PaymentScreenModel>()
        val paymentUiState by paymentScreenModel.uiState.collectAsState()

        val meals by screenModel.meals.collectAsState()
        val quantities by screenModel.quantities.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        val isSubmitting by screenModel.isSubmitting.collectAsState()
        val error by screenModel.error.collectAsState()

        val snackbarHostState = remember { SnackbarHostState() }
        PaymentEventEffect(paymentScreenModel, uriHandler)
        LaunchedEffect(error) {
            error?.let {
                snackbarHostState.showSnackbar(it)
                screenModel.clearError()
            }
        }

        var showOrderSheet by remember { mutableStateOf(false) }
        var showMapPicker by remember { mutableStateOf(false) }
        var dropoff by remember { mutableStateOf<LatLng?>(null) }
        var deliveryAddress by remember { mutableStateOf("") }
        val restaurantPoint = remember(restaurant) {
            if (restaurant.latitude != 0.0 || restaurant.longitude != 0.0)
                LatLng(restaurant.latitude, restaurant.longitude) else null
        }

        val availableMeals = remember(meals) { meals.filter { it.isAvailable } }
        val cartLines = availableMeals.filter { (quantities[it.id] ?: 0) > 0 }
        val itemCount = cartLines.sumOf { quantities[it.id] ?: 0 }
        val subtotal = cartLines.sumOf { it.basePrice * (quantities[it.id] ?: 0) }
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val groupedMeals = remember(availableMeals) { availableMeals.groupBy { it.category }.toList() }

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                LargeFlexibleTopAppBar(
                    title = {
                        Column {
                            Text(
                                restaurant.name,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.headlineMedium
                            )
                            // Show details only when expanded (collapsedFraction close to 0)
                            if (scrollBehavior.state.collapsedFraction < 0.5f) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.graphicsLayer {
                                        alpha = 1f - (scrollBehavior.state.collapsedFraction * 2f).coerceIn(0f, 1f)
                                    }
                                ) {
                                    Text(
                                        "Fee: ₱${"%.2f".format(restaurant.deliveryFee)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "${restaurant.estimatedTimeMinutes} mins",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Surface(
                                        color = if (restaurant.isOpen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            if (restaurant.isOpen) "Open" else "Closed",
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (restaurant.isOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        FilledIconButton(
                            onClick = { navigator?.pop() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                if (itemCount > 0) {
                    Surface(
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "$itemCount item(s) selected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "₱${"%.2f".format(grandTotal)}",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Button(
                                onClick = { showOrderSheet = true },
                                enabled = restaurant.isOpen,
                                modifier = Modifier.height(50.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (restaurant.isOpen) "Review Order" else "Restaurant Closed", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(top = paddingValues.calculateTopPadding())) {

                when {
                    isLoading -> Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    availableMeals.isEmpty() -> Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        Text("No meals available right now.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        groupedMeals.forEach { (category, list) ->
                            items(list, key = { it.id }) { meal ->
                                MealCard(
                                    meal = meal,
                                    quantity = quantities[meal.id] ?: 0,
                                    onAdd = { screenModel.addMeal(meal.id) },
                                    onRemove = { screenModel.removeMeal(meal.id) },
                                    onClick = {
                                        navigator?.push(
                                            MealScreen(
                                                meal = meal,
                                                onAddToOrderSheet = { screenModel.addMeal(meal.id) }
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Checkout Bottom Sheet
        if (showOrderSheet) {
            ModalBottomSheet(onDismissRequest = { if (!isSubmitting) showOrderSheet = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Review Your Order", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    cartLines.forEach { meal ->
                        val qty = quantities[meal.id] ?: 0
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(meal.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                                Text(
                                    "₱${"%.2f".format(meal.basePrice)} × $qty = ₱${"%.2f".format(meal.basePrice * qty)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    screenModel.removeMeal(meal.id)
                                    if (itemCount <= 1) showOrderSheet = false
                                }) { Icon(Icons.Default.Remove, contentDescription = "Decrease") }
                                Text("$qty", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                IconButton(onClick = { screenModel.addMeal(meal.id) }) {
                                    Icon(Icons.Default.Add, contentDescription = "Increase")
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Pickup Location
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Pickup (Restaurant)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(restaurant.address.ifBlank { restaurant.name }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }

                    if (restaurantPoint == null) {
                        Text(
                            "This restaurant hasn't set its location yet, so it can't take delivery orders.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            showOrderSheet = false
                            showMapPicker = true
                        },
                        enabled = restaurantPoint != null,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (dropoff == null) "Choose drop-off location on map" else "Change drop-off location")
                    }

                    if (dropoff != null) {
                        OutlinedTextField(
                            value = deliveryAddress,
                            onValueChange = { deliveryAddress = it },
                            label = { Text("Drop-off address details") },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    PaymentMethodSelector(
                        selectedMethod = paymentUiState.selectedMethod,
                        onMethodSelected = paymentScreenModel::selectMethod,
                        enabled = !paymentUiState.isSubmitting
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Estimated item subtotal", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("₱${"%.2f".format(subtotal)}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            "Final catalogue prices, route-based delivery charges and order total are calculated by Labmda before checkout.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    PayButton(
                        label = "Place Order Now",
                        enabled = restaurant.isOpen && restaurantPoint != null && dropoff != null &&
                            deliveryAddress.isNotBlank() && cartLines.isNotEmpty(),
                        isLoading = isSubmitting || paymentUiState.isSubmitting,
                        onClick = {
                            val drop = dropoff ?: return@PayButton
                            paymentScreenModel.checkoutStarted()
                            screenModel.placeOrder(
                                lines = cartLines,
                                paymentMethod = paymentUiState.selectedMethod.name,
                                dropoffAddress = deliveryAddress,
                                dropoffLat = drop.latitude,
                                dropoffLng = drop.longitude,
                                onSuccess = { result ->
                                    showOrderSheet = false
                                    dropoff = null
                                    deliveryAddress = ""
                                    paymentScreenModel.checkoutCreated(result)
                                },
                                onFailure = paymentScreenModel::checkoutFailed
                            )
                        }
                    )
                }
            }
        }

        // Map Picker for Drop-off
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

        PaymentResultSheet(
            state = paymentUiState,
            onDismiss = {
                val orderId = paymentUiState.creation?.entityId
                paymentScreenModel.dismissResult()
                orderId?.let { navigator?.push(LiveOrderTrackingScreen(it)) }
            },
            onOpenCheckout = { payment ->
                (payment.checkoutUrl ?: payment.webCheckoutUrl)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { url -> runCatching { uriHandler.openUri(url) } }
            }
        )
    }

    @Composable
    private fun MealCard(
        meal: Meal,
        quantity: Int,
        onAdd: () -> Unit,
        onRemove: () -> Unit,
        onClick: () -> Unit
    ) {
        val imageUrl = meal.imageUrls.firstOrNull().orEmpty()
        val hasImage = imageUrl.isNotBlank()

        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (hasImage) 260.dp else 160.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (hasImage) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = meal.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }

                Column(
                    modifier = Modifier.weight(1f).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = meal.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = "₱${"%.2f".format(meal.basePrice)}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )

//                    if (meal.description.isNotBlank()) {
//                        Text(
//                            text = meal.description,
//                            style = MaterialTheme.typography.bodySmall,
//                            color = MaterialTheme.colorScheme.onSurfaceVariant,
//                            maxLines = 2
//                        )
//                    }
                }

                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    if (quantity == 0) {
                        OutlinedButton(
                            onClick = onAdd,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            IconButton(onClick = onRemove) {
                                Icon(Icons.Default.Remove, contentDescription = "Decrease")
                            }
                            Text("$quantity", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            IconButton(onClick = onAdd) {
                                Icon(Icons.Default.Add, contentDescription = "Increase")
                            }
                        }
                    }
                }
            }
        }
    }
}