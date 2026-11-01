package com.zouitel.presentaion.screens.payment

import com.zouitel.presentaion.model.formatPhpCentavos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PaymentCenterScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { PaymentCenterScreenModel() }
        val payments by screenModel.payments.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        val error by screenModel.error.collectAsState()

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Payment Center", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { PaymentActivityHero(payments = payments) }
                when {
                    isLoading -> item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 44.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text("Loading your transactions", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    payments.isEmpty() -> item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
                            Text(if (error == null) "No payment activity yet" else "Payment activity unavailable", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                error ?: "Your GCash and cash order or ride payments will appear here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Transactions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(
                                    "Select a transaction to review its payment, refund and settlement details.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(payments, key = { it.id }) { payment -> PaymentActivityCard(payment) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentActivityHero(payments: List<PaymentActivityItem>) {
    val successful = payments.count { it.status.uppercase() in setOf("PAID", "COLLECTED", "REFUNDED") }
    val pending = payments.count { it.status.uppercase() !in setOf("PAID", "COLLECTED", "REFUNDED", "FAILED", "CANCELLED", "EXPIRED", "INITIATION_FAILED") }
    val failed = payments.count { it.status.uppercase() in setOf("FAILED", "CANCELLED", "EXPIRED", "INITIATION_FAILED") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    )
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                Text("PAYMENT OVERVIEW", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
            Text("Your money, clearly tracked.", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            Text(
                "Review GCash and cash payments for your orders and rides.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.86f)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentSummaryMetric("ALL", payments.size)
                PaymentSummaryMetric("SUCCESSFUL", successful)
                PaymentSummaryMetric("PENDING", pending)
                PaymentSummaryMetric("ISSUES", failed)
            }
        }
    }
}

@Composable
private fun RowScope.PaymentSummaryMetric(label: String, count: Int) {
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(count.toString(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f), maxLines = 1)
    }
}

@Composable
private fun PaymentActivityCard(payment: PaymentActivityItem) {
    var expanded by remember(payment.id) { mutableStateOf(false) }
    val date = rememberPaymentDate(payment.createdAtTimestamp)
    val amount = formatPhpCentavos(payment.amountCentavos)
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    payment.entityType.ifBlank { "PAYMENT" }.replace('_', ' '),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "₱$amount",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(payment.channelCode.ifBlank { payment.provider.ifBlank { "Payment" } }, style = MaterialTheme.typography.bodyMedium)
                PaymentStatusLabel(payment.status)
            }
            Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (expanded) "Hide transaction details ▲" else "View transaction details ▼",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (expanded) {
                Text("Transaction reference: ${payment.id}", style = MaterialTheme.typography.bodySmall)
                Text("Provider: ${payment.provider.ifBlank { "Not available" }}", style = MaterialTheme.typography.bodySmall)
                Text("Last updated: ${rememberPaymentDate(payment.updatedAtTimestamp)}", style = MaterialTheme.typography.bodySmall)
                if (payment.failureCode.isNotBlank()) {
                    Text("Payment issue: ${paymentFailureExplanation(payment.failureCode)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("Provider code: ${payment.failureCode}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (payment.refundedCentavos > 0L || payment.pendingRefundCentavos > 0L || payment.refunds.isNotEmpty()) {
                    Text("Refunds", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (payment.refundedCentavos > 0L) Text("Refunded: ₱${formatPhpCentavos(payment.refundedCentavos)}")
                    if (payment.pendingRefundCentavos > 0L) Text("Refund pending: ₱${formatPhpCentavos(payment.pendingRefundCentavos)}")
                    if (payment.refundStatus.isNotBlank()) Text("Refund status: ${payment.refundStatus.replace('_', ' ')}")
                    payment.refunds.forEach { refund ->
                        Text(
                            "Refund ${refund.id.takeLast(10)} · ₱${formatPhpCentavos(refund.amountCentavos)} · ${refund.status.replace('_', ' ')}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (refund.failureCode.isNotBlank()) Text("Refund issue: ${paymentFailureExplanation(refund.failureCode)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        when (refund.walletSettlementStatus.uppercase()) {
                            "POSTED" -> Text("Wallet accounting: refund entry posted", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            "PENDING", "PROCESSING", "FAILED" -> Text("Wallet accounting: ${refund.walletSettlementStatus.replace('_', ' ')}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (payment.settlementStatus.isNotBlank()) {
                    Text("Xendit settlement: ${payment.settlementStatus.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                }
                if (payment.earningsSettlementStatus.isNotBlank() || payment.earningsPendingStatus.isNotBlank()) {
                    Text(
                        "Earnings reconciliation: ${(payment.earningsSettlementStatus.ifBlank { payment.earningsPendingStatus }).replace('_', ' ')}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentStatusLabel(status: String) {
    val normalized = status.uppercase()
    val color = when (normalized) {
        "PAID", "COLLECTED", "REFUNDED" -> MaterialTheme.colorScheme.primary
        "FAILED", "CANCELLED", "EXPIRED", "INITIATION_FAILED" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    Text(normalized.replace('_', ' '), color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
}

private fun paymentFailureExplanation(code: String): String {
    val normalized = code.uppercase()
    return when {
        normalized.contains("INSUFFICIENT") -> "The payment provider reported insufficient funds. Check your GCash balance and try again."
        normalized.contains("EXPIRED") -> "The payment request expired before it was completed. Start a new checkout."
        normalized.contains("CANCEL") -> "The payment was cancelled before completion."
        normalized.contains("ACCOUNT") -> "The destination or payment account could not be verified. Review the account details."
        normalized.contains("LIMIT") -> "The provider limit was reached. Try a smaller amount or contact support."
        else -> "The payment provider could not complete this transaction. Check the provider app and try again later."
    }
}

@Composable
private fun rememberPaymentDate(timestamp: Long): String {
    val formatter = remember { SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()) }
    return remember(timestamp) {
        if (timestamp > 0L) formatter.format(Date(timestamp)) else "Date unavailable"
    }
}
