package com.zouitel.presentaion.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PanelScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val model = rememberScreenModel { PanelScreenModel() }
        val state by model.state.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Admin Overview", fontWeight = FontWeight.Bold)
                            Text("Labmda operations at a glance", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    actions = {
                        IconButton(onClick = model::refresh, enabled = !state.isLoading) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh overview")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (state.isLoading && state.lastUpdated == 0L) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.error?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Overview data unavailable", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                            OutlinedButton(onClick = model::refresh) { Text("Try again") }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Operations", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (state.lastUpdated > 0L) Text(
                        "Updated " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(state.lastUpdated)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OverviewMetric("Pending approvals", state.pendingApprovals.toString(), "Merchants & providers", { Icon(Icons.Default.Assessment, null) }, Modifier.weight(1f)) {
                        navigator?.push(AdminApprovalOverviewScreen())
                    }
                    OverviewMetric("Active orders", state.activeOrders.toString(), "Food & shop", { Icon(Icons.Default.Storefront, null) }, Modifier.weight(1f)) {
                        navigator?.push(AdminPaymentsScreen())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OverviewMetric("Active rides", state.activeRides.toString(), "Ride requests", { Icon(Icons.Default.DirectionsCar, null) }, Modifier.weight(1f)) {
                        navigator?.push(AdminDriverScreen())
                    }
                    OverviewMetric("Payout alerts", state.payoutAlerts.toString(), "Review required", { Icon(Icons.Default.AccountBalanceWallet, null) }, Modifier.weight(1f)) {
                        navigator?.push(AdminPaymentsScreen())
                    }
                }

                SectionTitle("Approval queue")
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OverviewRow(Icons.Default.Storefront, "Shop applications", state.pendingShops.toString()) { navigator?.push(AdminShopsScreen()) }
                        HorizontalDivider()
                        OverviewRow(Icons.Default.Restaurant, "Restaurant applications", state.pendingRestaurants.toString()) { navigator?.push(AdminRestaurantScreen()) }
                        HorizontalDivider()
                        OverviewRow(Icons.Default.DirectionsCar, "Driver applications", state.pendingDrivers.toString()) { navigator?.push(AdminDriverScreen()) }
                        HorizontalDivider()
                        OverviewRow(Icons.Default.DirectionsBike, "Courier applications", state.pendingCouriers.toString()) { navigator?.push(DeliveryScreen()) }
                        HorizontalDivider()
                        OverviewRow(Icons.Default.LocalShipping, "Freight applications", "Open") { navigator?.push(AdminFreightScreen()) }
                    }
                }

                SectionTitle("Payment health")
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HealthLine("Payment failures / exceptions", state.paymentIssues, state.paymentIssues > 0)
                        HealthLine("Payments still processing", state.paymentsProcessing, false)
                        HealthLine("Outstanding cash ledger", null, state.outstandingCashCentavos > 0L, "₱" + pesoAmount(state.outstandingCashCentavos))
                        HealthLine("Pending payout approvals", state.pendingPayoutRequests, state.pendingPayoutRequests > 0)
                        HealthLine("Payout transfer failures", state.failedPayouts, state.failedPayouts > 0)
                        HealthLine("Payout accounts to verify", state.pendingPayoutAccounts, state.pendingPayoutAccounts > 0)
                        OutlinedButton(onClick = { navigator?.push(AdminPaymentsScreen()) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.AccountBalanceWallet, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Open payments, wallets & payouts")
                        }
                    }
                }

                SectionTitle("Administration")
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AdminAction("Merchant approvals", "Review shops and restaurants") { navigator?.push(AdminApprovalOverviewScreen()) }
                        AdminAction("Provider approvals", "Review drivers and couriers") { navigator?.push(AdminApprovalOverviewScreen()) }
                        AdminAction("Pricing & commissions", "Manage PHP rates and platform fees") { navigator?.push(AdminPricingScreen()) }
                        AdminAction("Cash ledger & remittances", "Review cash collections and balances") { navigator?.push(CashLedgerScreen()) }
                        AdminAction("Payments, wallets & payouts", "Inspect GCash payments and transfers") { navigator?.push(AdminPaymentsScreen()) }
                        AdminAction("Freight applications", "Review freight providers") { navigator?.push(AdminFreightScreen()) }
                    }
                }
                if (state.isLoading && state.lastUpdated > 0L) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Refreshing overview…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun OverviewMetric(
    title: String,
    value: String,
    detail: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                icon()
            }
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OverviewRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, count: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(title, modifier = Modifier.weight(1f))
        Text(count, fontWeight = FontWeight.Bold)
        TextButton(onClick = onClick) { Text("Review") }
    }
}

@Composable
private fun HealthLine(label: String, count: Int?, warning: Boolean, value: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value ?: (count ?: 0).toString(), fontWeight = FontWeight.Bold, color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun AdminAction(title: String, description: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Open")
    }
}

private fun pesoAmount(centavos: Long): String = "%,.2f".format(Locale.US, centavos / 100.0)

class AdminApprovalOverviewScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Approval queues") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { navigator?.push(AdminShopsScreen()) }, modifier = Modifier.fillMaxWidth()) { Text("Shop applications") }
                Button(onClick = { navigator?.push(AdminRestaurantScreen()) }, modifier = Modifier.fillMaxWidth()) { Text("Restaurant applications") }
                Button(onClick = { navigator?.push(AdminDriverScreen()) }, modifier = Modifier.fillMaxWidth()) { Text("Driver applications") }
                Button(onClick = { navigator?.push(DeliveryScreen()) }, modifier = Modifier.fillMaxWidth()) { Text("Courier applications") }
                Button(onClick = { navigator?.push(AdminFreightScreen()) }, modifier = Modifier.fillMaxWidth()) { Text("Freight applications") }
            }
        }
    }
}
