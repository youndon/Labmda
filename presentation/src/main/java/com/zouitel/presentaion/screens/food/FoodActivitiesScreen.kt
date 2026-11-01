package com.zouitel.presentaion.screens.food

import com.zouitel.presentaion.model.formatPhpPesos
import com.zouitel.presentaion.model.formatPhpCentavos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.LiveOrderTrackingScreen
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
import java.text.SimpleDateFormat
import java.util.*

class FoodActivitiesScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { FoodActivitiesScreenModel() }
        val orders by screenModel.orders.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        val refundNotice by screenModel.refundNotice.collectAsState()

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Food orders", fontWeight = FontWeight.Bold) },
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
                        .padding(paddingValues)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.ReceiptLong,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Text("Your food orders will appear here", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text(
                            "Discover a restaurant, place an order and follow every update from preparation to delivery.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Your meals, from kitchen to doorstep", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text("Keep track of restaurant preparation, courier progress and payment updates.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FoodActivitySummaryTile("Active", orders.count { it.status == OrderStatus.AWAITING_PAYMENT || it.status == OrderStatus.PLACED || it.status == OrderStatus.ACCEPTED || it.status == OrderStatus.PREPARING || it.status == OrderStatus.OUT_FOR_DELIVERY }, Modifier.weight(1f), MaterialTheme.colorScheme.primaryContainer)
                                FoodActivitySummaryTile("Delivered", orders.count { it.status == OrderStatus.DELIVERED }, Modifier.weight(1f), MaterialTheme.colorScheme.secondaryContainer)
                                FoodActivitySummaryTile("Cancelled", orders.count { it.status == OrderStatus.CANCELLED }, Modifier.weight(1f), MaterialTheme.colorScheme.surfaceContainerHigh)
                            }
                        }
                    }
                    item {
                        refundNotice?.let {
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(14.dp), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
                                Text(it, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    item { Text("Recent orders", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    items(orders, key = { it.id }) { order ->
                        ClientOrderHistoryCard(
                            order = order,
                            onTrackOrder = { navigator?.push(LiveOrderTrackingScreen(order.id)) },
                            onCancelOrder = { screenModel.cancelOrder(order.id) },
                            onRequestRefund = { screenModel.requestRefund(order.id) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun FoodActivitySummaryTile(
        label: String,
        count: Int,
        modifier: Modifier = Modifier,
        containerColor: androidx.compose.ui.graphics.Color
    ) {
        Card(modifier = modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = containerColor), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(count.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }

    @Composable
    private fun ClientOrderHistoryCard(
        order: Order,
        onTrackOrder: () -> Unit,
        onCancelOrder: () -> Unit,
        onRequestRefund: () -> Unit
    ) {
        val formattedDate = remember(order.createdAtTimestamp) {
            val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            sdf.format(Date(order.createdAtTimestamp))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = order.sourceName.ifBlank { "Restaurant" },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Order #${order.id.takeLast(6).uppercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(text = formattedDate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(modifier = Modifier.height(10.dp))
                StatusChip(status = order.status)

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Delivery details", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Pickup", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(order.pickupLocation.ifBlank { order.sourceName }, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Drop-off", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(order.dropoffLocation.ifBlank { "Not provided" }, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Payment method", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(order.paymentMethod.ifBlank { "Cash on Delivery" }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Payment status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(order.paymentStatus.replace('_', ' '), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (order.deliveryName.isNotBlank()) {
                        Text("Courier · ${order.deliveryName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Ordered Items Container
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Items (${order.items.sumOf { item -> item.quantity }})",
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
                                Text("₱${formatPhpPesos(item.totalPrice)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        "₱${formatPhpPesos(order.totalPrice)}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onTrackOrder,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Track Status")
                    }

                    if (order.paymentMethod.equals("GCASH", ignoreCase = true) &&
                        order.paymentStatus.equals("PAID", ignoreCase = true) &&
                        order.status != OrderStatus.CANCELLED) {
                        OutlinedButton(
                            onClick = onRequestRefund,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Request refund") }
                    }
                    if (order.status == OrderStatus.PLACED && !order.paymentStatus.equals("PAID", ignoreCase = true)) {
                        OutlinedButton(
                            onClick = onCancelOrder,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
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
        val (color, containerColor, text) = when (status) {
            OrderStatus.AWAITING_PAYMENT -> Triple(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer, "PAYMENT PENDING")
            OrderStatus.PLACED -> Triple(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer, "ORDER PLACED")
            OrderStatus.ACCEPTED -> Triple(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.secondaryContainer, "CONFIRMED")
            OrderStatus.PREPARING -> Triple(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, "IN KITCHEN")
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
}