package com.zouitel.presentaion.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator

class AdminCommissionScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val model = koinScreenModel<AdminCommissionScreenModel>()
        val state by model.state.collectAsState()
        var commissionBps by remember { mutableStateOf("1000") }
        var courierBps by remember { mutableStateOf("0") }
        var currency by remember { mutableStateOf("USD") }
        LaunchedEffect(Unit) { model.load() }
        LaunchedEffect(state.settings) {
            state.settings?.let {
                commissionBps = it.platformCommissionBps.toString()
                courierBps = it.courierShareBps.toString()
                currency = it.currencyCode
            }
        }
        Scaffold(topBar = {
            TopAppBar(title = { Text("Commission settings") }, navigationIcon = {
                IconButton(onClick = { navigator?.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Rates are basis points: 100 = 1%. These settings apply only to new server-priced orders.", style = MaterialTheme.typography.bodyMedium)
                if (state.isLoading) CircularProgressIndicator()
                OutlinedTextField(currency, { currency = it.uppercase().take(3) }, label = { Text("Currency code") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(commissionBps, { commissionBps = it.filter(Char::isDigit).take(5) }, label = { Text("Platform commission (bps)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(courierBps, { courierBps = it.filter(Char::isDigit).take(5) }, label = { Text("Courier share (bps)") }, modifier = Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Button(
                    onClick = {
                        val platform = commissionBps.toIntOrNull() ?: return@Button
                        val courier = courierBps.toIntOrNull() ?: return@Button
                        model.save(CommissionSettings(currency, platform, courier))
                    },
                    enabled = !state.isSaving && currency.length == 3 &&
                        (commissionBps.toIntOrNull() ?: 10001) in 0..10000 &&
                        (courierBps.toIntOrNull() ?: 10001) in 0..10000 &&
                        (commissionBps.toIntOrNull() ?: 10001) + (courierBps.toIntOrNull() ?: 10001) <= 10000,
                    modifier = Modifier.fillMaxWidth()
                ) { if (state.isSaving) CircularProgressIndicator() else Text("Save commission settings") }
            }
        }
    }
}