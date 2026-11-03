package com.zouitel.presentaion.screens.shops

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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

class ShopOrdersScreen() : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { ShopOrdersScreenModel() }
        val orders by screenModel.orders.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        var searchQuery by remember { mutableStateOf("") }
        var selectedFilter by remember { mutableStateOf("All") }

        val filters = listOf("All", "Needs attention", "Preparing", "Out for delivery", "Completed", "Cancelled")
        val filteredOrders = remember(orders, searchQuery, selectedFilter) {
            val query = searchQuery.trim().lowercase()
            orders.filter { order ->
                val matchesSearch = query.isBlank() || listOf(
                    order.id,
                    order.clientId,
                    order.sourceName,
                    order.pickupLocation,
                    order.dropoffLocation,
                    order.deliveryName,
                    order.deliveryPhone,
                    order.items.joinToString(" ") { it.name }
                ).any { it.lowercase().contains(query) }
                val matchesStatus = when (selectedFilter) {
                    "Needs attention" -> order.status == OrderStatus.PENDING_PAYMENT || order.status == OrderStatus.PLACED
                    "Preparing" -> order.status == OrderStatus.ACCEPTED || order.status == OrderStatus.PREPARING
                    "Out for delivery" -> order.status == OrderStatus.OUT_FOR_DELIVERY
                    "Completed" -> order.status == OrderStatus.DELIVERED
                    "Cancelled" -> order.status == OrderStatus.CANCELLED
                    else -> true
                }
                matchesSearch && matchesStatus
            }
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Shop orders", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(paddingValues),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }
                orders.isEmpty() -> {
                    EmptyOrdersState(
                        title = "No shop orders yet",
                        message = "New customer orders will appear here when your shop receives them.",
                        modifier = Modifier.fillMaxSize().padding(paddingValues)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(paddingValues),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    "Keep every order moving",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${filteredOrders.size} of ${orders.size} orders",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    placeholder = { Text("Search order, customer, address or item") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Search, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            TextButton(onClick = { searchQuery = "" }) { Text("Clear") }
                                        }
                                    }
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    filters.forEach { filter ->
                                        FilterChip(
                                            selected = selectedFilter == filter,
                                            onClick = { selectedFilter = filter },
                                            label = { Text(filter) }
                                        )
                                    }
                                }
                            }
                        }

                        if (filteredOrders.isEmpty()) {
                            item {
                                EmptyOrdersState(
                                    title = "No matching orders",
                                    message = "Try another search term or choose a different order status.",
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp)
                                )
                            }
                        } else {
                            items(filteredOrders, key = { it.id }) { order ->
                                ManagerOrderCard(
                                    order = order,
                                    onStatusChange = { newStatus ->
                                        screenModel.updateOrderStatus(order, newStatus)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EmptyOrdersState(title: String, message: String, modifier: Modifier = Modifier) {
        Box(modifier = modifier.padding(28.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }

    @Composable
    private fun ManagerOrderCard(order: Order, onStatusChange: (OrderStatus) -> Unit) {
        val formattedDate = remember(order.createdAtTimestamp) {
            val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            sdf.format(Date(order.createdAtTimestamp))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "Order #${order.id.takeLast(6).uppercase()}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(formattedDate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusChip(order.status)
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text("PICKUP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(order.pickupLocation.ifBlank { order.sourceName }, style = MaterialTheme.typography.bodyMedium)
                Text("CUSTOMER DROP-OFF", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(order.dropoffLocation.ifBlank { "Address not provided" }, style = MaterialTheme.typography.bodyMedium)

                Text(
                    "Payment: ${order.paymentMethod.ifBlank { "Cash" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (order.deliveryName.isNotBlank()) {
                    Text(
                        "Courier: ${order.deliveryName}${order.deliveryPhone.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Medium
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    "ITEMS · ${order.items.sumOf { it.quantity }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                order.items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${item.quantity} × ${item.name}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text("₱${"%.2f".format(item.totalPrice)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Order total", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "₱${"%.2f".format(order.totalPrice)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                when (order.status) {
                    OrderStatus.PENDING_PAYMENT -> {
                        Text("Waiting for customer payment confirmation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OrderStatus.PLACED -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onStatusChange(OrderStatus.ACCEPTED) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Accept", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { onStatusChange(OrderStatus.CANCELLED) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Reject", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    OrderStatus.ACCEPTED -> {
                        Button(
                            onClick = { onStatusChange(OrderStatus.PREPARING) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("Start preparing", fontWeight = FontWeight.Bold) }
                    }
                    OrderStatus.PREPARING -> {
                        Button(
                            onClick = { onStatusChange(OrderStatus.OUT_FOR_DELIVERY) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("Dispatch to courier", fontWeight = FontWeight.Bold) }
                    }
                    OrderStatus.OUT_FOR_DELIVERY -> {
                        Text(
                            "Handed to the courier network. Waiting for delivery confirmation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OrderStatus.DELIVERED, OrderStatus.CANCELLED -> Unit
                }
            }
        }
    }

    @Composable
    private fun StatusChip(status: OrderStatus) {
        val (color, text) = when (status) {
            OrderStatus.PENDING_PAYMENT -> MaterialTheme.colorScheme.tertiary to "PAYMENT PENDING"
            OrderStatus.PLACED -> MaterialTheme.colorScheme.error to "PLACED"
            OrderStatus.ACCEPTED -> MaterialTheme.colorScheme.tertiary to "ACCEPTED"
            OrderStatus.PREPARING -> MaterialTheme.colorScheme.secondary to "PREPARING"
            OrderStatus.OUT_FOR_DELIVERY -> MaterialTheme.colorScheme.primary to "OUT FOR DELIVERY"
            OrderStatus.DELIVERED -> MaterialTheme.colorScheme.outline to "DELIVERED"
            OrderStatus.CANCELLED -> MaterialTheme.colorScheme.error to "CANCELLED"
        }
        Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}
