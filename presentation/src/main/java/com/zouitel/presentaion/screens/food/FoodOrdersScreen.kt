package com.zouitel.presentaion.screens.food

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
import java.text.SimpleDateFormat
import java.util.*

class FoodOrdersScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { FoodOrdersScreenModel() }
        val orders by screenModel.orders.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Manager Orders Dashboard", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (orders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No client orders received yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(orders, key = { it.id }) { order ->
                        ClientOrderCard(
                            order = order,
                            onStatusChange = { newStatus -> screenModel.updateOrderStatus(order, newStatus) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ClientOrderCard(
        order: Order,
        onStatusChange: (OrderStatus) -> Unit
    ) {
        val formattedDate = remember(order.createdAtTimestamp) {
            val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            sdf.format(Date(order.createdAtTimestamp))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Order #${order.id.takeLast(6).uppercase()}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(text = formattedDate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(modifier = Modifier.height(8.dp))
                StatusChip(status = order.status)

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Client & Delivery Info
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Client & Delivery Info",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(text = "Client ID: ${order.clientId.ifBlank { "Guest Client" }}", style = MaterialTheme.typography.bodySmall)
                    Text(text = "Pickup: ${order.pickupLocation.ifBlank { order.sourceName }}", style = MaterialTheme.typography.bodySmall)
                    Text(text = "Drop-off: ${order.dropoffLocation.ifBlank { "Not provided" }}", style = MaterialTheme.typography.bodySmall)
                    Text(text = "Payment: ${order.paymentMethod.ifBlank { "Cash" }}", style = MaterialTheme.typography.bodySmall)
                    Text(text = "Payment status: ${order.paymentStatus.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                    if (order.cashCollectionStatus == "COLLECTED") {
                        Text(
                            text = "Cash collected by ${order.cashCollectedByRole.lowercase()}: ₱${"%.2f".format(order.cashCollectedAmountCentavos / 100.0)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Merchant payable: ₱${"%.2f".format(order.cashMerchantOwedCentavos / 100.0)} · Platform: ₱${"%.2f".format(order.cashPlatformOwedCentavos / 100.0)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Remitted: ₱${"%.2f".format(order.cashRemittedCentavos / 100.0)} · Remaining due: ₱${"%.2f".format(order.cashOutstandingRemittanceCentavos / 100.0)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    if (order.deliveryName.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Assigned Courier: ${order.deliveryName} (${order.deliveryPhone})",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Items list container
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Ordered Items (${order.items.sumOf { item -> item.quantity }})",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        order.items.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${item.quantity}x ${item.name}", style = MaterialTheme.typography.bodySmall)
                                Text("₱${"%.2f".format(item.totalPrice)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Total Amount", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "₱${"%.2f".format(order.totalPrice)}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                OrderStatusActions(status = order.status, onStatusChange = onStatusChange)
            }
        }
    }

    @Composable
    private fun StatusChip(status: OrderStatus) {
        val (color, containerColor, text) = when (status) {
            OrderStatus.AWAITING_PAYMENT -> Triple(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer, "PAYMENT PENDING")
            OrderStatus.PLACED -> Triple(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer, "NEW ORDER")
            OrderStatus.ACCEPTED -> Triple(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer, "ACCEPTED")
            OrderStatus.PREPARING -> Triple(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.secondaryContainer, "PREPARING IN KITCHEN")
            OrderStatus.OUT_FOR_DELIVERY -> Triple(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, "OUT FOR COURIER")
            OrderStatus.DELIVERED -> Triple(MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surfaceVariant, "DELIVERED")
            OrderStatus.CANCELLED -> Triple(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer, "CANCELLED")
        }

        Surface(
            color = containerColor,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = color
            )
        }
    }

    @Composable
    private fun OrderStatusActions(
        status: OrderStatus,
        onStatusChange: (OrderStatus) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (status) {
                OrderStatus.AWAITING_PAYMENT -> { /* Payment must complete before the merchant can accept the order. */ }
                OrderStatus.PLACED -> {
                    Button(
                        onClick = { onStatusChange(OrderStatus.ACCEPTED) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Accept")
                    }
                    OutlinedButton(
                        onClick = { onStatusChange(OrderStatus.CANCELLED) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reject")
                    }
                }
                OrderStatus.ACCEPTED -> {
                    Button(
                        onClick = { onStatusChange(OrderStatus.PREPARING) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Start Kitchen Preparation")
                    }
                }
                OrderStatus.PREPARING -> {
                    Button(
                        onClick = { onStatusChange(OrderStatus.OUT_FOR_DELIVERY) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Send Out for Delivery")
                    }
                }
                OrderStatus.OUT_FOR_DELIVERY -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Handed to courier. Awaiting delivery confirmation.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OrderStatus.DELIVERED, OrderStatus.CANCELLED -> {
                    // Final states
                }
            }
        }
    }
}