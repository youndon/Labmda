package com.zouitel.presentaion.screens.cash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import java.util.UUID

@Serializable data class CashMerchantHandover(val paymentId: String = "", val merchantId: String = "", val amountMinor: Long = 0, val collectedAt: Long = 0)
@Serializable data class CashSettlementItem(val paymentId: String = "", val amountMinor: Long = 0, val collectedAt: Long = 0)
@Serializable data class MyCashOperations(val collectorId: String = "", val currency: String = "PHP", val platformOutstandingMinor: Long = 0, val merchantOutstandingMinor: Long = 0, val totalOutstandingMinor: Long = 0, val merchantSettlements: List<CashMerchantHandover> = emptyList())
@Serializable data class CashOutstandingBreakdown(val accountId: String = "", val amountMinor: Long = 0)
@Serializable data class MyCashOutstanding(val collectorId: String = "", val currency: String = "PHP", val totalOutstandingMinor: Long = 0, val breakdown: List<CashOutstandingBreakdown> = emptyList())
@Serializable data class MyCashSettlementItems(val currency: String = "PHP", val items: List<CashSettlementItem> = emptyList())
@Serializable data class CashControlsConfig(val alertAfterHours: Int = 24, val maxCollectorOutstandingMinor: Long = 50_000)
@Serializable data class CashOverdueAlert(val paymentId: String = "", val collectorId: String = "", val amountMinor: Long = 0, val ageHours: Int = 0, val status: String = "")
@Serializable data class CashCollectorBalance(val collectorId: String = "", val outstandingMinor: Long = 0)
@Serializable data class AdminCashOverview(val config: CashControlsConfig = CashControlsConfig(), val alerts: List<CashOverdueAlert> = emptyList(), val collectors: List<CashCollectorBalance> = emptyList())

class CashOperationsScreenModel : ScreenModel {
    private val functions = Firebase.functions
    private val _collector = MutableStateFlow<MyCashOperations?>(null)
    val collector = _collector.asStateFlow()
    private val _merchant = MutableStateFlow<MyCashSettlementItems?>(null)
    val merchant = _merchant.asStateFlow()
    private val _admin = MutableStateFlow<AdminCashOverview?>(null)
    val admin = _admin.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun refreshCollector() { screenModelScope.launch { runCatching {
        val outstanding = functions.httpsCallable("getMyCashOutstanding").invoke().data<MyCashOutstanding>()
        val operations = functions.httpsCallable("getMyCashOperations").invoke().data<MyCashOperations>()
        val platform = outstanding.breakdown.filter { it.accountId == "cash_owed_${outstanding.collectorId}" }
            .sumOf { it.amountMinor }
        val merchant = outstanding.breakdown.filter { it.accountId.startsWith("cash_owed_${outstanding.collectorId}_merchant_") }
            .sumOf { it.amountMinor }
        _collector.value = operations.copy(
            platformOutstandingMinor = platform,
            merchantOutstandingMinor = merchant,
            totalOutstandingMinor = outstanding.totalOutstandingMinor
        )
    }.onFailure { _message.value = it.message ?: "Could not load your cash balance." } } }
    fun refreshMerchant() { screenModelScope.launch { runCatching {
        _merchant.value = functions.httpsCallable("getMyCashSettlementItems").invoke().data<MyCashSettlementItems>()
    }.onFailure { _message.value = it.message ?: "Could not load merchant settlements." } } }
    fun refreshAdmin() { screenModelScope.launch { runCatching {
        _admin.value = functions.httpsCallable("getAdminCashControlOverview").invoke().data<AdminCashOverview>()
    }.onFailure { _message.value = it.message ?: "Could not load cash controls." } } }

    fun settleMerchant(paymentId: String) = runAction("Merchant cash receipt confirmed.") {
        functions.httpsCallable("confirmMerchantCashSettlement").invoke(mapOf("paymentId" to paymentId))
        refreshMerchant()
    }
    fun remitCash(collectorId: String, amountMinor: Long, remittanceId: String, onSuccess: () -> Unit) =
        runAction("Cash remittance recorded.", onSuccess) {
            functions.httpsCallable("confirmCashRemittance").invoke(mapOf(
                "remittanceId" to remittanceId,
                "collectorId" to collectorId,
                "amountMinor" to amountMinor
            ))
            refreshAdmin()
        }
    fun saveControls(hours: Int, capMinor: Long) = runAction("Cash controls updated.") {
        functions.httpsCallable("updateAdminCashControls").invoke(mapOf(
            "alertAfterHours" to hours,
            "maxCollectorOutstandingMinor" to capMinor
        ))
        refreshAdmin()
    }
    private fun runAction(success: String, onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        if (_busy.value) return
        screenModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching { action(); _message.value = success; onSuccess() }
                .onFailure { _message.value = it.message ?: "Cash operation failed." }
            _busy.value = false
        }
    }
}

class CollectorCashScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable override fun Content() {
        val navigator = LocalNavigator.current
        val model = rememberScreenModel { CashOperationsScreenModel() }
        val data by model.collector.collectAsState()
        val message by model.message.collectAsState()
        val busy by model.busy.collectAsState()
        LaunchedEffect(Unit) { model.refreshCollector() }
        Scaffold(topBar = { TopAppBar(title = { Text("Cash balance", fontWeight = FontWeight.Bold) }, navigationIcon = {
            IconButton(onClick = { navigator?.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
        }, actions = { IconButton(onClick = { model.refreshCollector() }) { Icon(Icons.Default.Refresh, "Refresh") } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Cash collected on completed jobs is split into two separate obligations. Keep both settled to remain eligible for new work.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("TOTAL CASH OUTSTANDING", style = MaterialTheme.typography.labelMedium)
                        Text(data?.let { php(it.totalOutstandingMinor) } ?: "—", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text("Platform commission: ${data?.let { php(it.platformOutstandingMinor) } ?: "—"}")
                        Text("Merchant handovers: ${data?.let { php(it.merchantOutstandingMinor) } ?: "—"}")
                    }
                } }
                if (busy) item { CircularProgressIndicator() }
                if (!message.isNullOrBlank()) item { Text(message.orEmpty(), color = MaterialTheme.colorScheme.primary) }
                item { Text("Merchant handovers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(data?.merchantSettlements.orEmpty()) { row ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Payment ${row.paymentId.takeLast(8)}", fontWeight = FontWeight.SemiBold)
                        Text(php(row.amountMinor), style = MaterialTheme.typography.titleLarge)
                        Text("Merchant: ${row.merchantId}", style = MaterialTheme.typography.bodySmall)
                    } }
                }
            }
        }
    }
}

class MerchantCashSettlementScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable override fun Content() {
        val navigator = LocalNavigator.current
        val model = rememberScreenModel { CashOperationsScreenModel() }
        val data by model.merchant.collectAsState()
        val message by model.message.collectAsState()
        val busy by model.busy.collectAsState()
        LaunchedEffect(Unit) { model.refreshMerchant() }
        Scaffold(topBar = { TopAppBar(title = { Text("Cash settlements", fontWeight = FontWeight.Bold) }, navigationIcon = {
            IconButton(onClick = { navigator?.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
        }, actions = { IconButton(onClick = { model.refreshMerchant() }) { Icon(Icons.Default.Refresh, "Refresh") } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Confirm only after you physically receive your allocated cash from the assigned courier or driver.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (busy) item { CircularProgressIndicator() }
                if (!message.isNullOrBlank()) item { Text(message.orEmpty(), color = MaterialTheme.colorScheme.primary) }
                items(data?.items.orEmpty()) { item ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Payment ${item.paymentId.takeLast(8)}", fontWeight = FontWeight.SemiBold)
                        Text(php(item.amountMinor), style = MaterialTheme.typography.titleLarge)
                        Button(onClick = { model.settleMerchant(item.paymentId) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Confirm cash received")
                        }
                    } }
                }
                if (data?.items?.isEmpty() == true) item { Text("No merchant cash handovers are waiting for confirmation.") }
            }
        }
    }
}

class AdminCashControlsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable override fun Content() {
        val navigator = LocalNavigator.current
        val model = rememberScreenModel { CashOperationsScreenModel() }
        val data by model.admin.collectAsState()
        val message by model.message.collectAsState()
        val busy by model.busy.collectAsState()
        var hours by remember { mutableStateOf("24") }
        var cap by remember { mutableStateOf("500.00") }
        LaunchedEffect(Unit) { model.refreshAdmin() }
        LaunchedEffect(data?.config) {
            data?.config?.let { hours = it.alertAfterHours.toString(); cap = "%.2f".format(it.maxCollectorOutstandingMinor / 100.0) }
        }
        Scaffold(topBar = { TopAppBar(title = { Text("Cash controls", fontWeight = FontWeight.Bold) }, navigationIcon = {
            IconButton(onClick = { navigator?.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
        }, actions = { IconButton(onClick = { model.refreshAdmin() }) { Icon(Icons.Default.Refresh, "Refresh") } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Overdue cash alert and online cap", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        OutlinedTextField(hours, { hours = it }, label = { Text("Alert after hours") }, singleLine = true)
                        OutlinedTextField(cap, { cap = it }, label = { Text("Maximum outstanding (PHP)") }, singleLine = true)
                        Text("Set the cap to 0 to disable the online limit.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            val h = hours.toIntOrNull()
                            val minor = ((cap.toDoubleOrNull() ?: -1.0) * 100).toLong()
                            if (h != null && minor >= 0) model.saveControls(h, minor)
                        }, enabled = !busy) { Text("Save cash controls") }
                    } }
                }
                if (busy) item { CircularProgressIndicator() }
                if (!message.isNullOrBlank()) item { Text(message.orEmpty(), color = MaterialTheme.colorScheme.primary) }
                item { Text("Overdue cash collections", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(data?.alerts.orEmpty()) { alert ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Payment ${alert.paymentId.takeLast(8)} • ${alert.ageHours}h overdue", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                        Text("Collector: ${alert.collectorId.ifBlank { "Not assigned" }}")
                        Text(php(alert.amountMinor))
                    } }
                }
                item { Text("Collector balances", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(data?.collectors.orEmpty()) { collector ->
                    var amount by remember(collector.collectorId) { mutableStateOf("") }
                    var remittanceId by remember(collector.collectorId) { mutableStateOf("rem_${UUID.randomUUID()}") }
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(collector.collectorId, fontWeight = FontWeight.SemiBold)
                        Text("Outstanding: ${php(collector.outstandingMinor)}")
                        OutlinedTextField(amount, { amount = it }, label = { Text("Cash remitted (PHP)") }, singleLine = true)
                        OutlinedButton(onClick = {
                            val minor = ((amount.toDoubleOrNull() ?: 0.0) * 100).toLong()
                            if (minor > 0) model.remitCash(collector.collectorId, minor, remittanceId) {
                                remittanceId = "rem_${UUID.randomUUID()}"
                                amount = ""
                            }
                        }, enabled = !busy) { Text("Confirm platform remittance") }
                    } }
                }
            }
        }
    }
}

private fun php(minor: Long): String = "₱" + "%.2f".format(minor / 100.0)
