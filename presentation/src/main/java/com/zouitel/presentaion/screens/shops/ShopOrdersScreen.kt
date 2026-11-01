package com.zouitel.presentaion.screens.shops

import com.zouitel.presentaion.model.formatPhpPesos
import com.zouitel.presentaion.model.formatPhpCentavos

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

class ShopOrdersScreen() : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { ShopOrdersScreenModel() }
        val orders by screenModel.orders.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

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
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (orders.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "New customer orders will appear here when they come in.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Your order desk",
                                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    "Review incoming orders, prepare items and follow each handoff.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OrderSummaryTile(
                                    label = "New",
                                    count = orders.count { it.status == OrderStatus.PLACED },
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                )
                                OrderSummaryTile(
                                    label = "In progress",
                                    count = orders.count {
                                        it.status == OrderStatus.ACCEPTED ||
                                            it.status == OrderStatus.PREPARING ||
                                            it.status == OrderStatus.OUT_FOR_DELIVERY
                                    },
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                )
                                OrderSummaryTile(
                                    label = "Delivered",
                                    count = orders.count { it.status == OrderStatus.DELIVERED },
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                )
                            }
                        }
                    }
                    items(orders, key = { it.id }) { order ->
                        ManagerOrderCard(
                            order = order,
                            onStatusChange = { newStatus -> screenModel.updateOrderStatus(order, newStatus) }
                        )
                    }
                }
            }
        }
    }


    @Composable
    private fun OrderSummaryTile(
        label: String,
        count: Int,
        modifier: Modifier = Modifier,
        color: androidx.compose.ui.graphics.Color
    ) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = color),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
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
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Demand #${order.id.takeLast(6).uppercase()}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text(formattedDate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                StatusChip(order.status)

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text("Courier Pickup (Store)", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                Text(
                    order.pickupLocation.ifBlank { order.sourceName },
                    style = MaterialTheme.typography.bodyMedium
                )

                Text("Client Drop-off Address", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                Text(
                    order.dropoffLocation.ifBlank { "Address not provided" },
                    style = MaterialTheme.typography.bodyMedium
                )

                Text("Payment Method: ${order.paymentMethod.ifBlank { "Cash" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Payment Status: ${order.paymentStatus.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (order.cashCollectionStatus == "COLLECTED") {
                    Text(
                        "Cash collected by ${order.cashCollectedByRole.lowercase()}: ₱${formatPhpCentavos(order.cashCollectedAmountCentavos)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Merchant payable: ₱${formatPhpCentavos(order.cashMerchantOwedCentavos)} · Platform: ₱${formatPhpCentavos(order.cashPlatformOwedCentavos)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Remitted: ₱${formatPhpCentavos(order.cashRemittedCentavos)} · Remaining due: ₱${formatPhpCentavos(order.cashOutstandingRemittanceCentavos)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (order.deliveryName.isNotBlank()) {
                    Text("Assigned Delivery: ${order.deliveryName} (${order.deliveryPhone})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Medium)
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text("Ordered Products (${order.items.sumOf { it.quantity }})", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                order.items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${item.quantity}x ${item.name}", style = MaterialTheme.typography.bodySmall)
                        Text("₱${formatPhpPesos(item.totalPrice)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Total Price", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Text("₱${formatPhpPesos(order.totalPrice)}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (order.status) {
                        OrderStatus.AWAITING_PAYMENT -> { /* Payment must complete before the shop accepts the order. */ }
                        OrderStatus.PLACED -> {
                            Button(
                                onClick = { onStatusChange(OrderStatus.ACCEPTED) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Accept", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { onStatusChange(OrderStatus.CANCELLED) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reject", fontWeight = FontWeight.Bold)
                            }
                        }
                        OrderStatus.ACCEPTED -> {
                            Button(
                                onClick = { onStatusChange(OrderStatus.PREPARING) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text("Start Preparing Order", fontWeight = FontWeight.Bold)
                            }
                        }
                        OrderStatus.PREPARING -> {
                            Button(
                                onClick = { onStatusChange(OrderStatus.OUT_FOR_DELIVERY) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text("Dispatch to Delivery", fontWeight = FontWeight.Bold)
                            }
                        }
                        OrderStatus.OUT_FOR_DELIVERY -> {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Handed to courier network. Awaiting courier delivery confirmation.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                        OrderStatus.DELIVERED, OrderStatus.CANCELLED -> {}
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusChip(status: OrderStatus) {
        val (color, text) = when (status) {
            OrderStatus.AWAITING_PAYMENT -> MaterialTheme.colorScheme.error to "PAYMENT PENDING"
            OrderStatus.PLACED -> MaterialTheme.colorScheme.error to "PLACED"
            OrderStatus.ACCEPTED -> MaterialTheme.colorScheme.tertiary to "ACCEPTED"
            OrderStatus.PREPARING -> MaterialTheme.colorScheme.secondary to "PACKAGING"
            OrderStatus.OUT_FOR_DELIVERY -> MaterialTheme.colorScheme.primary to "OUT FOR COURIER"
            OrderStatus.DELIVERED -> MaterialTheme.colorScheme.outline to "DELIVERED"
            OrderStatus.CANCELLED -> MaterialTheme.colorScheme.error to "CANCELLED"
        }
        Surface(
            color = color.copy(alpha = 0.15f),
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
}