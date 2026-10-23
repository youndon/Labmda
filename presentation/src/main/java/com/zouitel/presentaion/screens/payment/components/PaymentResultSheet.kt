package com.zouitel.presentaion.screens.payment.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zouitel.presentaion.model.PaymentRecord
import com.zouitel.presentaion.model.PaymentStatus

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
                text = when (payment?.status) {
                    PaymentStatus.SUCCEEDED -> "Payment confirmed by the server."
                    PaymentStatus.PENDING, PaymentStatus.PROCESSING, PaymentStatus.REQUIRES_ACTION -> "Your payment is being processed."
                    PaymentStatus.FAILED -> "The payment failed. Please try again."
                    PaymentStatus.CANCELLED -> "Payment was cancelled."
                    PaymentStatus.REFUNDED -> "This payment has been refunded."
                    PaymentStatus.PARTIALLY_REFUNDED -> "This payment was partially refunded."
                    else -> "Waiting for the payment provider to confirm the result."
                },
                style = MaterialTheme.typography.bodyLarge
            )
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}