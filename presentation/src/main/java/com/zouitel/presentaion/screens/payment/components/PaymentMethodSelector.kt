package com.zouitel.presentaion.screens.payment.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zouitel.presentaion.model.PaymentMethod

@Composable
fun PaymentMethodSelector(
    selected: PaymentMethod,
    onSelect: (PaymentMethod) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            PaymentMethod.CASH to "Cash on delivery",
            PaymentMethod.STRIPE to "Credit or debit card (Stripe)",
            PaymentMethod.PAYPAL to "PayPal"
        ).forEach { (method, label) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                onClick = { onSelect(method) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected == method) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == method, onClick = { onSelect(method) }, enabled = enabled)
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}