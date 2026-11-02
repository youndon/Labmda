package com.zouitel.presentaion.screens.payment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zouitel.domain.model.PaymentMethod
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Presentation-only lifecycle. A UI state never asserts that a provider received money. */
enum class PaymentUiStatus {
    READY,
    CREATING_SESSION,
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED
}

data class PaymentCheckoutUiModel(
    val title: String,
    val subtitle: String,
    val reference: String,
    val amountMinor: Long,
    val paymentMethod: PaymentMethod = PaymentMethod.GCASH,
    val status: PaymentUiStatus = PaymentUiStatus.READY,
    val checkoutUrl: String = "",
    val message: String = "",
    val simulationOnly: Boolean = false
) {
    init {
        require(amountMinor >= 0L) { "Payment amount cannot be negative." }
    }
}

data class PaymentReceiptUiModel(
    val transactionId: String,
    val reference: String,
    val description: String,
    val paymentMethod: PaymentMethod,
    val status: PaymentUiStatus,
    val amountMinor: Long,
    val dateLabel: String,
    val providerReference: String = ""
)

/**
 * Customer checkout UI shared by food, shop, delivery and ride flows.
 *
 * The host owns quote creation, payment-session creation, cash confirmation and
 * navigation. In particular, it must update SUCCEEDED only from trusted backend state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentCheckoutScreen(
    model: PaymentCheckoutUiModel,
    onBack: () -> Unit,
    onPaymentMethodSelected: (PaymentMethod) -> Unit,
    onConfirmPayment: () -> Unit,
    onOpenGcashCheckout: (String) -> Unit,
    onViewReceipt: () -> Unit,
    modifier: Modifier = Modifier
) {
    val amount = remember(model.amountMinor) { formatPhpMinor(model.amountMinor) }
    val busy = model.status == PaymentUiStatus.CREATING_SESSION ||
        model.status == PaymentUiStatus.PROCESSING

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Checkout", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = model.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = model.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (model.reference.isNotBlank()) {
                        Text(
                            text = model.reference,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Total to pay", style = MaterialTheme.typography.titleSmall)
                        Text(
                            amount,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "All amounts are shown in Philippine pesos (PHP).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "Payment method",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            PaymentMethodOption(
                title = "GCash",
                description = "Pay online using your GCash account.",
                selected = model.paymentMethod == PaymentMethod.GCASH,
                enabled = model.status == PaymentUiStatus.READY,
                icon = {
                    Icon(
                        Icons.Default.Wallet,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                onClick = { onPaymentMethodSelected(PaymentMethod.GCASH) }
            )
            PaymentMethodOption(
                title = "Cash",
                description = "Pay the assigned courier or driver in person.",
                selected = model.paymentMethod == PaymentMethod.CASH,
                enabled = model.status == PaymentUiStatus.READY,
                icon = {
                    Icon(
                        Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                onClick = { onPaymentMethodSelected(PaymentMethod.CASH) }
            )

            if (model.paymentMethod == PaymentMethod.CASH) {
                InfoPanel(
                    text = "Your payment stays pending until the assigned courier or driver confirms that cash was collected."
                )
            } else {
                InfoPanel(
                    text = "You'll continue to BDO Checkout. Select GCash on the payment page and wait for Labmda to show verified payment status."
                )
            }

            if (model.status != PaymentUiStatus.READY) {
                PaymentStatusPanel(
                    status = model.status,
                    method = model.paymentMethod,
                    message = model.message,
                    simulationOnly = model.simulationOnly
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            when (model.status) {
                PaymentUiStatus.READY -> Button(
                    onClick = onConfirmPayment,
                    enabled = model.amountMinor > 0L,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
                ) {
                    Text(
                        if (model.paymentMethod == PaymentMethod.GCASH) "Continue to BDO Checkout" else "Confirm cash order",
                        fontWeight = FontWeight.Bold
                    )
                }
                PaymentUiStatus.PENDING -> {
                    if (model.paymentMethod == PaymentMethod.GCASH && model.checkoutUrl.isNotBlank()) {
                        Button(
                            onClick = { onOpenGcashCheckout(model.checkoutUrl) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Open BDO Checkout", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onViewReceipt,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("View payment details")
                        }
                    }
                }
                PaymentUiStatus.SUCCEEDED -> Button(
                    onClick = onViewReceipt,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.ReceiptLong, contentDescription = null)
                    Text("View receipt", modifier = Modifier.padding(start = 8.dp))
                }
                PaymentUiStatus.FAILED,
                PaymentUiStatus.CANCELLED,
                PaymentUiStatus.EXPIRED -> OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Back to checkout")
                }
                PaymentUiStatus.CREATING_SESSION,
                PaymentUiStatus.PROCESSING -> Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Please wait…")
                }
            }
        }
    }
}

@Composable
private fun PaymentMethodOption(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        }
    }
}

@Composable
private fun InfoPanel(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun PaymentStatusPanel(
    status: PaymentUiStatus,
    method: PaymentMethod,
    message: String,
    simulationOnly: Boolean
) {
    val (label, detail, icon, tint) = when (status) {
        PaymentUiStatus.CREATING_SESSION -> StatusPresentation(
            "Preparing checkout", "Securely preparing your payment session.", Icons.Default.HourglassTop,
            MaterialTheme.colorScheme.primary
        )
        PaymentUiStatus.PENDING -> StatusPresentation(
            if (method == PaymentMethod.CASH) "Cash payment pending" else "Waiting for payment",
            if (method == PaymentMethod.CASH) "Payment will complete after the assigned collector confirms cash collection."
            else "Complete the GCash payment. Labmda will update this status after provider verification.",
            Icons.Default.HourglassTop, MaterialTheme.colorScheme.tertiary
        )
        PaymentUiStatus.PROCESSING -> StatusPresentation(
            "Verifying payment", "We're checking the payment result. Please don't pay again yet.",
            Icons.Default.HourglassTop, MaterialTheme.colorScheme.primary
        )
        PaymentUiStatus.SUCCEEDED -> StatusPresentation(
            "Payment successful", "Your payment has been confirmed.", Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.primary
        )
        PaymentUiStatus.FAILED -> StatusPresentation(
            "Payment failed", "The payment was not completed. You can return and try again.",
            Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error
        )
        PaymentUiStatus.CANCELLED -> StatusPresentation(
            "Payment cancelled", "No successful payment was recorded for this checkout.",
            Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error
        )
        PaymentUiStatus.EXPIRED -> StatusPresentation(
            "Checkout expired", "This payment session has expired. Return to checkout for a new quote.",
            Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error
        )
        PaymentUiStatus.READY -> return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (message.isNotBlank()) {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (simulationOnly) {
                    Text(
                        "DEVELOPMENT SIMULATION — NOT A REAL PAYMENT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private data class StatusPresentation(
    val label: String,
    val detail: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentReceiptScreen(
    receipt: PaymentReceiptUiModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val amount = remember(receipt.amountMinor) { formatPhpMinor(receipt.amountMinor) }
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
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Icon(
                if (receipt.status == PaymentUiStatus.SUCCEEDED) Icons.Default.CheckCircle else Icons.Default.ReceiptLong,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = if (receipt.status == PaymentUiStatus.SUCCEEDED) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (receipt.status == PaymentUiStatus.SUCCEEDED) "Payment confirmed" else "Payment details",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
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
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    ReceiptRow("Status", receipt.status.toReceiptLabel())
                    ReceiptRow("Payment method", receipt.paymentMethod.toReceiptLabel())
                    ReceiptRow("Description", receipt.description)
                    ReceiptRow("Reference", receipt.reference)
                    ReceiptRow("Transaction ID", receipt.transactionId)
                    if (receipt.providerReference.isNotBlank()) {
                        ReceiptRow("Provider reference", receipt.providerReference)
                    }
                    ReceiptRow("Date", receipt.dateLabel)
                }
            }
            Text(
                "Keep this receipt for your records.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.weight(1f))
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
            modifier = Modifier.weight(0.8f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value.ifBlank { "—" },
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End
        )
    }
}

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

private fun formatPhpMinor(amountMinor: Long): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale("en", "PH"))
    formatter.currency = Currency.getInstance("PHP")
    return formatter.format(BigDecimal.valueOf(amountMinor, 2))
}
