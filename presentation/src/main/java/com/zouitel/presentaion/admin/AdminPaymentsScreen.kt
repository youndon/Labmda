package com.zouitel.presentaion.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.screens.wallet.AdminPaymentsScreenModel
import com.zouitel.presentaion.screens.wallet.peso

class AdminPaymentsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable override fun Content() {
        val navigator = LocalNavigator.current
        val model = koinScreenModel<AdminPaymentsScreenModel>()
        val state by model.state.collectAsState()
        val dashboard = state.dashboard
        var reviewNote by remember { mutableStateOf("") }
        var rejectionNote by remember { mutableStateOf("") }

        Scaffold(topBar = {
            TopAppBar(
                title = { Text("Payments, Wallets & Payouts", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { navigator?.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.isLoading) CircularProgressIndicator()
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                OutlinedButton(onClick = model::refresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh") }

                Text("Wallet snapshot totals", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Metric("Wallets", dashboard.walletCount.toString())
                Metric("Available earnings", "₱" + peso(dashboard.availableCentavos))
                Metric("Pending earnings", "₱" + peso(dashboard.pendingCentavos))
                Metric("Cash owed by collectors", "₱" + peso(dashboard.cashOwedCentavos))
                Metric("Platform cash due", "₱" + peso(dashboard.cashPlatformOwedCentavos))
                Metric("Confirmed payouts", "₱" + peso(dashboard.paidOutCentavos))
                Metric("Recorded GCash commissions", "₱" + peso(dashboard.platformCommissionCentavos))
                Metric("Refund recovery outstanding", "₱" + peso(dashboard.refundDebtCentavos))

                Text("Pending payout requests", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val pendingRequests = dashboard.payoutRequests.filter { it.status == "PENDING_ADMIN_APPROVAL" }
                if (pendingRequests.isEmpty()) {
                    Text("No payout requests are waiting for approval.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    pendingRequests.forEach { payout ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Request " + payout.id, fontWeight = FontWeight.Bold)
                                Text("Participant: " + payout.userId)
                                Text("Requested: ₱" + peso(payout.amountCentavos))
                                Text("Destination: " + payout.accountChannelCode + " · " + payout.maskedAccountNumber)
                                Text("Submitted: " + payout.createdAtTimestamp.toString())
                                OutlinedTextField(
                                    rejectionNote,
                                    { rejectionNote = it },
                                    label = { Text("Rejection note (optional)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !state.isSubmitting
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { model.approvePayoutRequest(payout.id) },
                                        enabled = !state.isSubmitting,
                                        modifier = Modifier.weight(1f)
                                    ) { Text(if (state.isSubmitting) "Working…" else "Approve & send") }
                                    OutlinedButton(
                                        onClick = { model.rejectPayoutRequest(payout.id, rejectionNote) },
                                        enabled = !state.isSubmitting,
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Reject") }
                                }
                                Text(
                                    "Approval submits the requested amount to Xendit. Verify the recipient and amount before approving.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Text("Payout request history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (dashboard.payoutRequests.isEmpty()) Text("No payout requests recorded.")
                dashboard.payoutRequests.filter { it.status != "PENDING_ADMIN_APPROVAL" }.forEach { payout ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Request " + payout.id, fontWeight = FontWeight.Bold)
                            Text("Participant: " + payout.userId)
                            Text("₱" + peso(payout.amountCentavos) + " · " + payout.status.replace('_', ' '))
                            if (payout.maskedAccountNumber.isNotBlank()) Text("Destination: " + payout.maskedAccountNumber)
                            if (payout.rejectionNote.isNotBlank()) Text("Admin note: " + payout.rejectionNote, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Text("Payout-account verification", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (dashboard.payoutAccountReviews.isEmpty()) Text("No payout accounts queued for review.")
                dashboard.payoutAccountReviews.forEach { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.userId, fontWeight = FontWeight.Bold)
                            Text(item.accountHolderName + " · " + item.maskedAccountNumber)
                            Text("Status: " + item.status.replace('_', ' '))
                            if (item.reviewNote.isNotBlank()) Text(item.reviewNote)
                            if (item.status == "PENDING_REVIEW") {
                                OutlinedTextField(
                                    reviewNote, { reviewNote = it },
                                    label = { Text("Verification note") },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !state.isSubmitting
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { model.review(item.userId, true, reviewNote) },
                                        enabled = !state.isSubmitting,
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Verify") }
                                    OutlinedButton(
                                        onClick = { model.review(item.userId, false, reviewNote) },
                                        enabled = !state.isSubmitting,
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Reject") }
                                }
                            }
                        }
                    }
                }

                Text("Payout transfer history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (dashboard.payouts.isEmpty()) Text("No payout transfers recorded.")
                dashboard.payouts.forEach { payout ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Payout " + payout.id, fontWeight = FontWeight.Bold)
                            Text("Owner: " + payout.userId)
                            Text(payout.maskedAccountNumber + " · ₱" + peso(payout.amountCentavos))
                            Text("Provider status: " + payout.status.replace('_', ' '))
                            if (payout.providerPayoutId.isNotBlank()) Text("Xendit ID: " + payout.providerPayoutId)
                            if (payout.failureCode.isNotBlank()) Text("Failure code: " + payout.failureCode, color = MaterialTheme.colorScheme.error)
                            if (payout.failureMessage.isNotBlank()) Text("Failure details: " + payout.failureMessage, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Text("Immutable marketplace ledger", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                dashboard.ledgerEntries.forEach { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.entryType.replace('_', ' '), fontWeight = FontWeight.Bold)
                            Text(entry.operationId, style = MaterialTheme.typography.bodySmall)
                            Text("Balanced journal total: ₱" + peso(entry.totalCentavos))
                        }
                    }
                }

                Text("Payment history and refunds", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                dashboard.payments.forEach { payment ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(payment.entityType + " · " + payment.id, fontWeight = FontWeight.Bold)
                            Text(payment.provider + " · " + payment.status.replace('_', ' '))
                            Text("₱" + peso(payment.amountCentavos))
                            if (payment.refundedCentavos > 0L || payment.pendingRefundCentavos > 0L) {
                                Text("Refunded: ₱" + peso(payment.refundedCentavos) + " · pending refund: ₱" + peso(payment.pendingRefundCentavos))
                            }
                            if (payment.refundStatus.isNotBlank()) Text("Refund state: " + payment.refundStatus.replace('_', ' '))
                            if (payment.provider == "XENDIT" && payment.status in setOf("PAID", "PARTIALLY_REFUNDED")) {
                                OutlinedButton(
                                    onClick = { model.refund(payment.entityType, payment.id) },
                                    enabled = !state.isSubmitting,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Request remaining refund") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, modifier = Modifier.weight(1f))
            Text(value, fontWeight = FontWeight.Bold)
        }
    }
}
