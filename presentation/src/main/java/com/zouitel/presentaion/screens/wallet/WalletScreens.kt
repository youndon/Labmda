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
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
            item {
                WalletAmountCard("Available earnings", wallet.availableCentavos, "Can be paid to a verified GCash account")
            }
            item { WalletAmountCard("Pending earnings", wallet.pendingCentavos, "Released after the service has been completed") }
            item { WalletAmountCard("Cash owed to Labmda", wallet.cashOwedCentavos, "Cash collected by you that must be remitted") }
            item { WalletAmountCard("Cash earnings held by you", wallet.cashEarningsCentavos, "Your share from cash collected in person") }
            item { WalletAmountCard("Payout in progress", wallet.pendingPayoutCentavos, "Reserved while Xendit processes a payout") }
            item { WalletAmountCard("Total paid out", wallet.paidOutCentavos, "Confirmed successful wallet payouts") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Payout account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("GCash · " + wallet.payoutAccount.maskedAccountNumber.ifBlank { "Not added" })
                        Text("Status: " + wallet.payoutAccount.status.replace('_', ' '))
                        Button(onClick = { navigator?.push(PayoutAccountScreen()) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Set up payout account")
                        }
                        Button(
                            onClick = { screenModel.requestPayout() },
                            enabled = !state.isSubmitting && wallet.payoutAccount.status == "VERIFIED" &&
                                wallet.availableCentavos >= 10_000L && wallet.pendingPayoutCentavos == 0L,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (state.isSubmitting) "Submitting…" else "Request payout")
                        }
                        Text("Manual payout minimum PHP 100. Automatic payouts start at PHP 500. A verified recipient account is required.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { screenModel.refresh() }, modifier = Modifier.fillMaxWidth()) { Text("Refresh wallet") }
                    }
                }
            }
            item { Text("Recent payouts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (wallet.payouts.isEmpty()) {
                item { Text("No payouts recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(wallet.payouts, key = { it.id }) { payout ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Payout " + payout.id.takeLast(8), fontWeight = FontWeight.Bold)
                            Text("Amount: " + peso(payout.amountCentavos))
                            Text("Status: " + payout.status.replace('_', ' '))
                            if (payout.providerPayoutId.isNotBlank()) Text("Xendit reference: " + payout.providerPayoutId)
                            if (payout.failureCode.isNotBlank()) Text("Failure: " + payout.failureCode, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
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

internal fun peso(centavos: Long): String = String.format(Locale.US, "%.2f", centavos / 100.0)
