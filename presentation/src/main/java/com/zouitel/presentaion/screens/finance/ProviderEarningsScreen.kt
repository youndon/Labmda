package com.zouitel.presentaion.screens.finance

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProviderEarningsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val model = rememberScreenModel { ProviderEarningsScreenModel() }
        val earnings by model.earnings.collectAsState()
        val profile by model.profile.collectAsState()
        val payouts by model.payouts.collectAsState()
        val analytics by model.analytics.collectAsState()
        val analyticsPeriod by model.analyticsPeriod.collectAsState()
        val loading by model.isLoading.collectAsState()
        val submitting by model.isSubmitting.collectAsState()
        val message by model.message.collectAsState()

        var showProfileDialog by remember { mutableStateOf(false) }
        var payoutAmount by remember { mutableStateOf("") }
        var selectedAccountId by remember { mutableStateOf("") }
        var selectedTransactionFilter by remember { mutableStateOf("ALL") }
        var selectedTransaction by remember { mutableStateOf<FinancialTransaction?>(null) }
        val transactionFilters = listOf("ALL", "EARNINGS", "PAYOUTS", "CASH", "OTHER")
        val filteredTransactions = earnings.transactions.filter { transactionMatchesFilter(it, selectedTransactionFilter) }

        LaunchedEffect(earnings.accounts) {
            if (selectedAccountId.isBlank() || earnings.accounts.none { it.accountId == selectedAccountId }) {
                selectedAccountId = earnings.accounts.firstOrNull()?.accountId.orEmpty()
            }
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Earnings & Payouts", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { model.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh earnings")
                        }
                    }
                )
            }
        ) { padding ->
            if (loading) {
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
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Available to withdraw", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text(
                                    formatPhp(earnings.totals.availableMinor),
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text("Your earnings are recorded in PHP.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }

                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            BalanceTile("Pending", earnings.totals.pendingMinor, Modifier.weight(1f))
                            BalanceTile("Cash owed", earnings.totals.cashOwedMinor, Modifier.weight(1f))
                        }
                    }
                    item {
                        BalanceTile("Reserved for payouts", earnings.totals.clearingMinor, Modifier.fillMaxWidth())
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("Earnings analytics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text("Posted earnings from your financial ledger", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilterChip(selected = analyticsPeriod == "WEEK", onClick = { model.loadAnalytics("WEEK") }, label = { Text("7D") })
                                        FilterChip(selected = analyticsPeriod == "MONTH", onClick = { model.loadAnalytics("MONTH") }, label = { Text("30D") })
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                    BalanceTile("Earned", analytics.totals.earnedMinor, Modifier.weight(1f))
                                    BalanceTile("Payouts reserved", analytics.totals.payoutMinor, Modifier.weight(1f))
                                }
                                EarningsBarChart(analytics.days)
                                if (analytics.days.isNotEmpty()) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(analytics.days.first().date.takeLast(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(analytics.days[analytics.days.size / 2].date.takeLast(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(analytics.days.last().date.takeLast(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AccountBalance, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.padding(4.dp))
                                    Text("Payout destination", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                                if (profile.configured) {
                                    Text(if (profile.method == "GCASH") "GCash wallet" else "BDO bank account", fontWeight = FontWeight.SemiBold)
                                    Text(profile.accountName.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                                    Text(profile.maskedAccountNumber.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text("Add a GCash wallet or BDO bank account to request a payout.", style = MaterialTheme.typography.bodyMedium)
                                }
                                OutlinedButton(onClick = { showProfileDialog = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (profile.configured) "Update payout destination" else "Set up payout destination")
                                }
                            }
                        }
                    }

                    if (earnings.accounts.size > 1) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Select earnings wallet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    earnings.accounts.forEach { account ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(
                                                selected = selectedAccountId == account.accountId,
                                                onClick = { selectedAccountId = account.accountId }
                                            )
                                            Column {
                                                Text(account.providerKind, fontWeight = FontWeight.SemiBold)
                                                Text("Available: ${formatPhp(account.availableMinor)}", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Request a payout", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Choose an amount within the selected wallet’s available balance. An administrator reviews the request and manually sends the transfer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedTextField(
                                    value = payoutAmount,
                                    onValueChange = { payoutAmount = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Amount (PHP)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                                Button(
                                    onClick = { model.requestPayout(payoutAmount, selectedAccountId) },
                                    enabled = profile.configured && selectedAccountId.isNotBlank() && !submitting && earnings.accounts.any { it.accountId == selectedAccountId && it.availableMinor > 0 },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (submitting) "Submitting…" else "Submit payout request")
                                }
                            }
                        }
                    }

                    if (!message.isNullOrBlank()) {
                        item {
                            Text(message.orEmpty(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    item {
                        Text("Payout history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    if (payouts.isEmpty()) {
                        item {
                            Text("No payout requests yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        items(payouts, key = { it.payoutId }) { payout ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(formatPhp(payout.amountMinor), fontWeight = FontWeight.Bold)
                                        Text(payout.status, color = when (payout.status) {
                                            "PAID" -> MaterialTheme.colorScheme.primary
                                            "REJECTED" -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.tertiary
                                        }, fontWeight = FontWeight.SemiBold)
                                    }
                                    Text("${payout.method} • ${payout.maskedAccountNumber}", style = MaterialTheme.typography.bodySmall)
                                    if (!payout.transferReference.isNullOrBlank()) {
                                        Text("Transfer reference: ${payout.transferReference}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (!payout.adminNote.isNullOrBlank()) {
                                        Text(payout.adminNote.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Transaction history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                transactionFilters.forEach { filter ->
                                    FilterChip(
                                        selected = selectedTransactionFilter == filter,
                                        onClick = { selectedTransactionFilter = filter },
                                        label = { Text(filter.lowercase().replaceFirstChar { it.uppercase() }) }
                                    )
                                }
                            }
                            Text(
                                "${filteredTransactions.size} transactions shown",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (filteredTransactions.isEmpty()) {
                        item {
                            Text(
                                if (earnings.transactions.isEmpty()) "Your financial activity will appear here when earnings are posted." else "No transactions in this category.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(filteredTransactions, key = { it.operationId }) { transaction ->
                            Column(
                                Modifier.fillMaxWidth().clickable { selectedTransaction = transaction }.padding(vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(transaction.eventType.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                                        Text(transaction.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                    }
                                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(transaction.referenceType.ifBlank { "Activity" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(formatTransactionDate(transaction.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }

        if (showProfileDialog) {
            PayoutProfileDialog(
                initialMethod = profile.method ?: "GCASH",
                initialName = profile.accountName.orEmpty(),
                onDismiss = { showProfileDialog = false },
                onSave = { method, name, number ->
                    model.savePayoutProfile(method, name, number)
                    showProfileDialog = false
                }
            )
        }

        selectedTransaction?.let { transaction ->
            AlertDialog(
                onDismissRequest = { selectedTransaction = null },
                title = { Text(transaction.eventType.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(transaction.description.ifBlank { "Financial activity" })
                        Text("Date: ${formatTransactionDate(transaction.createdAt)}", style = MaterialTheme.typography.bodySmall)
                        Text("Reference: ${transaction.referenceType} / ${transaction.referenceId}", style = MaterialTheme.typography.bodySmall)
                        Divider()
                        Text("Ledger entries", fontWeight = FontWeight.SemiBold)
                        transaction.postings.forEach { posting ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(posting.bucket, style = MaterialTheme.typography.bodyMedium)
                                    Text(posting.partyType, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    "${if (posting.deltaMinor >= 0) "+" else ""}${formatPhp(posting.deltaMinor)}",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { selectedTransaction = null }) { Text("Close") } }
            )
        }
    }
}

@Composable
private fun BalanceTile(label: String, amountMinor: Long, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatPhp(amountMinor), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PayoutProfileDialog(
    initialMethod: String,
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var method by remember { mutableStateOf(initialMethod) }
    var accountName by remember { mutableStateOf(initialName) }
    var accountNumber by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Payout destination") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = method == "GCASH", onClick = { method = "GCASH" })
                    Text("GCash")
                    RadioButton(selected = method == "BDO", onClick = { method = "BDO" })
                    Text("BDO")
                }
                OutlinedTextField(
                    value = accountName,
                    onValueChange = { accountName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Account holder name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = accountNumber,
                    onValueChange = { accountNumber = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (method == "GCASH") "GCash mobile number" else "BDO account number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(method, accountName, accountNumber) },
                enabled = accountName.isNotBlank() && accountNumber.isNotBlank()
            ) { Text("Save destination") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


@Composable
private fun EarningsBarChart(days: List<EarningsAnalyticsDay>) {
    val maximum = (days.maxOfOrNull { it.earnedMinor } ?: 0L).coerceAtLeast(1L)
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxWidth().height(132.dp)) {
        if (days.isEmpty()) return@Canvas
        val slot = size.width / days.size
        val barWidth = (slot * 0.56f).coerceAtLeast(2f)
        days.forEachIndexed { index, day ->
            val fraction = (day.earnedMinor.toDouble() / maximum.toDouble()).toFloat().coerceIn(0f, 1f)
            val barHeight = (size.height - 8f) * fraction
            val left = index * slot + (slot - barWidth) / 2f
            drawRoundRect(
                color = barColor,
                topLeft = Offset(left, size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

private fun formatPhp(minor: Long): String {
    val pesos = minor / 100
    val centavos = kotlin.math.abs(minor % 100).toString().padStart(2, '0')
    return "₱$pesos.$centavos"
}

private fun transactionMatchesFilter(transaction: FinancialTransaction, filter: String): Boolean {
    val event = transaction.eventType.uppercase()
    return when (filter) {
        "EARNINGS" -> event.contains("EARNING") || event.contains("COMMISSION") || event == "CUSTOMER_PAYMENT"
        "PAYOUTS" -> event.contains("PAYOUT")
        "CASH" -> event.contains("CASH") || event.contains("COLLECTION") || event.contains("REMITTANCE")
        "OTHER" -> !(event.contains("EARNING") || event.contains("COMMISSION") || event == "CUSTOMER_PAYMENT" || event.contains("PAYOUT") || event.contains("CASH") || event.contains("COLLECTION") || event.contains("REMITTANCE"))
        else -> true
    }
}

private fun formatTransactionDate(timestamp: Long): String {
    if (timestamp <= 0L) return "Date unavailable"
    return SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(timestamp))
}
