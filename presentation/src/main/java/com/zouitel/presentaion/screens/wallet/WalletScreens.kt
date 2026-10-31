package com.zouitel.presentaion.screens.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import org.koin.core.context.GlobalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MerchantEarningsScreen : Screen {
    @Composable override fun Content() {
        val model = rememberScreenModel { WalletScreenModel(GlobalContext.get().get()) }
        WalletDashboard(model, "Merchant Earnings", "Food and goods earnings")
    }
}
class CourierEarningsScreen : Screen {
    @Composable override fun Content() {
        val model = rememberScreenModel { WalletScreenModel(GlobalContext.get().get()) }
        WalletDashboard(model, "Courier Earnings", "Delivery earnings and cash remittance")
    }
}
class DriverEarningsScreen : Screen {
    @Composable override fun Content() {
        val model = rememberScreenModel { WalletScreenModel(GlobalContext.get().get()) }
        WalletDashboard(model, "Driver Earnings", "Ride earnings and cash remittance")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletDashboard(screenModel: WalletScreenModel, title: String, subtitle: String) {
    val navigator = LocalNavigator.current
    val state by screenModel.state.collectAsState()
    val wallet = state.summary
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.labelSmall) } },
                navigationIcon = { IconButton(onClick = { navigator?.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.isLoading) item { CircularProgressIndicator() }
            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
            item { WalletAmountCard("Available earnings", wallet.availableCentavos, "Eligible for payout to your verified GCash account") }
            item { WalletAmountCard("Pending earnings", wallet.pendingCentavos, "Released after service completion and confirmed Xendit settlement") }
            item { WalletAmountCard("Cash owed to Labmda", wallet.cashOwedCentavos, "Cash collected by you that must be remitted") }
            item { WalletAmountCard("Cash earnings held by you", wallet.cashEarningsCentavos, "Your share from cash collected in person; not paid out a second time") }
            item { WalletAmountCard("Refund recovery owed", wallet.refundDebtCentavos, "Future earnings may be used to recover this balance before payout") }
            item { WalletAmountCard("Payout in progress", wallet.pendingPayoutCentavos, "Reserved while the transfer is being processed") }
            item { WalletAmountCard("Total paid out", wallet.paidOutCentavos, "Transfers confirmed successful by the payout process") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("GCash payout account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(wallet.payoutAccount.maskedAccountNumber.ifBlank { "No GCash destination added" })
                        PayoutStatusText(wallet.payoutAccount.status, payoutAccountExplanation(wallet.payoutAccount.status))
                        if (wallet.payoutAccount.reviewNote.isNotBlank()) {
                            Text("Review note: " + wallet.payoutAccount.reviewNote,
                                color = if (wallet.payoutAccount.status == "REJECTED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { navigator?.push(PayoutAccountScreen()) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (wallet.payoutAccount.status == "VERIFIED") "View payout account" else "Set up / update payout account")
                        }
                        Button(
                            onClick = { screenModel.requestPayout() },
                            enabled = !state.isSubmitting && wallet.payoutAccount.status == "VERIFIED" &&
                                wallet.availableCentavos >= 10_000L && wallet.pendingPayoutCentavos == 0L &&
                                wallet.refundDebtCentavos == 0L && wallet.activePayoutRequest == null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (state.isSubmitting) "Submitting…" else "Request payout") }
                        Text("Manual payout minimum: ₱100. Automatic payouts start at ₱500. A verified GCash destination is required.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { screenModel.refresh() }, modifier = Modifier.fillMaxWidth()) { Text("Refresh wallet") }
                    }
                }
            }
            wallet.activePayoutRequest?.let { active ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Payout request in progress", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Request " + active.id.takeLast(8) + " · ₱" + peso(active.amountCentavos))
                            PayoutStatusText(active.status, payoutRequestExplanation(active.status))
                            PayoutProgress(active.status)
                            Text("Your available balance is reserved only after the request is approved and submitted for transfer.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { Text("Payout request history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (wallet.payoutRequests.isEmpty()) {
                item { Text("No payout requests yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(wallet.payoutRequests, key = { "request-" + it.id }) { request ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Request " + request.id.takeLast(8), fontWeight = FontWeight.Bold)
                            Text("Amount: ₱" + peso(request.amountCentavos))
                            PayoutStatusText(request.status, payoutRequestExplanation(request.status))
                            if (request.providerStatus.isNotBlank()) Text("Xendit: " + request.providerStatus.replace('_', ' '))
                            if (request.maskedAccountNumber.isNotBlank()) Text("Destination: " + request.maskedAccountNumber)
                            if (request.createdAtTimestamp > 0L) Text("Requested: " + payoutDate(request.createdAtTimestamp), style = MaterialTheme.typography.bodySmall)
                            if (request.updatedAtTimestamp > 0L) Text("Updated: " + payoutDate(request.updatedAtTimestamp), style = MaterialTheme.typography.bodySmall)
                            if (request.rejectionNote.isNotBlank()) Text("Review note: " + request.rejectionNote, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item { Text("Payout transfer history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (wallet.payouts.isEmpty()) {
                item { Text("No payouts recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(wallet.payouts, key = { it.id }) { payout ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Payout " + payout.id.takeLast(8), fontWeight = FontWeight.Bold)
                            Text("Amount: ₱" + peso(payout.amountCentavos))
                            PayoutStatusText(payout.status, payoutTransferExplanation(payout.status))
                            if (payout.createdAtTimestamp > 0L) Text("Created: " + payoutDate(payout.createdAtTimestamp), style = MaterialTheme.typography.bodySmall)
                            if (payout.maskedAccountNumber.isNotBlank()) Text("Destination: " + payout.maskedAccountNumber)
                            if (payout.providerPayoutId.isNotBlank()) Text("Xendit reference: " + payout.providerPayoutId, style = MaterialTheme.typography.bodySmall)
                            if (payout.failureCode.isNotBlank()) Text("Failure code: " + payout.failureCode, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            if (payout.failureMessage.isNotBlank()) {
                                Text(payout.failureMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                Text(payoutFailureNextStep(payout.failureCode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.padding(bottom = 8.dp)) }
        }
    }
}

@Composable
private fun WalletAmountCard(title: String, centavos: Long, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("₱" + peso(centavos), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PayoutStatusText(status: String, explanation: String) {
    val normalized = status.uppercase()
    val color = when (normalized) {
        "SUCCEEDED", "VERIFIED" -> MaterialTheme.colorScheme.primary
        "FAILED", "REJECTED", "CANCELLED", "EXPIRED" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    Text(normalized.ifBlank { "UNKNOWN" }.replace('_', ' '), color = color, fontWeight = FontWeight.SemiBold)
    Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PayoutProgress(status: String) {
    val normalized = status.uppercase()
    val stages = listOf("REQUESTED", "APPROVED", "PROCESSING", "SUCCEEDED")
    val current = when {
        normalized in setOf("PENDING", "PENDING_ADMIN_APPROVAL", "REQUESTED") -> 0
        normalized in setOf("APPROVED", "SUBMITTED", "ACCEPTED") -> 1
        normalized in setOf("PROCESSING", "PENDING_PROVIDER") -> 2
        normalized == "SUCCEEDED" -> 3
        else -> -1
    }
    if (current >= 0) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            stages.forEachIndexed { index, stage ->
                Text(
                    if (index <= current) "● $stage" else "○ $stage",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun payoutAccountExplanation(status: String) = when (status.uppercase()) {
    "VERIFIED" -> "This GCash destination passed Labmda's review and can be selected for payout."
    "PENDING_REVIEW" -> "Your details were submitted. Payout requests are unavailable until the account is approved."
    "REJECTED" -> "The account needs changes. Read the review note and submit corrected details."
    else -> "Add your GCash details and submit them for administrator review."
}
private fun payoutRequestExplanation(status: String) = when (status.uppercase()) {
    "PENDING_ADMIN_APPROVAL", "PENDING" -> "Waiting for Labmda administrator review. Your available earnings are not reserved yet."
    "APPROVED", "SUBMITTED", "PROCESSING" -> "Approved and being processed for transfer. Check the payout history for the provider result."
    "REJECTED" -> "The request was declined. Review the administrator note before submitting another request."
    "FAILED" -> "The transfer could not be completed. Review the failure details and contact support if the issue persists."
    "SUCCEEDED" -> "The transfer was confirmed successful."
    "CANCELLED", "EXPIRED" -> "This request is no longer active."
    else -> "The request status is being updated. Refresh for the latest information."
}
private fun payoutTransferExplanation(status: String) = when (status.uppercase()) {
    "PENDING", "PROCESSING" -> "Xendit is processing this transfer. It is not confirmed as received yet."
    "SUCCEEDED" -> "The payout provider confirmed this transfer as successful."
    "FAILED" -> "The transfer failed. The reserved amount should return to your available balance after server reconciliation."
    "CANCELLED" -> "This transfer was cancelled."
    "ON_HOLD" -> "The transfer is on hold for additional review."
    else -> "The payout status is not yet final."
}
private fun payoutFailureNextStep(code: String) = when {
    code.contains("ACCOUNT", true) || code.contains("INVALID", true) -> "Check that the GCash account is active and the recipient details match."
    code.contains("LIMIT", true) -> "The provider limit may have been reached. Contact support before retrying."
    code.contains("COMPLIANCE", true) -> "The provider requires additional review. Contact Labmda support."
    else -> "Do not submit repeated payout requests. Refresh the wallet and contact Labmda support if the status does not change."
}
private fun payoutDate(timestamp: Long): String =
    SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(timestamp))

internal fun peso(centavos: Long): String = String.format(Locale.US, "%.2f", centavos / 100.0)
