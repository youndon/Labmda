package com.zouitel.presentaion.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.model.PaymentRecord

class AdminPaymentsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val model = koinScreenModel<AdminPaymentsScreenModel>()
        val state by model.state.collectAsState()
        var showRefund by remember { mutableStateOf(false) }
        var orderId by remember { mutableStateOf("") }
        var refundAmount by remember { mutableStateOf("") }
        var currency by remember { mutableStateOf("USD") }
        var reason by remember { mutableStateOf("") }
        var rangeStart by remember { mutableStateOf("0") }
        var rangeEnd by remember { mutableStateOf(System.currentTimeMillis().toString()) }
        LaunchedEffect(Unit) { model.loadLedger() }
        Scaffold(topBar = {
            TopAppBar(title = { Text("Payment ledger") }, navigationIcon = {
                IconButton(onClick = { navigator?.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { model.loadLedger() }, enabled = !state.isLoading) { Text("Refresh") }
                    Button(
                        onClick = {
                            val start = rangeStart.toLongOrNull()
                            val end = rangeEnd.toLongOrNull()
                            if (start != null && end != null) model.reconcile(start, end)
                        },
                        enabled = !state.reconciling && rangeStart.toLongOrNull() != null && rangeEnd.toLongOrNull() != null
                    ) { Text(if (state.reconciling) "Reconciling…" else "Reconcile") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(rangeStart, { rangeStart = it.filter(Char::isDigit) }, label = { Text("From epoch ms") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(rangeEnd, { rangeEnd = it.filter(Char::isDigit) }, label = { Text("To epoch ms") }, modifier = Modifier.weight(1f))
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (state.isLoading && state.payments.isEmpty()) CircularProgressIndicator()
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.payments, key = { it.orderId }) { payment ->
                        PaymentLedgerCard(payment, onRefund = { orderId = payment.orderId; currency = payment.amount.currencyCode; showRefund = true })
                    }
                }
            }
        }
        if (showRefund) {
            AlertDialog(
                onDismissRequest = { showRefund = false },
                title = { Text("Request refund") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Order: $orderId")
                        OutlinedTextField(refundAmount, { refundAmount = it.filter(Char::isDigit) }, label = { Text("Amount in minor units (blank = full refund)") })
                        OutlinedTextField(currency, { currency = it.uppercase().take(3) }, label = { Text("Currency") })
                        OutlinedTextField(reason, { reason = it }, label = { Text("Reason") })
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            model.refund(orderId, refundAmount.toLongOrNull(), currency, reason)
                            showRefund = false
                            refundAmount = ""
                            reason = ""
                        },
                        enabled = reason.isNotBlank() && (refundAmount.isBlank() || (refundAmount.toLongOrNull() ?: 0L) > 0L)
                    ) { Text("Send request") }
                },
                dismissButton = { Button(onClick = { showRefund = false }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
private fun PaymentLedgerCard(payment: PaymentRecord, onRefund: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Order ${payment.orderId}", style = MaterialTheme.typography.titleMedium)
            Text("${payment.amount.format()} • ${payment.method} • ${payment.status}")
            Text("Client: ${payment.clientId.ifBlank { "—" }}   Merchant: ${payment.merchantId.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
            Text("Provider ref: ${payment.providerPaymentId.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
            payment.breakdown?.let {
                Text("Merchant ${it.merchantShare.format()} • Courier ${it.courierShare.format()}")
                Text("Platform ${it.platformShare.format()} • Gateway ${it.gatewayFee.format()}", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onRefund, enabled = payment.status.name == "SUCCEEDED" || payment.status.name == "PARTIALLY_REFUNDED") {
                Text("Refund…")
            }
        }
    }
}