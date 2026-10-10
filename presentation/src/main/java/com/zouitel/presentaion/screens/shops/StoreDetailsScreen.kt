package com.zouitel.presentaion.screens.shops

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.LiveOrderTrackingScreen
import kotlinx.coroutines.launch

data class StoreDetailsScreen(val shop: Shop) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { StoreDetailsScreenModel(shop) }
        val products by screenModel.products.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        val cartItems = remember { mutableStateMapOf<String, ShopCartItem>() }
        var showOrderSheet by remember { mutableStateOf(false) }

        var deliveryAddress by remember { mutableStateOf("") }
        var paymentMethod by remember { mutableStateOf("Cash on Delivery") }
        var isSubmitting by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        val totalItemsCount = cartItems.values.sumOf { it.quantity }
        val subtotal = cartItems.values.sumOf { it.totalPrice }
        val courierFee = 3.00
        val grandTotal = if (totalItemsCount > 0) subtotal + courierFee else 0.0

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(shop.name) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            },
            bottomBar = {
                if (totalItemsCount > 0) {
                    Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "$totalItemsCount product(s)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "$${"%.2f".format(grandTotal)}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Button(onClick = { showOrderSheet = true }) {
                                Icon(Icons.Default.ReceiptLong, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("View Demand Card")
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                // Store Info Header
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = shop.name, style = MaterialTheme.typography.titleMedium)
                        Text(text = "Category: ${shop.category}", style = MaterialTheme.typography.bodySmall)
                        Text(text = "Address: ${shop.address}", style = MaterialTheme.typography.bodySmall)
                        if (shop.description.isNotBlank()) {
                            Text(text = shop.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Text(
                    text = "Store Products Catalog",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (products.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        Text("No items listed in this store currently.")
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(products, key = { it.id }) { product ->
                            val currentItem = cartItems[product.id]
                            val currentQty = currentItem?.quantity ?: 0

                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = product.name, style = MaterialTheme.typography.titleMedium)
                                        Text(text = product.description, style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            text = "$${"%.2f".format(product.price)}",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    if (currentQty == 0) {
                                        OutlinedButton(
                                            onClick = {
                                                cartItems[product.id] = ShopCartItem(
                                                    productId = product.id,
                                                    productName = product.name,
                                                    price = product.price,
                                                    quantity = 1
                                                )
                                            }
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Add")
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(onClick = {
                                                if (currentQty > 1) {
                                                    cartItems[product.id] = currentItem!!.copy(quantity = currentQty - 1)
                                                } else {
                                                    cartItems.remove(product.id)
                                                }
                                            }) {
                                                Icon(Icons.Default.Remove, contentDescription = "Decrease")
                                            }
                                            Text("$currentQty", style = MaterialTheme.typography.titleMedium)
                                            IconButton(onClick = {
                                                cartItems[product.id] = currentItem!!.copy(quantity = currentQty + 1)
                                            }) {
                                                Icon(Icons.Default.Add, contentDescription = "Increase")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Demand Card Bottom Sheet Popup
            if (showOrderSheet) {
                ModalBottomSheet(onDismissRequest = { if (!isSubmitting) showOrderSheet = false }) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Store Demand Card Summary", style = MaterialTheme.typography.titleLarge)
                        HorizontalDivider()

                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(cartItems.values.toList(), key = { it.productId }) { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = item.productName, style = MaterialTheme.typography.bodyLarge)
                                        Text(text = "$${"%.2f".format(item.price)} x ${item.quantity} = $${"%.2f".format(item.totalPrice)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    IconButton(onClick = { cartItems.remove(item.productId) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        HorizontalDivider()

                        OutlinedTextField(
                            value = deliveryAddress,
                            onValueChange = { deliveryAddress = it },
                            label = { Text("Delivery Address") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = paymentMethod,
                            onValueChange = { paymentMethod = it },
                            label = { Text("Payment Method") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total Demand Price:", style = MaterialTheme.typography.titleMedium)
                            Text("$${"%.2f".format(grandTotal)}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        }

                        Button(
                            enabled = deliveryAddress.isNotBlank() && cartItems.isNotEmpty() && !isSubmitting,
                            onClick = {
                                isSubmitting = true
                                scope.launch {
                                    screenModel.placeShopOrder(
                                        cartItems = cartItems.values.toList(),
                                        subtotal = subtotal,
                                        courierFee = courierFee,
                                        totalPrice = grandTotal,
                                        deliveryAddress = deliveryAddress,
                                        paymentMethod = paymentMethod,
                                        onSuccess = { orderId ->
                                            isSubmitting = false
                                            showOrderSheet = false
                                            cartItems.clear()
                                            navigator?.push(LiveOrderTrackingScreen(orderId))
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Icon(Icons.Default.Send, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Send Demand Card to Store Manager")
                            }
                        }
                    }
                }
            }
        }
    }
}