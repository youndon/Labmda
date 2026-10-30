package com.zouitel.presentaion.payments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zouitel.presentaion.model.PaymentMethod
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentResultSheet(
    state: PaymentUiState,
    onDismiss: () -> Unit,
    onOpenCheckout: (PaymentCreationResult) -> Unit
) {
    val payment = state.creation ?: return
    if (!state.isResultVisible) return

    val amount = if (payment.amountCentavos > 0L) payment.amountCentavos / 100.0 else payment.amount
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Payment details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Amount", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("₱${String.format(Locale.US, "%.2f", amount)}", fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Method", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (state.selectedMethod == PaymentMethod.GCASH) "GCash" else "Cash")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Payment status", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    state.paymentStatus.replace('_', ' '),
                    fontWeight = FontWeight.SemiBold,
                    color = when (state.paymentStatus.uppercase()) {
                        "PAID", "COLLECTED" -> MaterialTheme.colorScheme.primary
                        "FAILED", "CANCELLED", "EXPIRED" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
            if (state.selectedMethod == PaymentMethod.GCASH) {
                Text("Complete the GCash checkout to finish payment. Status is updated by the payment service.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!payment.checkoutUrl.isNullOrBlank() || !payment.webCheckoutUrl.isNullOrBlank()) {
                    Button(onClick = { onOpenCheckout(payment) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue to GCash")
                    }
                }
            } else {
                Text("Pay the assigned courier or driver in cash. Collection is confirmed when delivery or the trip is completed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}

/** Handles the GCash app/web redirect identically for food, goods and rides. */
@Composable
fun PaymentEventEffect(screenModel: PaymentScreenModel, uriHandler: UriHandler) {
    LaunchedEffect(screenModel, uriHandler) {
        screenModel.events.collect { event ->
            when (event) {
                is PaymentEvent.OpenCheckout -> {
                    val opened = runCatching { uriHandler.openUri(event.checkoutUrl) }.isSuccess
                    if (!opened) {
                        event.fallbackUrl
                            ?.takeIf { it.isNotBlank() && it != event.checkoutUrl }
                            ?.let { fallback -> runCatching { uriHandler.openUri(fallback) } }
                    }
                }
            }
        }
    }
}
