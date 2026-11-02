package com.zouitel.presentaion.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import java.math.BigDecimal
import java.math.RoundingMode

@Serializable
data class AdminPaymentRow(
    val paymentId: String = "", val payerId: String = "", val status: String = "",
    val method: String = "", val transactionType: String = "", val orderId: String = "",
    val tripId: String = "", val amountMinor: Long = 0, val provider: String = "",
    val checkoutUrl: String = "", val providerReference: String = "", val refundedAmountMinor: Long = 0,
    val refundableMinor: Long = 0, val currency: String = "PHP", val createdAt: Long = 0
)

@Serializable
data class AdminPaymentsResponse(val payments: List<AdminPaymentRow> = emptyList())

@Serializable
data class AdminBalanceRow(
    val accountId: String = "", val kind: String = "", val ownerId: String = "",
    val availableMinor: Long = 0, val pendingMinor: Long = 0, val cashOwedMinor: Long = 0,
    val clearingMinor: Long = 0, val totalMinor: Long = 0
)

@Serializable
data class AdminBalancesResponse(val accounts: List<AdminBalanceRow> = emptyList())

@Serializable
data class AdminRefundCaseRow(
    val id: String = "", val paymentId: String = "", val payerId: String = "",
    val amountMinor: Long = 0, val currency: String = "PHP", val reason: String = "",
    val status: String = "", val providerReference: String? = null, val createdAt: Long = 0
)

@Serializable
data class AdminRefundCasesResponse(val cases: List<AdminRefundCaseRow> = emptyList())

@Serializable
data class AdminReconciliationResult(val balanced: Boolean = false)

@Serializable
data class AdminAuditRow(
    val id: String = "", val actorUid: String = "", val action: String = "",
    val targetType: String = "", val targetId: String = "", val note: String = "", val createdAt: Long = 0
)

@Serializable
data class AdminAuditResponse(val logs: List<AdminAuditRow> = emptyList())

class AdminFinancialOperationsScreenModel : ScreenModel {
    private val functions = Firebase.functions
    private val _payments = MutableStateFlow<List<AdminPaymentRow>>(emptyList())
    val payments = _payments.asStateFlow()
    private val _balances = MutableStateFlow<List<AdminBalanceRow>>(emptyList())
    val balances = _balances.asStateFlow()
    private val _refunds = MutableStateFlow<List<AdminRefundCaseRow>>(emptyList())
    val refunds = _refunds.asStateFlow()
    private val _audit = MutableStateFlow<List<AdminAuditRow>>(emptyList())
    val audit = _audit.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _reconciliation = MutableStateFlow<String?>(null)
    val reconciliation = _reconciliation.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (_loading.value) return
        screenModelScope.launch {
            _loading.value = true
            _message.value = null
            runCatching {
                _payments.value = functions.httpsCallable("getAdminPaymentMonitor")
                    .invoke().data<AdminPaymentsResponse>().payments
                _balances.value = functions.httpsCallable("getAdminFinancialBalances")
                    .invoke().data<AdminBalancesResponse>().accounts
                _refunds.value = functions.httpsCallable("getAdminRefundCases")
                    .invoke().data<AdminRefundCasesResponse>().cases
                _audit.value = functions.httpsCallable("getAdminAuditTrail")
                    .invoke().data<AdminAuditResponse>().logs
            }.onFailure { _message.value = it.message ?: "Unable to load financial administration." }
            _loading.value = false
        }
    }

    fun attachBdoCheckoutLink(paymentId: String, checkoutUrl: String, amountPesos: String) = action {
        functions.httpsCallable("attachBdoCheckoutLink").invoke(
            mapOf("paymentId" to paymentId, "checkoutUrl" to checkoutUrl, "amountMinor" to pesosToMinor(amountPesos))
        )
        "BDO Checkout payment request link attached."
    }

    fun confirmBdoGcashPayment(paymentId: String, transferReference: String, amountPesos: String) = action {
        functions.httpsCallable("confirmBdoGcashPayment").invoke(
            mapOf("paymentId" to paymentId, "transferReference" to transferReference, "amountMinor" to pesosToMinor(amountPesos))
        )
        "BDO GCash payment verified and recorded."
    }

    fun createRefund(paymentId: String, amountPesos: String, reason: String) = action {
        val amountMinor = pesosToMinor(amountPesos)
        functions.httpsCallable("createAdminRefundCase").invoke(
            mapOf("paymentId" to paymentId, "amountMinor" to amountMinor, "reason" to reason)
        )
        "Refund case created."
    }

    fun reviewRefund(refundId: String, decision: String) = action {
        functions.httpsCallable("reviewAdminRefundCase").invoke(
            mapOf("refundId" to refundId, "decision" to decision, "note" to "Reviewed in Admin Financial Management.")
        )
        "Refund $decision."
    }

    fun completeRefund(refundId: String, reference: String) = action {
        functions.httpsCallable("completeAdminRefundCase").invoke(
            mapOf("refundId" to refundId, "providerReference" to reference)
        )
        "Refund completion recorded with provider reference."
    }

    fun reconcile() = action {
        val result = functions.httpsCallable("runAdminFinancialReconciliation").invoke().data<AdminReconciliationResult>()
        val balanced = result.balanced
        _reconciliation.value = if (balanced) "Ledger reconciliation is balanced." else "Discrepancies found. Review the report before making adjustments."
        "Financial reconciliation saved."
    }

    fun resolveDiscrepancy(
        referenceId: String, amountPesos: String, note: String,
        sourceAccount: String, sourceBucket: String, sourcePartyType: String, sourcePartyId: String,
        destinationAccount: String, destinationBucket: String, destinationPartyType: String, destinationPartyId: String
    ) = action {
        functions.httpsCallable("resolveAdminFinancialDiscrepancy").invoke(mapOf(
            "referenceId" to referenceId, "amountMinor" to pesosToMinor(amountPesos), "note" to note,
            "source" to mapOf("accountId" to sourceAccount, "bucket" to sourceBucket, "partyType" to sourcePartyType, "partyId" to sourcePartyId),
            "destination" to mapOf("accountId" to destinationAccount, "bucket" to destinationBucket, "partyType" to destinationPartyType, "partyId" to destinationPartyId)
        ))
        "Balanced adjustment posted."
    }

    private fun action(block: suspend () -> String) {
        if (_busy.value) return
        screenModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching { block() }
                .onSuccess { _message.value = it; refreshAfterAction() }
                .onFailure { _message.value = it.message ?: "Financial action failed." }
            _busy.value = false
        }
    }

    private suspend fun refreshAfterAction() {
        runCatching {
            _payments.value = functions.httpsCallable("getAdminPaymentMonitor").invoke().data<AdminPaymentsResponse>().payments
            _balances.value = functions.httpsCallable("getAdminFinancialBalances").invoke().data<AdminBalancesResponse>().accounts
            _refunds.value = functions.httpsCallable("getAdminRefundCases").invoke().data<AdminRefundCasesResponse>().cases
            _audit.value = functions.httpsCallable("getAdminAuditTrail").invoke().data<AdminAuditResponse>().logs
        }
    }

    private fun pesosToMinor(value: String): Long {
        val amount = runCatching { BigDecimal(value.trim()).setScale(2, RoundingMode.UNNECESSARY) }
            .getOrElse { throw IllegalArgumentException("Enter a valid amount in PHP, with at most two decimal places.") }
        val minor = amount.movePointRight(2).longValueExact()
        require(minor > 0) { "Amount must be greater than zero." }
        return minor
    }
}

class AdminFinancialOperationsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val model = rememberScreenModel { AdminFinancialOperationsScreenModel() }
        val payments by model.payments.collectAsState()
        val balances by model.balances.collectAsState()
        val refunds by model.refunds.collectAsState()
        val audit by model.audit.collectAsState()
        val loading by model.loading.collectAsState()
        val busy by model.busy.collectAsState()
        val message by model.message.collectAsState()
        val reconciliation by model.reconciliation.collectAsState()

        var refundTarget by remember { mutableStateOf<AdminPaymentRow?>(null) }
        var refundAmount by remember { mutableStateOf("") }
        var refundReason by remember { mutableStateOf("") }
        var completionTarget by remember { mutableStateOf<AdminRefundCaseRow?>(null) }
        var providerReference by remember { mutableStateOf("") }
        var showAdjustment by remember { mutableStateOf(false) }
        var checkoutTarget by remember { mutableStateOf<AdminPaymentRow?>(null) }
        var checkoutUrl by remember { mutableStateOf("") }
        var checkoutAmount by remember { mutableStateOf("") }
        var bdoConfirmTarget by remember { mutableStateOf<AdminPaymentRow?>(null) }
        var bdoReference by remember { mutableStateOf("") }
        var bdoAmount by remember { mutableStateOf("") }

        Scaffold(topBar = {
            TopAppBar(
                title = { Text("Financial Administration", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { navigator?.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { model.refresh() }, enabled = !loading) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") } }
            )
        }) { padding ->
            if (loading) {
                Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!message.isNullOrBlank()) item { Text(message.orEmpty(), color = MaterialTheme.colorScheme.primary) }
                    item {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Reconciliation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(reconciliation ?: "Compare immutable journal entries with cached account balances.", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { model.reconcile() }, enabled = !busy) {
                                        if (busy) CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Scale, contentDescription = null)
                                        Text("  Run ledger check")
                                    }
                                    OutlinedButton(onClick = { showAdjustment = true }, enabled = !busy) { Text("Resolve discrepancy") }
                                }
                            }
                        }
                    }
                    item { SectionTitle("Payments", "${payments.size} recent records") }
                    if (payments.isEmpty()) item { EmptyFinanceText("No payment records found.") }
                    items(payments, key = { it.paymentId }) { payment ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${payment.method} • ${payment.transactionType}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text(payment.status, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
                                }
                                Text(formatAdminMoney(payment.amountMinor), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Payment ${payment.paymentId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (payment.method == "GCASH" && payment.provider == "BDO_CHECKOUT" && payment.status in setOf("PENDING", "PROCESSING")) {
                                    Text("Processor: BDO Checkout • Customer method: GCash", style = MaterialTheme.typography.bodySmall)
                                    if (payment.checkoutUrl.isBlank()) {
                                        OutlinedButton(onClick = {
                                            checkoutTarget = payment
                                            checkoutUrl = ""
                                            checkoutAmount = BigDecimal.valueOf(payment.amountMinor, 2).stripTrailingZeros().toPlainString()
                                        }, enabled = !busy) { Text("Attach BDO payment request link") }
                                    } else {
                                        Text("BDO Checkout link attached", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                                        OutlinedButton(onClick = {
                                            checkoutTarget = payment
                                            checkoutUrl = ""
                                            checkoutAmount = BigDecimal.valueOf(payment.amountMinor, 2).stripTrailingZeros().toPlainString()
                                        }, enabled = !busy) { Text("Replace payment request link") }
                                        OutlinedButton(onClick = {
                                            bdoConfirmTarget = payment
                                            bdoReference = ""
                                            bdoAmount = BigDecimal.valueOf(payment.amountMinor, 2).stripTrailingZeros().toPlainString()
                                        }, enabled = !busy) { Text("Verify paid transaction") }
                                    }
                                }
                                Text("Refunded ${formatAdminMoney(payment.refundedAmountMinor)} • Remaining ${formatAdminMoney(payment.refundableMinor)}", style = MaterialTheme.typography.bodySmall)
                                if (payment.refundableMinor > 0 && (payment.status == "SUCCEEDED" || payment.status == "PARTIALLY_REFUNDED")) {
                                    OutlinedButton(onClick = { refundTarget = payment; refundAmount = ""; refundReason = "" }, enabled = !busy) {
                                        Text("Create refund case")
                                    }
                                }
                            }
                        }
                    }
                    item { SectionTitle("Balances", "${balances.size} ledger accounts") }
                    if (balances.isEmpty()) item { EmptyFinanceText("No wallet balances have been posted.") }
                    items(balances, key = { it.accountId }) { account ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${account.kind} • ${account.ownerId.ifBlank { account.accountId }}", fontWeight = FontWeight.SemiBold)
                                Text(account.accountId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Available ${formatAdminMoney(account.availableMinor)}  •  Pending ${formatAdminMoney(account.pendingMinor)}")
                                Text("Cash owed ${formatAdminMoney(account.cashOwedMinor)}  •  Clearing ${formatAdminMoney(account.clearingMinor)}")
                            }
                        }
                    }
                    item { SectionTitle("Refund management", "${refunds.size} latest cases") }
                    if (refunds.isEmpty()) item { EmptyFinanceText("No refund cases are waiting for review.") }
                    items(refunds, key = { it.id }) { refund ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(formatAdminMoney(refund.amountMinor), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text(refund.status, color = MaterialTheme.colorScheme.tertiary)
                                }
                                Text(refund.reason, style = MaterialTheme.typography.bodySmall)
                                Text("Refund ${refund.id} • Payment ${refund.paymentId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                when (refund.status) {
                                    "REQUESTED" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { model.reviewRefund(refund.id, "REJECTED") }, enabled = !busy) { Text("Reject") }
                                        Button(onClick = { model.reviewRefund(refund.id, "APPROVED") }, enabled = !busy) { Text("Approve") }
                                    }
                                    "APPROVED", "PROCESSING" -> Button(onClick = { completionTarget = refund; providerReference = refund.providerReference.orEmpty() }, enabled = !busy) {
                                        Text("Record executed refund")
                                    }
                                }
                                refund.providerReference?.let { Text("Provider reference: $it", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                    item { SectionTitle("Administrative audit trail", "${audit.size} latest actions") }
                    if (audit.isEmpty()) item { EmptyFinanceText("No administrative financial actions recorded yet.") }
                    items(audit, key = { it.id }) { entry ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(entry.action.replace('_', ' '), fontWeight = FontWeight.SemiBold)
                            Text("${entry.targetType} • ${entry.targetId} • ${entry.actorUid}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (entry.note.isNotBlank()) Text(entry.note, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item { Spacer(Modifier.height(18.dp)) }
                }
            }
        }

        checkoutTarget?.let { payment ->
            AlertDialog(
                onDismissRequest = { checkoutTarget = null },
                title = { Text("Attach BDO Checkout link") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Create a Payment Request in the BDO Checkout portal for exactly ${formatAdminMoney(payment.amountMinor)}. Copy the one-time link and paste it here.")
                        OutlinedTextField(value = checkoutUrl, onValueChange = { checkoutUrl = it }, label = { Text("https://checkout.bdo.com.ph/…") }, singleLine = true)
                        OutlinedTextField(value = checkoutAmount, onValueChange = { checkoutAmount = it }, label = { Text("Exact amount (PHP)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    }
                },
                confirmButton = {
                    Button(onClick = { model.attachBdoCheckoutLink(payment.paymentId, checkoutUrl, checkoutAmount); checkoutTarget = null }, enabled = !busy && checkoutUrl.isNotBlank() && checkoutAmount.isNotBlank()) { Text("Attach link") }
                },
                dismissButton = { TextButton(onClick = { checkoutTarget = null }) { Text("Cancel") } }
            )
        }

        bdoConfirmTarget?.let { payment ->
            AlertDialog(
                onDismissRequest = { bdoConfirmTarget = null },
                title = { Text("Verify BDO GCash payment") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Only confirm after the BDO Checkout portal shows this exact payment as successful. Do not rely on the customer's return to the app.")
                        OutlinedTextField(value = bdoReference, onValueChange = { bdoReference = it }, label = { Text("BDO transaction reference") }, singleLine = true)
                        OutlinedTextField(value = bdoAmount, onValueChange = { bdoAmount = it }, label = { Text("Confirmed amount (PHP)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    }
                },
                confirmButton = {
                    Button(onClick = { model.confirmBdoGcashPayment(payment.paymentId, bdoReference, bdoAmount); bdoConfirmTarget = null }, enabled = !busy && bdoReference.isNotBlank() && bdoAmount.isNotBlank()) { Text("Confirm paid") }
                },
                dismissButton = { TextButton(onClick = { bdoConfirmTarget = null }) { Text("Cancel") } }
            )
        }

        refundTarget?.let { payment ->
            AlertDialog(
                onDismissRequest = { refundTarget = null },
                title = { Text("Create refund case") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Remaining refundable: ${formatAdminMoney(payment.refundableMinor)}")
                        OutlinedTextField(value = refundAmount, onValueChange = { refundAmount = it }, label = { Text("Refund amount (PHP)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        OutlinedTextField(value = refundReason, onValueChange = { refundReason = it }, label = { Text("Reason") })
                    }
                },
                confirmButton = {
                    Button(onClick = { model.createRefund(payment.paymentId, refundAmount, refundReason); refundTarget = null }, enabled = !busy && refundAmount.isNotBlank() && refundReason.isNotBlank()) { Text("Create case") }
                },
                dismissButton = { TextButton(onClick = { refundTarget = null }) { Text("Cancel") } }
            )
        }

        completionTarget?.let { refund ->
            AlertDialog(
                onDismissRequest = { completionTarget = null },
                title = { Text("Confirm external refund") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Only continue after you have actually refunded ${formatAdminMoney(refund.amountMinor)} through the provider/bank dashboard.")
                        OutlinedTextField(value = providerReference, onValueChange = { providerReference = it }, label = { Text("Provider/bank reference") }, singleLine = true)
                    }
                },
                confirmButton = {
                    Button(onClick = { model.completeRefund(refund.id, providerReference); completionTarget = null }, enabled = !busy && providerReference.isNotBlank()) { Text("Record completed") }
                },
                dismissButton = { TextButton(onClick = { completionTarget = null }) { Text("Cancel") } }
            )
        }

        if (showAdjustment) {
            AdjustmentDialog(onDismiss = { showAdjustment = false }, onSubmit = { values ->
                model.resolveDiscrepancy(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9], values[10])
                showAdjustment = false
            }, busy = busy)
        }
    }
}

@Composable
private fun SectionTitle(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyFinanceText(value: String) {
    Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AdjustmentDialog(onDismiss: () -> Unit, onSubmit: (List<String>) -> Unit, busy: Boolean) {
    val labels = listOf(
        "Unique reference ID", "Amount (PHP)", "Resolution note",
        "Source account", "Source bucket", "Source party type", "Source party ID",
        "Destination account", "Destination bucket", "Destination party type", "Destination party ID"
    )
    val values = remember { labels.map { mutableStateOf("") } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resolve ledger discrepancy") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(labels.size) { index ->
                    OutlinedTextField(
                        value = values[index].value,
                        onValueChange = { values[index].value = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(labels[index]) },
                        keyboardOptions = if (index == 1) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                        singleLine = index != 2
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(values.map { it.value.trim() }) }, enabled = !busy && values.all { it.value.isNotBlank() }) { Text("Post adjustment") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun formatAdminMoney(minor: Long): String {
    val pesos = minor / 100
    val centavos = kotlin.math.abs(minor % 100).toString().padStart(2, '0')
    return "₱$pesos.$centavos"
}
