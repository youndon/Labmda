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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class AdminPaymentServices(
    val FOOD: Boolean = true,
    val SHOP: Boolean = true,
    val DELIVERY: Boolean = true,
    val RIDE: Boolean = true
)

@Serializable
data class AdminPaymentConfiguration(
    val version: Int = 1,
    val provider: String = "BDO_CHECKOUT",
    val currency: String = "PHP",
    val enabledMethods: List<String> = listOf("GCASH", "CASH"),
    val services: AdminPaymentServices = AdminPaymentServices(),
    val checkoutMode: String = "MANUAL_LINK",
    val payoutMode: String = "MANUAL"
)

@Serializable
data class AdminPaymentConfigurationSaveResponse(
    val saved: Boolean = false,
    val version: Int = 0,
    val enabledMethods: List<String> = emptyList(),
    val services: AdminPaymentServices = AdminPaymentServices()
)

class AdminPaymentSettingsScreenModel : ScreenModel {
    private val functions = Firebase.functions
    private val _configuration = MutableStateFlow<AdminPaymentConfiguration?>(null)
    val configuration = _configuration.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (_loading.value || _saving.value) return
        screenModelScope.launch {
            _loading.value = true
            _message.value = null
            runCatching {
                _configuration.value = functions.httpsCallable("getPaymentConfiguration")
                    .invoke().data<AdminPaymentConfiguration>()
            }.onFailure {
                _message.value = it.message ?: "Unable to load payment configuration."
            }
            _loading.value = false
        }
    }

    fun save(methods: Set<String>, services: AdminPaymentServices) {
        if (_saving.value || _loading.value) return
        if (methods.isEmpty()) {
            _message.value = "Keep at least one payment method enabled."
            return
        }
        screenModelScope.launch {
            _saving.value = true
            _message.value = null
            runCatching {
                val result = functions.httpsCallable("updateAdminPaymentConfiguration").invoke(
                    mapOf(
                        "enabledMethods" to methods.sorted(),
                        "services" to mapOf(
                            "FOOD" to services.FOOD,
                            "SHOP" to services.SHOP,
                            "DELIVERY" to services.DELIVERY,
                            "RIDE" to services.RIDE
                        )
                    )
                ).data<AdminPaymentConfigurationSaveResponse>()
                val previous = _configuration.value ?: AdminPaymentConfiguration()
                _configuration.value = previous.copy(
                    version = result.version,
                    enabledMethods = result.enabledMethods,
                    services = result.services
                )
                _message.value = "Payment settings saved as version ${result.version}."
            }.onFailure {
                _message.value = it.message ?: "Payment settings could not be saved."
            }
            _saving.value = false
        }
    }
}

class AdminPaymentSettingsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val model = rememberScreenModel { AdminPaymentSettingsScreenModel() }
        val configuration by model.configuration.collectAsState()
        val loading by model.loading.collectAsState()
        val saving by model.saving.collectAsState()
        val message by model.message.collectAsState()

        val current = configuration ?: AdminPaymentConfiguration()
        val methods = current.enabledMethods.toSet()
        val services = current.services

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Payment Settings", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { model.refresh() }, enabled = !loading && !saving) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                )
            }
        ) { padding ->
            if (loading && configuration == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
            } else {
                androidx.compose.runtime.key(current.version) {
                    var gcashEnabled by androidx.compose.runtime.remember(current) {
                        androidx.compose.runtime.mutableStateOf("GCASH" in methods)
                    }
                    var cashEnabled by androidx.compose.runtime.remember(current) {
                        androidx.compose.runtime.mutableStateOf("CASH" in methods)
                    }
                    var foodEnabled by androidx.compose.runtime.remember(current) {
                        androidx.compose.runtime.mutableStateOf(services.FOOD)
                    }
                    var shopEnabled by androidx.compose.runtime.remember(current) {
                        androidx.compose.runtime.mutableStateOf(services.SHOP)
                    }
                    var deliveryEnabled by androidx.compose.runtime.remember(current) {
                        androidx.compose.runtime.mutableStateOf(services.DELIVERY)
                    }
                    var rideEnabled by androidx.compose.runtime.remember(current) {
                        androidx.compose.runtime.mutableStateOf(services.RIDE)
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Collection provider", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text("BDO Checkout", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("Customer currency: PHP • Checkout: manual BDO payment request link", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Provider payouts: manual administrator transfers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Configuration version: ${current.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item { Text("Customer payment methods", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                        item {
                            SettingsToggle("GCash", "Online collection through BDO Checkout", gcashEnabled, !saving) { gcashEnabled = it }
                        }
                        item {
                            SettingsToggle("Cash", "Cash collection by the assigned courier or driver", cashEnabled, !saving) { cashEnabled = it }
                        }
                        item { HorizontalDivider() }
                        item { Text("Enabled services", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                        item {
                            SettingsToggle("Food orders", "Restaurant ordering and delivery", foodEnabled, !saving) { foodEnabled = it }
                        }
                        item {
                            SettingsToggle("Shop orders", "Retail and shop ordering", shopEnabled, !saving) { shopEnabled = it }
                        }
                        item {
                            SettingsToggle("Delivery", "Standalone courier bookings", deliveryEnabled, !saving) { deliveryEnabled = it }
                        }
                        item {
                            SettingsToggle("Rides", "Passenger ride bookings", rideEnabled, !saving) { rideEnabled = it }
                        }
                        if (!message.isNullOrBlank()) {
                            item { Text(message.orEmpty(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                        }
                        item {
                            Button(
                                onClick = {
                                    model.save(
                                        buildSet {
                                            if (gcashEnabled) add("GCASH")
                                            if (cashEnabled) add("CASH")
                                        },
                                        AdminPaymentServices(foodEnabled, shopEnabled, deliveryEnabled, rideEnabled)
                                    )
                                },
                                enabled = !saving && !loading && (gcashEnabled || cashEnabled),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (saving) CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                                Text(if (saving) "Saving…" else "Save payment settings")
                            }
                        }
                        item {
                            OutlinedButton(
                                onClick = { model.refresh() },
                                enabled = !saving && !loading,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Discard local changes / Reload") }
                        }
                        item {
                            Text(
                                "BDO API checkout and automated payouts are intentionally unavailable until BDO approves and documents those integrations.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}
