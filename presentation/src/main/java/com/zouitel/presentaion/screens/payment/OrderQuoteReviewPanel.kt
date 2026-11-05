package com.zouitel.presentaion.screens.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun OrderQuoteReviewPanel(
    quote: Map<String, Any?>,
    isSubmitting: Boolean,
    onConfirm: () -> Unit,
    onEdit: () -> Unit
) {
    val breakdown = quote["breakdown"] as? Map<*, *> ?: emptyMap<Any, Any>()
    val route = quote["route"] as? Map<*, *> ?: emptyMap<Any, Any>()
    fun money(key: String): String {
        val minor = (breakdown[key] as? Number)?.toLong() ?: 0L
        return "₱%.2f".format(minor / 100.0)
    }
    val distanceKm = ((route["distanceMeters"] as? Number)?.toDouble() ?: 0.0) / 1000.0
    val durationMin = (((route["durationSeconds"] as? Number)?.toDouble() ?: 0.0) / 60.0).toInt()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Server quote", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Route %.1f km · about %d min".format(distanceKm, durationMin),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            QuoteRow("Items subtotal", money("subtotalMinor"))
            QuoteRow("Delivery fee", money("deliveryFeeMinor"))
            val discount = (breakdown["discountMinor"] as? Number)?.toLong() ?: 0L
            if (discount > 0L) QuoteRow("Discount", "−₱%.2f".format(discount / 100.0))
            val providerFee = (breakdown["providerFeeMinor"] as? Number)?.toLong() ?: 0L
            val subtotal = (breakdown["subtotalMinor"] as? Number)?.toLong() ?: 0L
            val delivery = (breakdown["deliveryFeeMinor"] as? Number)?.toLong() ?: 0L
            val discountTotal = (breakdown["discountMinor"] as? Number)?.toLong() ?: 0L
            val customerTotal = (breakdown["customerTotalMinor"] as? Number)?.toLong() ?: 0L
            if (providerFee > 0L && customerTotal > subtotal + delivery - discountTotal) {
                QuoteRow("GCash processing fee", money("providerFeeMinor"))
            }
            QuoteRow("Payment method", if (quote["paymentMethod"] == "GCASH") "GCash" else "Cash")
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total to pay", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(money("customerTotalMinor"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "This amount was calculated by Labmda using the current admin pricing. The quote expires in five minutes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onEdit, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth()) {
                Text("Edit order")
            }
            Button(onClick = onConfirm, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth()) {
                Text(if (isSubmitting) "Please wait…" else "Confirm order")
            }
        }
    }
}

@Composable
private fun QuoteRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
