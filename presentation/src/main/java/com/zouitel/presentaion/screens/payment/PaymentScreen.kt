package com.zouitel.presentaion.screens.payment

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.model.PayPalSession
import com.zouitel.presentaion.model.PaymentStatus
import com.zouitel.presentaion.model.StripeSession
import com.zouitel.presentaion.screens.payment.components.PayButton
import com.zouitel.presentaion.screens.payment.components.PaymentMethodSelector
import com.zouitel.presentaion.screens.payment.components.PaymentResultSheet

data class PaymentScreen(val orderId: String) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val context = LocalContext.current
        val model = koinScreenModel<PaymentScreenModel>()
        val state by model.uiState.collectAsState()
        val stripeLauncher = remember { StripePaymentLauncher() }
        val paypalLauncher = remember { PayPalPaymentLauncher() }
        val returnHandler = remember { PayPalReturnHandler() }
        var showResult by remember { mutableStateOf(false) }

        LaunchedEffect(orderId) {
            model.observeOrderPayment(orderId)
        }
        LaunchedEffect(context, orderId) {
            val uri = (context as? Activity)?.intent?.data ?: return@LaunchedEffect
            returnHandler.parse(uri)?.let { result ->
                if (result.orderId == orderId) {
                    model.handleProviderReturn(orderId, "PAYPAL", result.sessionId, result.resultToken)
                }
            }
        }
        LaunchedEffect(state.payment?.status) {
            if (state.payment?.status in setOf(
                    PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.CANCELLED,
                    PaymentStatus.REFUNDED, PaymentStatus.PARTIALLY_REFUNDED
                )) showResult = true
        }
        LaunchedEffect(model) {
            model.events.collect { event ->
                when (event) {
                    is PaymentEvent.CheckoutReady -> runCatching {
                        when (val session = event.session) {
                            is StripeSession -> stripeLauncher.launch(context, session)
                            is PayPalSession -> paypalLauncher.launch(context, session)
                        }
                    }.onFailure { /* State remains server-driven; errors can be retried from this screen. */ }
                    PaymentEvent.CashPaymentRequested -> showResult = true
                    is PaymentEvent.Error -> Unit
                }
            }
        }

        Scaffold(topBar = {
            TopAppBar(
                title = { Text("Payment") },
                navigationIcon = {
                    IconButton(onClick = { navigator?.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Choose how you want to pay", style = MaterialTheme.typography.titleLarge)
                Text("Order: $orderId", style = MaterialTheme.typography.bodyMedium)
                PaymentMethodSelector(
                    selected = state.selectedMethod,
                    onSelect = model::selectMethod,
                    enabled = !state.isSubmitting
                )
                state.payment?.let { payment ->
                    Text(
                        "Server payment status: ${payment.status}",
                        color = if (payment.status == PaymentStatus.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Amount: ${payment.amount.format()}", style = MaterialTheme.typography.titleMedium)
                }
                state.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Button(onClick = model::clearError) { Text("Dismiss") }
                }
                PayButton(
                    method = state.selectedMethod,
                    isLoading = state.isSubmitting,
                    onClick = { model.pay(orderId) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Payment confirmation, totals, fees, refunds and payout splits are verified by the server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showResult) {
            PaymentResultSheet(payment = state.payment, onDismiss = { showResult = false })
        }
    }
}