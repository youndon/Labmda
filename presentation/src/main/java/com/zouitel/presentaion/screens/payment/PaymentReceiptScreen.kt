package com.zouitel.presentaion.screens.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zouitel.domain.model.PaymentMethod
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentReceiptScreen(
    receipt: PaymentReceiptUiModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val amount = remember(receipt.amountMinor) { formatReceiptPhp(receipt.amountMinor) }
    val (title, detail, icon, tint) = when (receipt.status) {
        PaymentUiStatus.SUCCEEDED -> StatusReceipt(
            "Payment confirmed",
            "Labmda has received trusted confirmation for this payment.",
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.primary
        )
        PaymentUiStatus.PENDING -> StatusReceipt(
            "Payment pending",
            "This transaction is not confirmed yet. Check its status before attempting another payment.",
            Icons.Default.HourglassTop,
            MaterialTheme.colorScheme.tertiary
        )
        PaymentUiStatus.PROCESSING, PaymentUiStatus.CREATING_SESSION -> StatusReceipt(
            "Payment processing",
            "The payment result is still being checked. Do not submit another payment while verification is in progress.",
            Icons.Default.HourglassTop,
            MaterialTheme.colorScheme.tertiary
        )
        PaymentUiStatus.FAILED -> StatusReceipt(
            "Payment failed",
            "No successful payment has been recorded for this transaction.",
            Icons.Default.ErrorOutline,
            MaterialTheme.colorScheme.error
        )
        PaymentUiStatus.CANCELLED -> StatusReceipt(
            "Payment cancelled",
            "This payment was cancelled and is not marked as paid.",
            Icons.Default.ErrorOutline,
            MaterialTheme.colorScheme.error
        )
        PaymentUiStatus.EXPIRED -> StatusReceipt(
            "Payment session expired",
            "This checkout session is no longer valid. Request a fresh quote before trying again.",
            Icons.Default.ErrorOutline,
            MaterialTheme.colorScheme.error
        )
        PaymentUiStatus.READY -> StatusReceipt(
            "Payment not submitted",
            "No payment confirmation is available for this checkout.",
            Icons.Default.ReceiptLong,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Payment receipt", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = tint.copy(alpha = 0.12f)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.padding(18.dp).size(38.dp),
                    tint = tint
                )
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                amount,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ReceiptRow("Status", receipt.status.toReceiptLabel())
                    ReceiptRow("Payment method", receipt.paymentMethod.toReceiptLabel())
                    ReceiptRow("Description", receipt.description)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ReceiptRow("Order / ride reference", receipt.reference)
                    ReceiptRow("Transaction ID", receipt.transactionId)
                    if (receipt.providerReference.isNotBlank()) {
                        ReceiptRow("Provider reference", receipt.providerReference)
                    }
                    ReceiptRow("Date", receipt.dateLabel)
                }
            }

            Text(
                "Keep this receipt for your records. A pending receipt is not proof that payment has been collected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ReceiptRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.85f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value.ifBlank { "—" },
            modifier = Modifier.weight(1.15f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End
        )
    }
}

private data class StatusReceipt(
    val title: String,
    val detail: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: androidx.compose.ui.graphics.Color
)

private fun PaymentUiStatus.toReceiptLabel(): String = when (this) {
    PaymentUiStatus.READY -> "Not paid"
    PaymentUiStatus.CREATING_SESSION -> "Preparing"
    PaymentUiStatus.PENDING -> "Pending"
    PaymentUiStatus.PROCESSING -> "Processing"
    PaymentUiStatus.SUCCEEDED -> "Paid"
    PaymentUiStatus.FAILED -> "Failed"
    PaymentUiStatus.CANCELLED -> "Cancelled"
    PaymentUiStatus.EXPIRED -> "Expired"
}

private fun PaymentMethod.toReceiptLabel(): String = when (this) {
    PaymentMethod.GCASH -> "GCash"
    PaymentMethod.CASH -> "Cash"
}

private fun formatReceiptPhp(amountMinor: Long): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale("en", "PH"))
    formatter.currency = Currency.getInstance("PHP")
    return formatter.format(BigDecimal.valueOf(amountMinor, 2))
}
