package com.zouitel.presentaion.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.model.OrderStatus

data class LiveOrderTrackingScreen(val orderId: String) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { LiveOrderTrackingScreenModel(orderId) }
        val order by screenModel.order.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Track Order") },
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
            } else if (order == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Order not found or has been removed.")
                }
            } else {
                val currentOrder = order!!

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Order #${currentOrder.id.takeLast(6).uppercase()}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "From: ${currentOrder.sourceName}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Payment: ${currentOrder.paymentMethod.ifBlank { "Cash on Delivery" }}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    // Status Timeline Stepper
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Order Status Progress",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                OrderStatusStepper(currentStatus = currentOrder.status)
                            }
                        }
                    }

                    // Delivery Information Section
                    if (currentOrder.status == OrderStatus.OUT_FOR_DELIVERY || currentOrder.status == OrderStatus.DELIVERED || currentOrder.deliveryName.isNotBlank()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "Delivery Assigned",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                        )
                                        Text(
                                            text = currentOrder.deliveryName.ifBlank { "Searching for nearby courier..." },
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        if (currentOrder.deliveryPhone.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Phone,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = currentOrder.deliveryPhone,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                            }
                                        }
                                    }
                                    Icon(
                                        imageVector = Icons.Default.DirectionsBike,
                                        contentDescription = "Delivery Icon",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Delivery Address Details
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Delivery Address", style = MaterialTheme.typography.titleSmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = currentOrder.clientAddress.ifBlank { "Address not provided" },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    // Itemized Order Summary
                    item {
                        Text("Ordered Items", style = MaterialTheme.typography.titleMedium)
                    }

                    items(currentOrder.items, key = { it.id }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${item.quantity}x ${item.name}", style = MaterialTheme.typography.bodyMedium)
                            Text("$${"%.2f".format(item.totalPrice)}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Delivery Fee", style = MaterialTheme.typography.bodyMedium)
                            Text("$${"%.2f".format(currentOrder.deliveryFee)}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Total", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "$${"%.2f".format(currentOrder.totalPrice)}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Allow Client Cancellation if Order is still in PLACED state
                    if (currentOrder.status == OrderStatus.PLACED) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { screenModel.cancelOrder() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Cancel Order")
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun OrderStatusStepper(currentStatus: OrderStatus) {
        val steps = listOf(
            OrderStatus.PLACED to "Placed",
            OrderStatus.ACCEPTED to "Confirmed",
            OrderStatus.PREPARING to "Kitchen",
            OrderStatus.OUT_FOR_DELIVERY to "Out for Delivery",
            OrderStatus.DELIVERED to "Delivered"
        )

        if (currentStatus == OrderStatus.CANCELLED) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "This order was cancelled.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            return
        }

        val currentStepIndex = steps.indexOfFirst { it.first == currentStatus }.let {
            if (it == -1) 0 else it
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            steps.forEachIndexed { index, (_, label) ->
                val isCompleted = index <= currentStepIndex
                val isCurrent = index == currentStepIndex

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isCompleted -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isCompleted) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = label,
                        style = if (isCurrent) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        color = if (isCompleted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}