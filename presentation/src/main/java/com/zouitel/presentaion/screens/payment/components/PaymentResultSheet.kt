package com.zouitel.presentaion.screens.payment.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zouitel.presentaion.model.PaymentRecord
import com.zouitel.presentaion.model.PaymentStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentResultSheet(
    payment: PaymentRecord?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Payment status", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = when {
                    payment?.method == com.zouitel.presentaion.model.PaymentMethod.CASH &&
                        payment?.status == PaymentStatus.PENDING ->
                        "Cash on delivery selected. Payment remains pending until cash is collected."
                    payment?.status == PaymentStatus.SUCCEEDED -> "Payment confirmed by the server."
                    payment?.status in setOf(PaymentStatus.PENDING, PaymentStatus.PROCESSING, PaymentStatus.REQUIRES_ACTION) ->
                        "Your payment is being processed."
                    payment?.status == PaymentStatus.FAILED -> "The payment failed. Please try again."
                    payment?.status == PaymentStatus.CANCELLED -> "Payment was cancelled."
                    payment?.status == PaymentStatus.REFUNDED -> "This payment has been refunded."
                    payment?.status == PaymentStatus.PARTIALLY_REFUNDED -> "This payment was partially refunded."
                    else -> "Waiting for the payment provider to confirm the result."
                },
                style = MaterialTheme.typography.bodyLarge
            )
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}
