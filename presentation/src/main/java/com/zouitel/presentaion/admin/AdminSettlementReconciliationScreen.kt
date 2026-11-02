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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
data class BdoTransferResult(
    val transferId: String = "",
    val status: String = "",
    val matchedType: String? = null,
    val matchedId: String? = null,
    val duplicate: Boolean = false
)

@Serializable
data class BdoReconciliationCounts(
    val bankTransfers: Int = 0,
    val matched: Int = 0,
    val unmatched: Int = 0,
    val expectedSettlements: Int = 0,
    val unsettledSettlements: Int = 0,
    val paidPayouts: Int = 0,
    val unreconciledPayouts: Int = 0
)

@Serializable
data class BdoReconciliationStatement(
    val openingBalanceMinor: Long = 0,
    val closingBalanceMinor: Long = 0,
    val ledgerMovementMinor: Long = 0,
    val expectedClosingBalanceMinor: Long = 0,
    val bankBalanceDifferenceMinor: Long = 0
)

@Serializable
data class BdoReconciliationReport(
    val id: String = "",
    val balanced: Boolean = false,
    val counts: BdoReconciliationCounts = BdoReconciliationCounts(),
    val statement: BdoReconciliationStatement = BdoReconciliationStatement(),
    val totals: Map<String, Long> = emptyMap()
)

class AdminSettlementReconciliationModel : ScreenModel {
    private val functions = Firebase.functions
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _report = MutableStateFlow<BdoReconciliationReport?>(null)
    val report = _report.asStateFlow()

    fun createExpected(reference: String, amount: String, settlementId: String) = perform {
        val payload = mutableMapOf<String, Any>(
            "transferReference" to reference.trim(),
            "amountMinor" to pesosToMinor(amount),
            "source" to "BDO"
        )
        if (settlementId.isNotBlank()) payload["settlementId"] = settlementId.trim()
        functions.httpsCallable("createExpectedBdoSettlement").invoke(payload)
        "Expected BDO settlement saved. It will remain outstanding until an exact statement line matches."
    }

    fun recordTransfer(reference: String, amount: String, direction: String, date: String, note: String) = perform {
        val timestamp = parseDate(date)
        val result = functions.httpsCallable("recordBdoBankTransfer").invoke(
            mapOf(
                "transferReference" to reference.trim(),
                "amountMinor" to pesosToMinor(amount),
                "direction" to direction,
                "transactionAt" to timestamp,
                "note" to note.trim()
            )
        ).data<BdoTransferResult>()
        "Statement line recorded: ${result.status.lowercase(Locale.US)}" +
            (result.matchedId?.let { " (${result.matchedType}: $it)" } ?: ".")
    }

    fun reconcile(opening: String, closing: String, startDate: String, endDate: String) = perform {
        val result = functions.httpsCallable("reconcileBdoSettlements").invoke(
            mapOf(
                "openingBalanceMinor" to pesosToMinorAllowZero(opening),
                "closingBalanceMinor" to pesosToMinorAllowZero(closing),
                "periodStartAt" to parseDate(startDate),
                "periodEndAt" to parseDate(endDate, endOfDay = true)
            )
        ).data<BdoReconciliationReport>()
        _report.value = result
        if (result.balanced) "BDO statement reconciliation is balanced."
        else "Reconciliation saved with outstanding matches or a bank-balance difference. Review the report below."
    }

    private fun perform(block: suspend () -> String) {
        if (_busy.value) return
        screenModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching { block() }
                .onSuccess { _message.value = it }
                .onFailure { _message.value = it.message ?: "The financial operation failed." }
            _busy.value = false
        }
    }

    private fun pesosToMinor(value: String): Long = convertPesos(value, allowZero = false)
    private fun pesosToMinorAllowZero(value: String): Long = convertPesos(value, allowZero = true)

    private fun convertPesos(value: String, allowZero: Boolean): Long {
        val parsed = runCatching { BigDecimal(value.trim()).setScale(2, RoundingMode.UNNECESSARY) }
            .getOrElse { throw IllegalArgumentException("Enter a PHP amount with no more than two decimal places.") }
        val minor = parsed.movePointRight(2).longValueExact()
        require(if (allowZero) minor >= 0 else minor > 0) { "Enter a valid PHP amount." }
        return minor
    }

    private fun parseDate(value: String, endOfDay: Boolean = false): Long {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
        val parsed = formatter.parse(value.trim()) ?: throw IllegalArgumentException("Use date format YYYY-MM-DD.")
        val calendar = java.util.Calendar.getInstance().apply {
            time = parsed
            if (endOfDay) {
                set(java.util.Calendar.HOUR_OF_DAY, 23)
                set(java.util.Calendar.MINUTE, 59)
                set(java.util.Calendar.SECOND, 59)
                set(java.util.Calendar.MILLISECOND, 999)
            } else {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
        }
        return calendar.timeInMillis
    }
}

class AdminSettlementReconciliationScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val model = rememberScreenModel { AdminSettlementReconciliationModel() }
        val busy by model.busy.collectAsState()
        val message by model.message.collectAsState()
        val report by model.report.collectAsState()

        var expectedReference by remember { mutableStateOf("") }
        var expectedAmount by remember { mutableStateOf("") }
        var settlementId by remember { mutableStateOf("") }
        var bankReference by remember { mutableStateOf("") }
        var bankAmount by remember { mutableStateOf("") }
        var direction by remember { mutableStateOf("INBOUND") }
        var transactionDate by remember { mutableStateOf("") }
        var bankNote by remember { mutableStateOf("") }
        var openingBalance by remember { mutableStateOf("") }
        var closingBalance by remember { mutableStateOf("") }
        var periodStart by remember { mutableStateOf("") }
        var periodEnd by remember { mutableStateOf("") }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Manual Payout Reconciliation", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { report?.let { report } }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Report")
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!message.isNullOrBlank()) item {
                    Text(message.orEmpty(), color = MaterialTheme.colorScheme.primary)
                }

                item {
                    SectionCard("1. Expected BDO settlement", "Register the amount and reference expected to arrive in the company's BDO account.") {
                        OutlinedTextField(expectedReference, { expectedReference = it }, Modifier.fillMaxWidth(), label = { Text("Expected transfer reference") }, singleLine = true)
                        OutlinedTextField(expectedAmount, { expectedAmount = it }, Modifier.fillMaxWidth(), label = { Text("Expected amount (PHP)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        OutlinedTextField(settlementId, { settlementId = it }, Modifier.fillMaxWidth(), label = { Text("Settlement ID (optional)") }, singleLine = true)
                        Button(
                            onClick = { model.createExpected(expectedReference, expectedAmount, settlementId) },
                            enabled = !busy && expectedReference.isNotBlank() && expectedAmount.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Save expected settlement") }
                    }
                }

                item {
                    SectionCard("2. Record actual bank statement line", "Enter a real BDO statement transaction. A matching reference and exact amount are required; unmatched lines remain visible in the reconciliation report.") {
                        OutlinedTextField(bankReference, { bankReference = it }, Modifier.fillMaxWidth(), label = { Text("Bank transfer reference") }, singleLine = true)
                        OutlinedTextField(bankAmount, { bankAmount = it }, Modifier.fillMaxWidth(), label = { Text("Statement amount (PHP)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { direction = "INBOUND" }, enabled = !busy) { Text(if (direction == "INBOUND") "✓ Inbound" else "Inbound") }
                            OutlinedButton(onClick = { direction = "OUTBOUND" }, enabled = !busy) { Text(if (direction == "OUTBOUND") "✓ Outbound" else "Outbound") }
                        }
                        OutlinedTextField(transactionDate, { transactionDate = it }, Modifier.fillMaxWidth(), label = { Text("Transaction date (YYYY-MM-DD)") }, singleLine = true)
                        OutlinedTextField(bankNote, { bankNote = it }, Modifier.fillMaxWidth(), label = { Text("Note (optional)") }, singleLine = true)
                        Button(
                            onClick = { model.recordTransfer(bankReference, bankAmount, direction, transactionDate, bankNote) },
                            enabled = !busy && bankReference.isNotBlank() && bankAmount.isNotBlank() && transactionDate.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Record statement line") }
                    }
                }

                item {
                    SectionCard("3. Reconcile statement period", "Use the opening and closing balances shown on the actual BDO statement. The period includes all recorded bank movements between the selected dates.") {
                        OutlinedTextField(openingBalance, { openingBalance = it }, Modifier.fillMaxWidth(), label = { Text("Opening balance (PHP)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        OutlinedTextField(closingBalance, { closingBalance = it }, Modifier.fillMaxWidth(), label = { Text("Closing balance (PHP)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        OutlinedTextField(periodStart, { periodStart = it }, Modifier.fillMaxWidth(), label = { Text("Period start (YYYY-MM-DD)") }, singleLine = true)
                        OutlinedTextField(periodEnd, { periodEnd = it }, Modifier.fillMaxWidth(), label = { Text("Period end (YYYY-MM-DD)") }, singleLine = true)
                        Button(
                            onClick = { model.reconcile(openingBalance, closingBalance, periodStart, periodEnd) },
                            enabled = !busy && openingBalance.isNotBlank() && closingBalance.isNotBlank() && periodStart.isNotBlank() && periodEnd.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Run BDO reconciliation") }
                    }
                }

                report?.let { result ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Latest reconciliation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(if (result.balanced) "BALANCED" else "ACTION REQUIRED", color = if (result.balanced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                Text("Matched bank lines: ${result.counts.matched} / ${result.counts.bankTransfers}")
                                Text("Unmatched bank lines: ${result.counts.unmatched}")
                                Text("Expected settlements outstanding: ${result.counts.unsettledSettlements}")
                                Text("Paid payouts without a matched BDO debit: ${result.counts.unreconciledPayouts}")
                                Text("Opening balance: ${formatSettlementPhp(result.statement.openingBalanceMinor)}")
                                Text("Ledger movement: ${formatSettlementPhp(result.statement.ledgerMovementMinor)}")
                                Text("Expected closing: ${formatSettlementPhp(result.statement.expectedClosingBalanceMinor)}")
                                Text("Actual closing: ${formatSettlementPhp(result.statement.closingBalanceMinor)}")
                                Text("Difference: ${formatSettlementPhp(result.statement.bankBalanceDifferenceMinor)}")
                                Text("Report ID: ${result.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                item {
                    Text(
                        "This screen records manual evidence only. It does not send GCash/BDO payouts, fetch bank statements, or prove that a transfer occurred. Record a payout as paid only after completing the transfer in the bank or GCash channel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (busy) {
            // The screen remains interactive only through disabled action buttons while a callable is running.
        }
    }
}

@Composable
private fun SectionCard(title: String, description: String, content: @Composable Column.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

private fun formatSettlementPhp(minor: Long): String {
    val pesos = minor / 100
    val centavos = kotlin.math.abs(minor % 100).toString().padStart(2, '0')
    return "₱$pesos.$centavos"
}
