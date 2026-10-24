package com.zouitel.presentaion.screens.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

        var provider by remember { mutableStateOf("") }
        var providerAccountId by remember { mutableStateOf("") }
        var countryCode by remember { mutableStateOf("") }
        var accountLabel by remember { mutableStateOf("") }

        LaunchedEffect(Unit) { model.load() }
        LaunchedEffect(account) {
            account?.let {
                provider = it.provider
                providerAccountId = it.providerAccountId
                countryCode = it.countryCode
                accountLabel = it.accountLabel
            }
        }

        val valid = provider.isNotBlank() && providerAccountId.isNotBlank() && countryCode.length == 2

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Payout account") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Save the payout provider and its provider-issued account reference. Do not enter bank/card numbers, passwords, or secret keys.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = provider,
                    onValueChange = { provider = it },
                    label = { Text("Provider (for example, STRIPE_CONNECT)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !loading
                )
                OutlinedTextField(
                    value = providerAccountId,
                    onValueChange = { providerAccountId = it },
                    label = { Text("Provider account reference") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !loading
                )
                OutlinedTextField(
                    value = countryCode,
                    onValueChange = { countryCode = it.filter(Char::isLetter).take(2).uppercase() },
                    label = { Text("Country code (2 letters)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !loading
                )
                OutlinedTextField(
                    value = accountLabel,
                    onValueChange = { accountLabel = it },
                    label = { Text("Display label (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !loading
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (saved) Text("Payout account details saved.", color = MaterialTheme.colorScheme.primary)
                if (loading) CircularProgressIndicator()
                Button(
                    onClick = {
                        model.save(
                            PayoutAccount(
                                provider = provider.trim(),
                                providerAccountId = providerAccountId.trim(),
                                countryCode = countryCode.trim().uppercase(),
                                accountLabel = accountLabel.trim(),
                                updatedAtEpochMillis = System.currentTimeMillis()
                            )
                        )
                    },
                    enabled = !loading && valid,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save payout account")
                }
            }
        }
    }
}
