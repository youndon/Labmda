package com.zouitel.presentaion.admin

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class AdminPaymentRecord(
    val id: String = "", val paymentId: String = "", val transactionType: String = "",
    val orderId: String = "", val tripId: String = "", val payerId: String = "",
    val method: String = "", val status: String = "", val amountMinor: Long = 0,
    val refundedAmountMinor: Long = 0, val currency: String = "PHP", val provider: String = "",
    val simulationOnly: Boolean = false, val createdAt: Long = 0, val updatedAt: Long = 0
)
@Serializable
data class AdminCheckoutSession(
    val id: String = "", val paymentId: String = "", val payerId: String = "",
    val method: String = "", val status: String = "", val amountMinor: Long = 0,
    val currency: String = "PHP", val provider: String = "", val simulationOnly: Boolean = false,
    val createdAt: Long = 0, val updatedAt: Long = 0, val expiresAt: Long = 0
)
@Serializable
data class AdminRefundRecord(
    val id: String = "", val paymentId: String = "", val requestedBy: String = "",
    val amountMinor: Long = 0, val currency: String = "PHP", val reason: String = "",
    val status: String = "", val provider: String = "", val simulationOnly: Boolean = false,
    val source: String = "", val createdAt: Long = 0, val updatedAt: Long = 0, val completedAt: Long = 0
)
@Serializable
data class AdminPaymentMonitor(
    val currency: String = "PHP", val generatedAt: Long = 0, val sampleLimitPerSource: Int = 100,
    val payments: List<AdminPaymentRecord> = emptyList(), val sessions: List<AdminCheckoutSession> = emptyList(),
    val refunds: List<AdminRefundRecord> = emptyList(), val liveRefundExecutionAvailable: Boolean = false
)

class AdminPaymentMonitorScreenModel : ScreenModel {
    private val functions = Firebase.functions
    private val _data = MutableStateFlow<AdminPaymentMonitor?>(null)
    val data = _data.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init { refresh() }
    fun refresh() {
        if (_loading.value) return
        screenModelScope.launch {
            _loading.value = true
            _message.value = null
            runCatching {
                _data.value = functions.httpsCallable("getAdminPaymentMonitor").invoke().data<AdminPaymentMonitor>()
            }.onFailure { _message.value = it.message ?: "Unable to load payment records." }
            _loading.value = false
        }
    }
}

class AdminPaymentMonitorScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val model = rememberScreenModel { AdminPaymentMonitorScreenModel() }
        val data by model.data.collectAsState()
        val loading by model.loading.collectAsState()
        val message by model.message.collectAsState()

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Payments & Refunds", fontWeight = FontWeight.Bold) },
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
            if (loading && data == null) {
                Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!message.isNullOrBlank()) item { Text(message.orEmpty(), color = MaterialTheme.colorScheme.error) }
                    item {
                        MonitorNotice(
                            title = "Provider integration status",
                            body = "This screen reads server records only. GCash checkout/refund records marked SIMULATED are development tests. Live Xendit refund execution is not connected, so no refund is sent from this screen."
                        )
                    }
                    data?.let { result ->
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MonitorMetric("Payments", result.payments.size.toString(), "Latest ${result.sampleLimitPerSource} records", Modifier.weight(1f))
                                MonitorMetric("Refunds", result.refunds.size.toString(), "All sources combined", Modifier.weight(1f))
                            }
                        }
                        item { MonitorSectionTitle("Payment records") }
                        if (result.payments.isEmpty()) item { MonitorEmpty("No payment records have been written yet.") }
                        else items(result.payments, key = { "payment-${it.id}" }) { payment ->
                            MonitorRecordCard(
                                title = formatMonitorPhp(payment.amountMinor), status = payment.status,
                                detail = "${payment.method} • ${payment.transactionType.ifBlank { "Payment" }} • ${payment.provider.ifBlank { "Provider not recorded" }}",
                                reference = payment.paymentId, simulationOnly = payment.simulationOnly
                            )
                        }
                        item { MonitorSectionTitle("Checkout sessions") }
                        if (result.sessions.isEmpty()) item { MonitorEmpty("No checkout sessions have been recorded.") }
                        else items(result.sessions, key = { "session-${it.id}" }) { session ->
                            MonitorRecordCard(
                                title = formatMonitorPhp(session.amountMinor), status = session.status,
                                detail = "${session.method} • ${session.provider.ifBlank { "Provider not recorded" }}",
                                reference = "Session ${session.id} • Payment ${session.paymentId}",
                                simulationOnly = session.simulationOnly
                            )
                        }
                        item { MonitorSectionTitle("Refund history") }
                        if (result.refunds.isEmpty()) item { MonitorEmpty("No refund records have been created.") }
                        else items(result.refunds, key = { "refund-${it.source}-${it.id}" }) { refund ->
                            MonitorRecordCard(
                                title = formatMonitorPhp(refund.amountMinor), status = refund.status,
                                detail = "${refund.provider.ifBlank { "Provider not recorded" }} • ${refund.reason.ifBlank { "No reason supplied" }}",
                                reference = "Refund ${refund.id} • Payment ${refund.paymentId}",
                                simulationOnly = refund.simulationOnly
                            )
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MonitorNotice(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
private fun MonitorMetric(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
private fun MonitorSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}
@Composable
private fun MonitorEmpty(message: String) {
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable
private fun MonitorRecordCard(title: String, status: String, detail: String, reference: String, simulationOnly: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(status.ifBlank { "UNKNOWN" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(reference, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            if (simulationOnly) Text(
                "SIMULATION ONLY • NOT REAL MONEY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
private fun formatMonitorPhp(minor: Long): String {
    val pesos = minor / 100
    val centavos = kotlin.math.abs(minor % 100).toString().padStart(2, '0')
    return "₱$pesos.$centavos"
}
