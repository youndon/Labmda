package com.zouitel.presentaion.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

@Serializable
data class PendingPayout(
    val payoutId: String = "",
    val uid: String = "",
    val providerKind: String = "",
    val providerId: String = "",
    val amountMinor: Long = 0,
    val currency: String = "PHP",
    val method: String = "",
    val accountName: String = "",
    val accountNumber: String = "",
    val status: String = "REQUESTED",
    val transferReference: String? = null,
    val createdAt: Long = 0
)

class AdminPayoutScreenModel : ScreenModel {
    private val functions = Firebase.functions
    private val _requests = MutableStateFlow<List<PendingPayout>>(emptyList())
    val requests = _requests.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init { refresh() }

    fun refresh() {
        screenModelScope.launch {
            _loading.value = true
            runCatching {
                _requests.value = functions.httpsCallable("getPendingPayoutRequests").invoke().data()
            }.onFailure { _message.value = it.message ?: "Unable to load payout requests." }
            _loading.value = false
        }
    }

    fun approve(id: String) = perform("approvePayoutRequest", mapOf("payoutId" to id), "Payout approved.")
    fun reject(id: String) = perform("rejectPayoutRequest", mapOf("payoutId" to id, "note" to "Rejected by administrator."), "Payout rejected and balance released.")
    fun markPaid(id: String, reference: String) = perform(
        "markPayoutAsPaid",
        mapOf("payoutId" to id, "transferReference" to reference.trim()),
        "Transfer recorded as paid."
    )

    private fun perform(name: String, data: Map<String, String>, success: String) {
        if (_busy.value) return
        screenModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching {
                functions.httpsCallable(name).invoke(data)
                _message.value = success
                _requests.value = functions.httpsCallable("getPendingPayoutRequests").invoke().data()
            }.onFailure { _message.value = it.message ?: "The payout action failed." }
            _busy.value = false
        }
    }
}

class AdminPayoutScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val model = rememberScreenModel { AdminPayoutScreenModel() }
        val requests by model.requests.collectAsState()
        val loading by model.loading.collectAsState()
        val busy by model.busy.collectAsState()
        val message by model.message.collectAsState()
        var selectedForPayment by remember { mutableStateOf<PendingPayout?>(null) }
        var transferReference by remember { mutableStateOf("") }
        var query by remember { mutableStateOf("") }
        val filteredRequests = requests.filter {
            it.providerKind.contains(query, ignoreCase = true) ||
                it.providerId.contains(query, ignoreCase = true) ||
                it.accountName.contains(query, ignoreCase = true) ||
                it.accountNumber.contains(query, ignoreCase = true) ||
                it.method.contains(query, ignoreCase = true) ||
                it.payoutId.contains(query, ignoreCase = true)
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Provider Payouts", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { model.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                )
            }
        ) { padding ->
            if (loading) {
                Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!message.isNullOrBlank()) item { Text(message.orEmpty(), color = MaterialTheme.colorScheme.primary) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "${requests.size} payout requests awaiting review",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Review the provider destination and record a transfer reference only after the payment has actually been sent.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                label = { Text("Search provider, account or payout ID") }
                            )
                        }
                    }
                    if (filteredRequests.isEmpty()) {
                        item {
                            AdminApplicationEmptyState(
                                title = if (requests.isEmpty()) "No payout requests awaiting review" else "No matching payout requests",
                                description = if (requests.isEmpty()) "New provider payout requests will appear here." else "Try another provider, account number or payout ID."
                            )
                        }
                    }
                    items(filteredRequests, key = { it.payoutId }) { request ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(formatAdminPhp(request.amountMinor), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text("${request.providerKind} • Provider ${request.providerId}", style = MaterialTheme.typography.bodyMedium)
                                Text("Account holder: ${request.accountName}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (request.method == "GCASH") "GCash: ${request.accountNumber}" else "BDO account: ${request.accountNumber}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text("Request ID: ${request.payoutId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Status: ${request.status}", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
                                if (request.status == "REJECTING") {
                                    Button(onClick = { model.reject(request.payoutId) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                        Text("Retry rejection")
                                    }
                                } else if (request.status == "PAYING") {
                                    Button(
                                        onClick = { selectedForPayment = request; transferReference = request.transferReference.orEmpty() },
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Finish payout recording") }
                                } else if (request.status == "REQUESTED") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { model.reject(request.payoutId) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                            Text("Reject")
                                        }
                                        Button(onClick = { model.approve(request.payoutId) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                            Text("Approve")
                                        }
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { model.reject(request.payoutId) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                            Text("Reject")
                                        }
                                        Button(
                                            onClick = { selectedForPayment = request; transferReference = request.transferReference.orEmpty() },
                                            enabled = !busy,
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Record transfer") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        selectedForPayment?.let { payout ->
            AlertDialog(
                onDismissRequest = { selectedForPayment = null },
                title = { Text("Confirm manual transfer") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Only continue after you have actually sent ${formatAdminPhp(payout.amountMinor)} to the provider.")
                        Text("Destination: ${payout.method} • ${payout.accountNumber}", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = transferReference,
                            onValueChange = { transferReference = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("GCash / BDO transfer reference") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            model.markPaid(payout.payoutId, transferReference)
                            selectedForPayment = null
                        },
                        enabled = !busy && transferReference.isNotBlank()
                    ) { Text("Confirm paid") }
                },
                dismissButton = { TextButton(onClick = { selectedForPayment = null }) { Text("Cancel") } }
            )
        }
    }
}

private fun formatAdminPhp(minor: Long): String {
    val pesos = minor / 100
    val centavos = kotlin.math.abs(minor % 100).toString().padStart(2, '0')
    return "₱$pesos.$centavos"
}
