package com.zouitel.presentaion.screens.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.screens.account.AccountScreenModel
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.launch

data class RestaurantScreen(val restaurant: Restaurant) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { RestaurantDetailScreenModel(restaurant.id) }
        val accountModel = koinScreenModel<AccountScreenModel>()
        val meals by screenModel.meals.collectAsState()
        val profile by accountModel.profile.collectAsState()

        val isAuthorizedManager = profile?.let { user ->
            user.isAdmin || user.email in restaurant.managers || user.phoneNumber in restaurant.managers
        } ?: false

        // Order Sheet State (mealId -> CartItem)
        val orderSheetItems = remember { mutableStateMapOf<String, CartItem>() }
        var showOrderBottomSheet by remember { mutableStateOf(false) }

        var deliveryAddress by remember { mutableStateOf("") }
        var paymentMethod by remember { mutableStateOf("Cash on Delivery") }
        var isSubmittingOrder by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        val totalItemsCount = orderSheetItems.values.sumOf { it.quantity }
        val subtotal = orderSheetItems.values.sumOf { it.basePrice * it.quantity }
        val grandTotal = if (totalItemsCount > 0) subtotal + restaurant.deliveryFee else 0.0

        Scaffold(
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
            floatingActionButton = {
                if (isAuthorizedManager) {
                    FloatingActionButton(
                        onClick = { navigator?.push(MealWorkplaceScreen(restaurant.id)) }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Meal")
                    }
                }
            },
            bottomBar = {
                if (totalItemsCount > 0) {
                    Surface(
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "$totalItemsCount item(s) selected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "$${"%.2f".format(grandTotal)}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Button(
                                onClick = { showOrderBottomSheet = true },
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Icon(Icons.Default.ReceiptLong, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("View Order Sheet")
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Restaurant Info Header
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Rating: ⭐ ${restaurant.rating}", style = MaterialTheme.typography.bodyMedium)
                        Text(text = "Delivery Fee: $${"%.2f".format(restaurant.deliveryFee)}", style = MaterialTheme.typography.bodyMedium)
                        Text(text = "Est. Delivery: ${restaurant.estimatedTimeMinutes} mins", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Text(
                    text = "Menu Items",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                if (meals.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No meals available in this restaurant.")
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(meals, key = { it.id }) { meal ->
                            val currentItem = orderSheetItems[meal.id]
                            val currentQuantity = currentItem?.quantity ?: 0

                            MealCardItem(
                                meal = meal,
                                currentQuantity = currentQuantity,
                                onClickMealCard = {
                                    // Navigate to MealScreen to customize quantity / notes
                                    navigator?.push(
                                        MealScreen(
                                            meal = meal,
                                            initialQuantity = if (currentQuantity > 0) currentQuantity else 1,
                                            onAddToOrderSheet = { updatedItem ->
                                                orderSheetItems[meal.id] = updatedItem
                                            }
                                        )
                                    )
                                },
                                onQuickAdd = {
                                    orderSheetItems[meal.id] = CartItem(
                                        mealId = meal.id,
                                        mealName = meal.name,
                                        basePrice = meal.basePrice,
                                        quantity = currentQuantity + 1,
                                        selectedOptions = emptyList()
                                    )
                                },
                                onQuickRemove = {
                                    if (currentQuantity > 1) {
                                        orderSheetItems[meal.id] = currentItem!!.copy(quantity = currentQuantity - 1)
                                    } else {
                                        orderSheetItems.remove(meal.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // Order Sheet Popup (ModalBottomSheet)
        if (showOrderBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { if (!isSubmittingOrder) showOrderBottomSheet = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Order Sheet Summary",
                        style = MaterialTheme.typography.titleLarge
                    )

                    HorizontalDivider()

                    // Selected Meals List in Sheet
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(orderSheetItems.values.toList(), key = { it.mealId }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val meal = meals.find { it.id == item.mealId }
                                        if (meal != null) {
                                            navigator?.push(
                                                MealScreen(
                                                    meal = meal,
                                                    initialQuantity = item.quantity,
                                                    onAddToOrderSheet = { updatedItem ->
                                                        orderSheetItems[meal.id] = updatedItem
                                                    }
                                                )
                                            )
                                        }
                                    },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = item.mealName, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = "$${"%.2f".format(item.basePrice)} x ${item.quantity} = $${"%.2f".format(item.totalPrice)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (item.specialInstructions.isNotBlank()) {
                                        Text(
                                            text = "Note: ${item.specialInstructions}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = {
                                        if (item.quantity > 1) {
                                            orderSheetItems[item.mealId] = item.copy(quantity = item.quantity - 1)
                                        } else {
                                            orderSheetItems.remove(item.mealId)
                                        }
                                    }) {
                                        Icon(
                                            imageVector = if (item.quantity == 1) Icons.Default.Delete else Icons.Default.Remove,
                                            contentDescription = "Decrease",
                                            tint = if (item.quantity == 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    Text("${item.quantity}", style = MaterialTheme.typography.titleMedium)

                                    IconButton(onClick = {
                                        orderSheetItems[item.mealId] = item.copy(quantity = item.quantity + 1)
                                    }) {
                                        Icon(Icons.Default.Add, contentDescription = "Increase")
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    // Address & Details Input
                    OutlinedTextField(
                        value = deliveryAddress,
                        onValueChange = { deliveryAddress = it },
                        label = { Text("Delivery Address") },
                        placeholder = { Text("Enter building, street, floor #") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = paymentMethod,
                        onValueChange = { paymentMethod = it },
                        label = { Text("Payment Method") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Price Breakdown
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Subtotal:", style = MaterialTheme.typography.bodyMedium)
                            Text("$${"%.2f".format(subtotal)}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Delivery Fee:", style = MaterialTheme.typography.bodyMedium)
                            Text("$${"%.2f".format(restaurant.deliveryFee)}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Grand Total:", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "$${"%.2f".format(grandTotal)}",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Send Order Card to Manager Button
                    Button(
                        enabled = deliveryAddress.isNotBlank() && orderSheetItems.isNotEmpty() && !isSubmittingOrder,
                        onClick = {
                            isSubmittingOrder = true
                            scope.launch {
                                sendOrderCardToManager(
                                    restaurant = restaurant,
                                    cartItems = orderSheetItems.values.toList(),
                                    subtotal = subtotal,
                                    totalPrice = grandTotal,
                                    deliveryAddress = deliveryAddress,
                                    paymentMethod = paymentMethod,
                                    onSuccess = { orderId ->
                                        isSubmittingOrder = false
                                        showOrderBottomSheet = false
                                        orderSheetItems.clear()
                                        navigator?.push(LiveOrderTrackingScreen(orderId))
                                    }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(14.dp)
                    ) {
                        if (isSubmittingOrder) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Send, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Send Order Card to Manager")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MealCardItem(
    meal: Meal,
    currentQuantity: Int,
    onClickMealCard: () -> Unit,
    onQuickAdd: () -> Unit,
    onQuickRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClickMealCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = meal.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "$${"%.2f".format(meal.basePrice)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = meal.description, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SuggestionChip(
                    onClick = onClickMealCard,
                    label = { Text(meal.category.name) }
                )

                if (currentQuantity == 0) {
                    OutlinedButton(onClick = onQuickAdd) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add")
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(onClick = onQuickRemove) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease")
                        }
                        Text(
                            text = "$currentQuantity",
                            style = MaterialTheme.typography.titleMedium
                        )
                        IconButton(onClick = onQuickAdd) {
                            Icon(Icons.Default.Add, contentDescription = "Increase")
                        }
                    }
                }
            }
        }
    }
}

private suspend fun sendOrderCardToManager(
    restaurant: Restaurant,
    cartItems: List<CartItem>,
    subtotal: Double,
    totalPrice: Double,
    deliveryAddress: String,
    paymentMethod: String,
    onSuccess: (String) -> Unit
) {
    val db = Firebase.database.reference()
    val currentUserId = Firebase.auth.currentUser?.uid ?: "client_user"
    val newOrderRef = db.child("orders").push()
    val orderId = newOrderRef.key ?: System.currentTimeMillis().toString()

    val order = Order(
        id = orderId,
        userId = currentUserId,
        restaurantId = restaurant.id,
        restaurantName = restaurant.name,
        items = cartItems,
        subtotal = subtotal,
        deliveryFee = restaurant.deliveryFee,
        totalPrice = totalPrice,
        status = OrderStatus.PLACED,
        deliveryAddress = deliveryAddress,
        createdAtTimestamp = System.currentTimeMillis(),
        paymentMethod = paymentMethod
    )

    // Save order payload to Realtime Database so it appears in Manager Dashboard
    newOrderRef.setValue(order)
    onSuccess(orderId)
}