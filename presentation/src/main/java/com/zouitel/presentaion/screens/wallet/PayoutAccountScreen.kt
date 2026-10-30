package com.zouitel.presentaion.screens.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
        val state by model.state.collectAsState()
        var givenName by remember { mutableStateOf("") }
        var surname by remember { mutableStateOf("") }
        var holder by remember { mutableStateOf("") }
        var gcashNumber by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var street by remember { mutableStateOf("") }
        var city by remember { mutableStateOf("") }
        var province by remember { mutableStateOf("") }
        var postal by remember { mutableStateOf("") }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Payout Account", fontWeight = FontWeight.Bold) },
                    navigationIcon = { IconButton(onClick = { navigator?.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
                )
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Labmda currently supports GCash wallet payouts. Enter the recipient details exactly as registered to the GCash account.", style = MaterialTheme.typography.bodyMedium)
                Text("Status: " + state.account.status.replace('_', ' '), fontWeight = FontWeight.Bold)
                if (state.account.maskedAccountNumber.isNotBlank()) Text("Saved account: " + state.account.maskedAccountNumber)
                if (state.account.reviewNote.isNotBlank()) Text("Admin note: " + state.account.reviewNote)
                if (state.isLoading) CircularProgressIndicator()
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                OutlinedTextField(givenName, { givenName = it }, label = { Text("Recipient first name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(surname, { surname = it }, label = { Text("Recipient last name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(holder, { holder = it }, label = { Text("GCash account holder name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(gcashNumber, { gcashNumber = it }, label = { Text("GCash wallet number") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(phone, { phone = it }, label = { Text("Recipient Philippine mobile number") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(street, { street = it }, label = { Text("Street address") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(city, { city = it }, label = { Text("City") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(province, { province = it }, label = { Text("Province") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(postal, { postal = it }, label = { Text("Postal code") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("The account number is encrypted on the server and is not returned to the app. Submission requires administrator review before payout.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = { model.save(givenName, surname, holder, gcashNumber, phone, street, city, province, postal) },
                    enabled = !state.isSaving && listOf(givenName, surname, holder, gcashNumber, phone, street, city, province, postal).all { it.isNotBlank() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (state.isSaving) "Submitting…" else "Submit GCash payout account") }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
