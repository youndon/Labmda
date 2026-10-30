package com.zouitel.presentaion.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import com.zouitel.presentaion.payments.PaymentRepository
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.coroutines.launch

private data class CashCollectionRow(
    val collectionId: String,
    val entityType: String,
    val entityId: String,
    val collectorId: String,
    val collectorRole: String,
    val amountCentavos: Long,
    val merchantOwedCentavos: Long,
    val platformOwedCentavos: Long,
    val collectorEarningsCentavos: Long,
    val remittedCentavos: Long,
    val outstandingRemittanceCentavos: Long,
    val status: String,
    val remittanceStatus: String,
    val createdAtTimestamp: Long
)

class CashLedgerScreen : Screen {
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val db = remember { Firebase.database.reference() }
        val payments = remember { PaymentRepository() }
        val scope = rememberCoroutineScope()
        val rowsScroll = rememberScrollState()

        var collections by remember { mutableStateOf<List<CashCollectionRow>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            try {
                db.child("cashCollections").valueEvents.collect { snapshot ->
                    collections = snapshot.children.mapNotNull { child ->
                        val id = child.key ?: return@mapNotNull null
                        fun string(key: String): String =
                            runCatching { child.child(key).value<String?>() ?: "" }.getOrDefault("")
                        fun long(key: String): Long {
                            val value = child.child(key)
                            return runCatching { value.value<Long?>() }.getOrNull()
                                ?: runCatching { value.value<Double?>()?.toLong() }.getOrNull()
                                ?: 0L
                        }
                        CashCollectionRow(
                            collectionId = id,
                            entityType = string("entityType"),
                            entityId = string("entityId"),
                            collectorId = string("collectorId"),
                            collectorRole = string("collectorRole"),
                            amountCentavos = long("collectedAmountCentavos"),
                            merchantOwedCentavos = long("merchantOwedCentavos"),
                            platformOwedCentavos = long("platformOwedCentavos"),
                            collectorEarningsCentavos = long("collectorEarningsCentavos"),
                            remittedCentavos = long("remittedCentavos"),
                            outstandingRemittanceCentavos = long("outstandingRemittanceCentavos"),
                            status = string("status"),
                            remittanceStatus = string("cashRemittanceStatus"),
                            createdAtTimestamp = long("collectedAtTimestamp")
                        )
                    }.sortedByDescending { it.createdAtTimestamp }
                    loading = false
                }
            } catch (e: Exception) {
                error = e.message ?: "Could not read the cash ledger. Check administrator access."
                loading = false
            }
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("Cash Ledger & Remittances") }) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rowsScroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Cash collection journals are immutable. Record only the amount physically received from the assigned courier or driver.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (message.isNotBlank()) {
                    Text(message, color = MaterialTheme.colorScheme.primary)
                }
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                if (loading) {
                    CircularProgressIndicator()
                } else if (collections.isEmpty()) {
                    Text("No cash collections have been recorded yet.")
                } else {
                    collections.forEach { row ->
                        CashCollectionCard(
                            row = row,
                            onRemit = { amountText, idempotencyKey, onFinished ->
                                val amountCentavos = try {
                                    BigDecimal(amountText.trim())
                                        .multiply(BigDecimal("100"))
                                        .setScale(0, RoundingMode.UNNECESSARY)
                                        .longValueExact()
                                } catch (_: Exception) {
                                    message = "Enter a valid amount in PHP with up to two decimal places."
                                    onFinished(false)
                                    return@CashCollectionCard
                                }
                                if (amountCentavos <= 0L || amountCentavos > row.outstandingRemittanceCentavos) {
                                    message = "Remittance must be greater than zero and cannot exceed the outstanding amount."
                                    onFinished(false)
                                    return@CashCollectionCard
                                }
                                scope.launch {
                                    message = ""
                                    try {
                                        val result = payments.recordCashRemittance(
                                            entityType = row.entityType,
                                            entityId = row.entityId,
                                            amountCentavos = amountCentavos,
                                            idempotencyKey = idempotencyKey
                                        )
                                        message = "Recorded ₱${peso(amountCentavos)} for ${row.entityType.lowercase()} ${row.entityId}. Remaining due: ₱${peso(result.outstandingRemittanceCentavos)}."
                                        onFinished(true)
                                    } catch (e: Exception) {
                                        message = e.message ?: "Could not record the cash remittance."
                                        onFinished(false)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CashCollectionCard(
    row: CashCollectionRow,
    onRemit: (String, String, (Boolean) -> Unit) -> Unit
) {
    var amount by remember(row.collectionId) {
        mutableStateOf((row.outstandingRemittanceCentavos / 100.0).let { "%.2f".format(it) })
    }
    var requestKey by remember(row.collectionId) {
        mutableStateOf(UUID.randomUUID().toString())
    }
    var isSubmitting by remember(row.collectionId) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "${row.entityType} · ${row.entityId}",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Collected by ${row.collectorRole.lowercase()} · ${row.collectorId}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Cash collected: ₱${peso(row.amountCentavos)}")
            if (row.entityType == "ORDER") {
                Text("Merchant payable: ₱${peso(row.merchantOwedCentavos)}")
            }
            Text("Platform commission/fees: ₱${peso(row.platformOwedCentavos)}")
            Text("Collector earnings: ₱${peso(row.collectorEarningsCentavos)}")
            Text("Already remitted: ₱${peso(row.remittedCentavos)}")
            Text(
                "Outstanding to platform: ₱${peso(row.outstandingRemittanceCentavos)} · ${row.remittanceStatus.ifBlank { "DUE" }}",
                color = if (row.outstandingRemittanceCentavos > 0L) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
            )
            if (row.outstandingRemittanceCentavos > 0L) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = {
                        if (it != amount) requestKey = UUID.randomUUID().toString()
                        amount = it
                    },
                    label = { Text("Amount received (₱)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (!isSubmitting) {
                            isSubmitting = true
                            onRemit(amount, requestKey) { succeeded ->
                                isSubmitting = false
                                if (succeeded) requestKey = UUID.randomUUID().toString()
                            }
                        }
                    },
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isSubmitting) "Recording…" else "Record cash remittance")
                }
            }
        }
    }
}

private fun peso(centavos: Long): String = "%.2f".format(centavos / 100.0)
