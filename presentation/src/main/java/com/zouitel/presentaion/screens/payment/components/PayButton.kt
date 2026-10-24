package com.zouitel.presentaion.screens.payment.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zouitel.presentaion.model.PaymentMethodType

@Composable
fun PayButton(
    method: PaymentMethodType,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled && !isLoading
    ) {
        if (isLoading) CircularProgressIndicator()
        else when (method) {
                PaymentMethodType.CASH -> Text("Place order")
                PaymentMethodType.STRIPE ->  Text("Pay by card")
                PaymentMethodType.PAYPAL -> Text("Continue with PayPal")
                else -> {
                    Text("Pay by card")
                }
            }
    }
}