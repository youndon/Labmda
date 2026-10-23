package com.zouitel.presentaion.screens.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator

class MerchantEarningsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val model = koinScreenModel<MerchantEarningsScreenModel>()
        val wallet by model.wallet.collectAsState()
        val loading by model.isLoading.collectAsState()
        val error by model.error.collectAsState()
        LaunchedEffect(Unit) { model.load() }
        Scaffold(topBar = {
            TopAppBar(title = { Text("Merchant earnings") }, navigationIcon = {
                IconButton(onClick = { navigator?.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                wallet?.let { value ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Available: ${value.summary.availableBalance.format()}", style = MaterialTheme.typography.titleLarge)
                            Text("Pending: ${value.summary.pendingBalance.format()}")
                            Text("Lifetime earnings: ${value.summary.lifetimeEarnings.format()}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { navigator?.push(PayoutAccountScreen()) }) { Text("Payout account") }
                                Button(onClick = { model.requestPayout(value.summary.availableBalance) }) { Text("Request payout") }
                            }
                            Text("Payouts are validated by the server and require an approved merchant account.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("Transactions", style = MaterialTheme.typography.titleMedium)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(value.transactions, key = { it.id }) { transaction ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(transaction.description.ifBlank { transaction.type })
                                    Text("${transaction.amount.format()} • ${transaction.status}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                } ?: if (!loading && error == null) Text("No wallet information is available yet.")
            }
        }
    }
}