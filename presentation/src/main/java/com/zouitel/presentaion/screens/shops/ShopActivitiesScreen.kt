package com.zouitel.presentaion.screens.shops

import com.zouitel.presentaion.model.formatPhpPesos
import com.zouitel.presentaion.model.formatPhpCentavos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

class ShopActivitiesScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { ShopActivitiesScreenModel() }
        val activities by screenModel.activities.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        val refundNotice by screenModel.refundNotice.collectAsState()

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Shop activity", fontWeight = FontWeight.Bold) },
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
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(28.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.padding(20.dp).size(34.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Text("Your shop orders will appear here", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text("Once you place an order with a store, you can follow its progress and manage available order actions from this screen.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Your orders, all in one place", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text("Track store preparation, delivery progress and payment updates.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActivitySummaryTile("Active", activities.count { it.status == OrderStatus.PLACED || it.status == OrderStatus.ACCEPTED || it.status == OrderStatus.PREPARING || it.status == OrderStatus.OUT_FOR_DELIVERY || it.status == OrderStatus.AWAITING_PAYMENT }, Modifier.weight(1f), MaterialTheme.colorScheme.primaryContainer)
                                ActivitySummaryTile("Delivered", activities.count { it.status == OrderStatus.DELIVERED }, Modifier.weight(1f), MaterialTheme.colorScheme.secondaryContainer)
                                ActivitySummaryTile("Cancelled", activities.count { it.status == OrderStatus.CANCELLED }, Modifier.weight(1f), MaterialTheme.colorScheme.surfaceContainerHigh)
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
                    items(activities, key = { it.id }) { order ->
                        ClientActivityCard(
                            order = order,
                            onTrackOrder = { navigator?.push(LiveOrderTrackingScreen(order.id)) },
                            onCancelOrder = { screenModel.cancelShopOrder(order.id) },
                            onRequestRefund = { screenModel.requestRefund(order.id) }
                        )
                    }
                }
            }
        }
    }


    @Composable
    private fun ActivitySummaryTile(
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
    private fun ClientActivityCard(order: Order, onTrackOrder: () -> Unit, onCancelOrder: () -> Unit, onRequestRefund: () -> Unit) {
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
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(order.sourceName.ifBlank { "Store" }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text(formattedDate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Text("Order #${order.id.takeLast(6).uppercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                StatusChip(order.status)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Delivery address", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(order.dropoffLocation.ifBlank { "Address not provided" }, style = MaterialTheme.typography.bodyMedium)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Payment method", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(order.paymentMethod.ifBlank { "Cash on Delivery" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Payment status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(order.paymentStatus.replace('_', ' '), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text("Products (${order.items.sumOf { it.quantity }})", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
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
                    Button(
                        onClick = onTrackOrder,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Track Status", fontWeight = FontWeight.Bold)
                    }

                    if (order.paymentMethod.equals("GCASH", ignoreCase = true) &&
                        order.paymentStatus.equals("PAID", ignoreCase = true) &&
                        order.status != OrderStatus.CANCELLED) {
                        OutlinedButton(
                            onClick = onRequestRefund,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) { Text("Request refund", fontWeight = FontWeight.Bold) }
                    }
                    if (order.status == OrderStatus.PLACED && !order.paymentStatus.equals("PAID", ignoreCase = true)) {
                        OutlinedButton(
                            onClick = onCancelOrder,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cancel", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusChip(status: OrderStatus) {
        val (color, text) = when (status) {
            OrderStatus.AWAITING_PAYMENT -> MaterialTheme.colorScheme.error to "PAYMENT PENDING"
            OrderStatus.PLACED -> MaterialTheme.colorScheme.tertiary to "ORDER PLACED"
            OrderStatus.ACCEPTED -> MaterialTheme.colorScheme.secondary to "CONFIRMED BY STORE"
            OrderStatus.PREPARING -> MaterialTheme.colorScheme.primary to "IN PACKAGING"
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