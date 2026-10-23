package com.zouitel.presentaion.screens.payment.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zouitel.presentaion.model.PaymentMethod

@Composable
fun PayButton(
    method: PaymentMethod,
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
        else Text(
            when (method) {
                PaymentMethod.CASH -> "Place order"
                PaymentMethod.STRIPE -> "Pay by card"
                PaymentMethod.PAYPAL -> "Continue with PayPal"
            }
        )
    }
}