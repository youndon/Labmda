package com.zouitel.presentaion.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.util.Locale

@Serializable
data class AdminFinanceProviderBalances(
    val availableMinor: Long = 0,
    val pendingMinor: Long = 0,
    val clearingMinor: Long = 0
)

@Serializable
data class AdminFinancePayoutAggregate(
    val count: Int = 0,
    val amountMinor: Long = 0
)

@Serializable
data class AdminFinanceDay(
    val date: String = "",
    val amountMinor: Long = 0
)

@Serializable
data class AdminFinanceOperation(
    val operationId: String = "",
    val eventType: String = "",
    val referenceId: String = "",
    val description: String = "",
    val createdAt: Long = 0,
    val amountMinor: Long = 0
)

@Serializable
data class AdminFinancialOverview(
    val currency: String = "PHP",
    val generatedAt: Long = 0,
    val platformRevenueAvailableMinor: Long = 0,
    val platformRevenueClearingMinor: Long = 0,
    val providerBalances: AdminFinanceProviderBalances = AdminFinanceProviderBalances(),
    val cashOutstandingMinor: Long = 0,
    val paymentVolumeMinor: Long = 0,
    val paymentCount: Int = 0,
    val payoutSummary: Map<String, AdminFinancePayoutAggregate> = emptyMap(),
    val dailyPaymentVolume: List<AdminFinanceDay> = emptyList(),
    val recentOperations: List<AdminFinanceOperation> = emptyList()
)

class AdminFinanceOverviewScreenModel : ScreenModel {
    private val functions = Firebase.functions
    private val _overview = MutableStateFlow<AdminFinancialOverview?>(null)
    val overview = _overview.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (_loading.value) return
        screenModelScope.launch {
            _loading.value = true
            _message.value = null
            runCatching {
                _overview.value = functions.httpsCallable("getAdminFinancialOverview")
                    .invoke().data<AdminFinancialOverview>()
            }.onFailure { _message.value = it.message ?: "Unable to load financial overview." }
            _loading.value = false
        }
    }

    fun reconcile() {
        if (_busy.value) return
        screenModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching {
                functions.httpsCallable("reconcileCashPayments").invoke()
                _message.value = "Reconciliation snapshot saved. Refresh to update the overview."
                refresh()
            }.onFailure { _message.value = it.message ?: "Reconciliation could not be completed." }
            _busy.value = false
        }
    }
}

class AdminFinanceOverviewScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val model = rememberScreenModel { AdminFinanceOverviewScreenModel() }
        val overview by model.overview.collectAsState()
        val loading by model.loading.collectAsState()
        val busy by model.busy.collectAsState()
        val message by model.message.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Financial Overview", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { model.refresh() }, enabled = !loading) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                )
            }
        ) { padding ->
            if (loading && overview == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("LABMDA FINANCE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text("Your platform at a glance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("PHP ledger figures • server generated", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!message.isNullOrBlank()) {
                        item {
                            Text(message.orEmpty(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (overview == null && !loading) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Financial data unavailable", fontWeight = FontWeight.SemiBold)
                                    Text("Check administrator permissions and Cloud Functions deployment.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Button(onClick = { model.refresh() }) { Text("Try again") }
                                }
                            }
                        }
                    }
                    overview?.let { data ->
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.TrendingUp, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text("  PLATFORM REVENUE AVAILABLE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                                    }
                                    Text("${data.platformRevenueAvailableMinor.let(::formatFinancePhp)}", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                                    Text("Current available balance in the platform revenue ledger account. Not gross sales.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FinanceMetricCard("Payment volume", formatFinancePhp(data.paymentVolumeMinor), "${data.paymentCount} posted payments", Modifier.weight(1f))
                                FinanceMetricCard("Cash outstanding", formatFinancePhp(data.cashOutstandingMinor), "Awaiting remittance / settlement", Modifier.weight(1f))
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FinanceMetricCard("Provider available", formatFinancePhp(data.providerBalances.availableMinor), "Merchant, driver and courier wallets", Modifier.weight(1f))
                                FinanceMetricCard("Provider pending", formatFinancePhp(data.providerBalances.pendingMinor), "Not yet available for payout", Modifier.weight(1f))
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Payment volume", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Last 7 days • PHP", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                FinanceVolumeChart(data.dailyPaymentVolume)
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Payout pipeline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    FinanceMetricCard("Requested", payoutCount(data, "REQUESTED"), payoutAmount(data, "REQUESTED"), Modifier.weight(1f))
                                    FinanceMetricCard("Approved", payoutCount(data, "APPROVED"), payoutAmount(data, "APPROVED"), Modifier.weight(1f))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    FinanceMetricCard("Processing", payoutCount(data, "PAYING"), payoutAmount(data, "PAYING"), Modifier.weight(1f))
                                    FinanceMetricCard("Paid", payoutCount(data, "PAID"), payoutAmount(data, "PAID"), Modifier.weight(1f))
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { navigator?.push(AdminPayoutScreen()) }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text("  Manage payouts")
                                }
                                Button(onClick = { model.reconcile() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                    if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Default.Scale, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text("  Reconcile")
                                }
                            }
                        }
                        item {
                            Text("Recent ledger activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        if (data.recentOperations.isEmpty()) {
                            item { Text("No ledger activity has been recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        } else {
                            items(data.recentOperations, key = { it.operationId }) { operation ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(operation.eventType.replace('_', ' '), fontWeight = FontWeight.SemiBold)
                                            Text(operation.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(operation.referenceId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                        }
                                        Text(formatFinancePhp(operation.amountMinor), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FinanceMetricCard(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FinanceVolumeChart(days: List<AdminFinanceDay>) {
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (days.isEmpty()) {
                Text("No payment volume available for this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val maximum = (days.maxOfOrNull { it.amountMinor } ?: 0L).coerceAtLeast(1L).toFloat()
                Canvas(Modifier.fillMaxWidth().height(112.dp)) {
                    val gap = 10.dp.toPx()
                    val count = days.size
                    val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(2f)
                    days.forEachIndexed { index, day ->
                        val left = index * (barWidth + gap)
                        val barHeight = (day.amountMinor.toFloat() / maximum * size.height).coerceAtLeast(4.dp.toPx())
                        drawRoundRect(
                            color = trackColor,
                            topLeft = Offset(left, 0f),
                            size = androidx.compose.ui.geometry.Size(barWidth, size.height),
                            cornerRadius = CornerRadius(7.dp.toPx())
                        )
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(left, size.height - barHeight),
                            size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(7.dp.toPx())
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(days.first().date.takeLast(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(days.last().date.takeLast(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun payoutCount(data: AdminFinancialOverview, status: String): String =
    (data.payoutSummary[status]?.count ?: 0).toString()

private fun payoutAmount(data: AdminFinancialOverview, status: String): String =
    formatFinancePhp(data.payoutSummary[status]?.amountMinor ?: 0)

private fun formatFinancePhp(minor: Long): String {
    val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    return "₱${formatter.format(minor / 100.0)}"
}
