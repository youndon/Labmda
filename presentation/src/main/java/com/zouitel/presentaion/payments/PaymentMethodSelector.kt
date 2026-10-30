package com.zouitel.presentaion.payments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zouitel.presentaion.model.PaymentMethod

@Composable
fun PaymentMethodSelector(
    selectedMethod: PaymentMethod,
    onMethodSelected: (PaymentMethod) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Payment method", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedMethod == PaymentMethod.GCASH,
                onClick = { onMethodSelected(PaymentMethod.GCASH) },
                enabled = enabled,
                label = { Text("GCash") }
            )
            FilterChip(
                selected = selectedMethod == PaymentMethod.CASH,
                onClick = { onMethodSelected(PaymentMethod.CASH) },
                enabled = enabled,
                label = { Text("Cash") }
            )
        }
    }
}
