package com.zouitel.presentaion.screens.food

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.Order
import com.zouitel.presentaion.common.OrderStatus
import java.text.SimpleDateFormat
import java.util.*

class DashboardScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { DashboardScreenModel() }
        val orders by screenModel.orders.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Manager - Orders Dashboard") },
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
                    Text("No client orders received yet.")
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
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header: Order ID & Date
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Order #${order.id.takeLast(6).uppercase()}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(text = formattedDate, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(4.dp))
                StatusChip(status = order.status)

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Client & Courier Info
                Text(
                    text = "Client Info & Delivery Address",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(text = "Client ID: ${order.userId.ifBlank { "Guest Client" }}", style = MaterialTheme.typography.bodyMedium)
                Text(text = "Address: ${order.deliveryAddress}", style = MaterialTheme.typography.bodyMedium)
                Text(text = "Payment: ${order.paymentMethod.ifBlank { "Cash" }}", style = MaterialTheme.typography.bodyMedium)

                if (order.courierName.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Courier: ${order.courierName} (${order.courierPhone})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Items list
                Text(
                    text = "Ordered Items (${order.items.sumOf { it.quantity }})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                order.items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${item.quantity}x ${item.name}", style = MaterialTheme.typography.bodySmall)
                        Text("$${"%.2f".format(item.totalPrice)}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Total Amount
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total Price:", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "$${"%.2f".format(order.totalPrice)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons to update order status
                OrderStatusActions(status = order.status, onStatusChange = onStatusChange)
            }
        }
    }

    @Composable
    private fun StatusChip(status: OrderStatus) {
        val (color, text) = when (status) {
            OrderStatus.PLACED -> MaterialTheme.colorScheme.error to "PLACED"
            OrderStatus.ACCEPTED -> MaterialTheme.colorScheme.tertiary to "ACCEPTED"
            OrderStatus.PREPARING -> MaterialTheme.colorScheme.secondary to "PREPARING"
            OrderStatus.OUT_FOR_DELIVERY -> MaterialTheme.colorScheme.primary to "OUT FOR COURIER"
            OrderStatus.DELIVERED -> MaterialTheme.colorScheme.outline to "DELIVERED"
            OrderStatus.CANCELLED -> MaterialTheme.colorScheme.error to "CANCELLED"
        }

        SuggestionChip(
            onClick = {},
            label = { Text(text) },
            colors = SuggestionChipDefaults.suggestionChipColors(labelColor = color)
        )
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
                OrderStatus.PLACED -> {
                    Button(
                        onClick = { onStatusChange(OrderStatus.ACCEPTED) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Accept")
                    }
                    OutlinedButton(
                        onClick = { onStatusChange(OrderStatus.CANCELLED) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reject")
                    }
                }
                OrderStatus.ACCEPTED -> {
                    Button(
                        onClick = { onStatusChange(OrderStatus.PREPARING) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start Preparing Kitchen")
                    }
                }
                OrderStatus.PREPARING -> {
                    Button(
                        onClick = { onStatusChange(OrderStatus.OUT_FOR_DELIVERY) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Send Out for Courier")
                    }
                }
                OrderStatus.OUT_FOR_DELIVERY -> {
                    // Managers cannot set DELIVERED; state transition belongs solely to Courier
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Handed to courier. Awaiting courier delivery confirmation.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                OrderStatus.DELIVERED, OrderStatus.CANCELLED -> {
                    // Final states - no actions needed
                }
            }
        }
    }
}