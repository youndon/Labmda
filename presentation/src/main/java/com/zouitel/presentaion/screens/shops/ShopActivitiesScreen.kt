package com.zouitel.presentaion.screens.shops

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.LiveOrderTrackingScreen
import com.zouitel.presentaion.common.Order
import com.zouitel.presentaion.common.OrderStatus
import java.text.SimpleDateFormat
import java.util.*

class ShopActivitiesScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { ShopActivitiesScreenModel() }
        val activities by screenModel.activities.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("My Store Order Activities") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (activities.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    Text("You have no active store orders.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(activities, key = { it.id }) { order ->
                        ClientActivityCard(
                            order = order,
                            onTrackOrder = { navigator?.push(LiveOrderTrackingScreen(order.id)) },
                            onCancelOrder = { screenModel.cancelShopOrder(order.id) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ClientActivityCard(order: Order, onTrackOrder: () -> Unit, onCancelOrder: () -> Unit) {
        val formattedDate = remember(order.createdAtTimestamp) {
            val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            sdf.format(Date(order.createdAtTimestamp))
        }

        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(order.shopName.ifBlank { "Store" }, style = MaterialTheme.typography.titleMedium)
                    Text(formattedDate, style = MaterialTheme.typography.bodySmall)
                }

                Text("Demand #${order.id.takeLast(6).uppercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))

                StatusChip(order.status)
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text("Delivery Address: ${order.deliveryAddress}", style = MaterialTheme.typography.bodyMedium)
                Text("Payment: ${order.paymentMethod.ifBlank { "Cash on Delivery" }}", style = MaterialTheme.typography.bodyMedium)

                Spacer(modifier = Modifier.height(12.dp))

                Text("Products (${order.items.sumOf { it.quantity }})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                order.items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${item.quantity}x ${item.name}", style = MaterialTheme.typography.bodySmall)
                        Text("$${"%.2f".format(item.totalPrice)}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total Price:", style = MaterialTheme.typography.titleSmall)
                    Text("$${"%.2f".format(order.totalPrice)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onTrackOrder, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Track Status")
                    }

                    if (order.status == OrderStatus.PLACED) {
                        OutlinedButton(
                            onClick = onCancelOrder,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusChip(status: OrderStatus) {
        val (color, text) = when (status) {
            OrderStatus.PLACED -> MaterialTheme.colorScheme.tertiary to "ORDER PLACED"
            OrderStatus.ACCEPTED -> MaterialTheme.colorScheme.secondary to "CONFIRMED BY STORE"
            OrderStatus.PREPARING -> MaterialTheme.colorScheme.primary to "IN PACKAGING"
            OrderStatus.OUT_FOR_DELIVERY -> MaterialTheme.colorScheme.primary to "OUT FOR COURIER"
            OrderStatus.DELIVERED -> MaterialTheme.colorScheme.outline to "DELIVERED"
            OrderStatus.CANCELLED -> MaterialTheme.colorScheme.error to "CANCELLED"
        }
        SuggestionChip(onClick = {}, label = { Text(text) }, colors = SuggestionChipDefaults.suggestionChipColors(labelColor = color))
    }
}