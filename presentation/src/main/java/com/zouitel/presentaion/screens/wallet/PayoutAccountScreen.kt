package com.zouitel.presentaion.screens.wallet

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

class PayoutAccountScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val model = koinScreenModel<PayoutAccountScreenModel>()
        val account by model.account.collectAsState()
        val loading by model.isLoading.collectAsState()
        val error by model.error.collectAsState()
        val saved by model.saved.collectAsState()
        var provider by remember { mutableStateOf("stripe_connect") }
        var accountId by remember { mutableStateOf("") }
        var country by remember { mutableStateOf("") }
        var label by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { model.load() }
        LaunchedEffect(account) {
            account?.let {
                provider = it.provider
                accountId = it.providerAccountId
                country = it.countryCode
                label = it.accountLabel
            }
        }
        Scaffold(topBar = {
            TopAppBar(title = { Text("Payout account") }, navigationIcon = {
                IconButton(onClick = { navigator?.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Save only the provider's opaque account reference. Do not enter card numbers, bank logins, or secret keys.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(provider, { provider = it }, label = { Text("Payout provider") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(accountId, { accountId = it }, label = { Text("Connected account ID") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(country, { country = it.uppercase().take(2) }, label = { Text("Country code") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(label, { label = it }, label = { Text("Display label") }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (saved) Text("Payout account update accepted.", color = MaterialTheme.colorScheme.primary)
                Button(
                    onClick = { model.save(PayoutAccount(provider.trim(), accountId.trim(), country.trim(), label.trim())) },
                    enabled = !loading && provider.isNotBlank() && accountId.isNotBlank() && country.length == 2,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (loading) CircularProgressIndicator() else Text("Save payout account")
                }
            }
        }
    }
}