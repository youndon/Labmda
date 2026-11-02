package com.zouitel.presentaion.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
data class AdminPricingRide(
    val baseFareMinor: Long = 0,
    val perKmMinor: Long = 0,
    val perMinuteMinor: Long = 0,
    val minimumFareMinor: Long = 0,
    val platformCommissionBps: Int = 0
)

@Serializable
data class AdminPricingDelivery(
    val baseFeeMinor: Long = 0,
    val perKmMinor: Long = 0,
    val minimumFeeMinor: Long = 0,
    val platformCommissionBps: Int = 0
)

@Serializable
data class AdminPricingFood(val commissionBps: Int = 0)

@Serializable
data class AdminPricingShop(val commissionBps: Int = 0)

@Serializable
data class AdminPricingGcash(
    val rateBps: Int = 0,
    val fixedFeeMinor: Long = 0,
    val payer: String = "PLATFORM"
)

@Serializable
data class AdminPricingProviderFees(val GCASH: AdminPricingGcash = AdminPricingGcash())

@Serializable
data class AdminPricingConfig(
    val version: Int = 0,
    val currency: String = "PHP",
    val ride: AdminPricingRide = AdminPricingRide(),
    val delivery: AdminPricingDelivery = AdminPricingDelivery(),
    val food: AdminPricingFood = AdminPricingFood(),
    val shop: AdminPricingShop = AdminPricingShop(),
    val providerFees: AdminPricingProviderFees = AdminPricingProviderFees()
)

@Serializable
data class AdminPricingConfigResponse(
    val configured: Boolean = false,
    val config: AdminPricingConfig? = null
)

class AdminPricingScreenModel : ScreenModel {
    private val functions = Firebase.functions
    private val _config = MutableStateFlow<AdminPricingConfig?>(null)
    val config = _config.asStateFlow()
    private val _configured = MutableStateFlow(false)
    val configured = _configured.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (_loading.value) return
        screenModelScope.launch {
            _loading.value = true
            _message.value = null
            runCatching {
                val response = functions.httpsCallable("getAdminPricingConfig").invoke().data<AdminPricingConfigResponse>()
                _configured.value = response.configured
                _config.value = response.config
            }.onFailure { _message.value = it.message ?: "Unable to load pricing configuration." }
            _loading.value = false
        }
    }

    fun save(config: AdminPricingConfig, expectedVersion: Int) {
        if (_saving.value) return
        screenModelScope.launch {
            _saving.value = true
            _message.value = null
            runCatching {
                val response = functions.httpsCallable("updateAdminPricingConfig").invoke(mapOf("config" to config, "expectedVersion" to expectedVersion)).data<AdminPricingSaveResponse>()
                _config.value = response.config
                _configured.value = true
                _message.value = "Pricing saved as version ${response.config.version}. New quotations will use this version."
            }.onFailure { _message.value = it.message ?: "Pricing could not be saved." }
            _saving.value = false
        }
    }
}

@Serializable
data class AdminPricingSaveResponse(
    val success: Boolean = false,
    val config: AdminPricingConfig = AdminPricingConfig()
)

class AdminPricingScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val model = rememberScreenModel { AdminPricingScreenModel() }
        val config by model.config.collectAsState()
        val configured by model.configured.collectAsState()
        val loading by model.loading.collectAsState()
        val saving by model.saving.collectAsState()
        val message by model.message.collectAsState()

        var rideBase by remember(config) { mutableStateOf(config?.ride?.baseFareMinor?.let(::minorToPesos).orEmpty()) }
        var rideKm by remember(config) { mutableStateOf(config?.ride?.perKmMinor?.let(::minorToPesos).orEmpty()) }
        var rideMinute by remember(config) { mutableStateOf(config?.ride?.perMinuteMinor?.let(::minorToPesos).orEmpty()) }
        var rideMinimum by remember(config) { mutableStateOf(config?.ride?.minimumFareMinor?.let(::minorToPesos).orEmpty()) }
        var rideCommission by remember(config) { mutableStateOf(config?.ride?.platformCommissionBps?.toString().orEmpty()) }
        var deliveryBase by remember(config) { mutableStateOf(config?.delivery?.baseFeeMinor?.let(::minorToPesos).orEmpty()) }
        var deliveryKm by remember(config) { mutableStateOf(config?.delivery?.perKmMinor?.let(::minorToPesos).orEmpty()) }
        var deliveryMinimum by remember(config) { mutableStateOf(config?.delivery?.minimumFeeMinor?.let(::minorToPesos).orEmpty()) }
        var deliveryCommission by remember(config) { mutableStateOf(config?.delivery?.platformCommissionBps?.toString().orEmpty()) }
        var foodCommission by remember(config) { mutableStateOf(config?.food?.commissionBps?.toString().orEmpty()) }
        var shopCommission by remember(config) { mutableStateOf(config?.shop?.commissionBps?.toString().orEmpty()) }
        var gcashRate by remember(config) { mutableStateOf(config?.providerFees?.GCASH?.rateBps?.toString().orEmpty()) }
        var gcashFixed by remember(config) { mutableStateOf(config?.providerFees?.GCASH?.fixedFeeMinor?.let(::minorToPesos).orEmpty()) }
        var gcashPayer by remember(config) { mutableStateOf(config?.providerFees?.GCASH?.payer ?: "PLATFORM") }
        var validationError by remember { mutableStateOf<String?>(null) }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Commissions & Fees", fontWeight = FontWeight.Bold) },
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
            if (loading && config == null) {
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("PRICING CONTROL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text("Set the rates used by server-generated quotes.", style = MaterialTheme.typography.bodyMedium)
                            Text("Money is entered in PHP. Commission rates use basis points: 100 = 1%, 1,000 = 10%. Saving increments the pricing version.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!configured) {
                                Text("Pricing is not configured yet. Quotes will remain unavailable until approved rates are saved.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("Current version: ${config?.version ?: "—"}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    if (!message.isNullOrBlank()) item { Text(message.orEmpty(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                    if (!validationError.isNullOrBlank()) item { Text(validationError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                    item {
                        PricingSection("Ride fares", "Base fare, distance and time rates, minimum fare, and driver/platform split.") {
                            PricingInput("Base fare (₱)", rideBase, { rideBase = it })
                            PricingInput("Per kilometre (₱/km)", rideKm, { rideKm = it })
                            PricingInput("Per minute (₱/min)", rideMinute, { rideMinute = it })
                            PricingInput("Minimum fare (₱)", rideMinimum, { rideMinimum = it })
                            PricingInput("Platform commission (bps)", rideCommission, { rideCommission = it }, true)
                        }
                    }
                    item {
                        PricingSection("Delivery fees", "Distance-based customer delivery fee and courier/platform split.") {
                            PricingInput("Base fee (₱)", deliveryBase, { deliveryBase = it })
                            PricingInput("Per kilometre (₱/km)", deliveryKm, { deliveryKm = it })
                            PricingInput("Minimum fee (₱)", deliveryMinimum, { deliveryMinimum = it })
                            PricingInput("Platform commission (bps)", deliveryCommission, { deliveryCommission = it }, true)
                        }
                    }
                    item {
                        PricingSection("Order commissions", "Commission charged against merchant-funded order value.") {
                            PricingInput("Restaurant / food commission (bps)", foodCommission, { foodCommission = it }, true)
                            PricingInput("Shop commission (bps)", shopCommission, { shopCommission = it }, true)
                        }
                    }
                    item {
                        PricingSection("GCash provider fee", "Configure the provider fee charged for GCash checkout. Cash payments have no provider fee.") {
                            PricingInput("Rate (bps)", gcashRate, { gcashRate = it }, true)
                            PricingInput("Fixed fee (₱)", gcashFixed, { gcashFixed = it })
                            Text("Who pays the provider fee?", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (gcashPayer == "PLATFORM") Button(onClick = { gcashPayer = "PLATFORM" }, modifier = Modifier.weight(1f)) { Text("Platform") }
                                else OutlinedButton(onClick = { gcashPayer = "PLATFORM" }, modifier = Modifier.weight(1f)) { Text("Platform") }
                                if (gcashPayer == "CUSTOMER") Button(onClick = { gcashPayer = "CUSTOMER" }, modifier = Modifier.weight(1f)) { Text("Customer") }
                                else OutlinedButton(onClick = { gcashPayer = "CUSTOMER" }, modifier = Modifier.weight(1f)) { Text("Customer") }
                            }
                        }
                    }
                    item {
                        Button(
                            onClick = {
                                validationError = null
                                runCatching {
                                    fun pesos(value: String, field: String): Long {
                                        val clean = value.trim()
                                        require(Regex("^\\d+(?:\\.\\d{1,2})?$").matches(clean)) { "$field must be a PHP amount with up to 2 decimals." }
                                        val parts = clean.split(".")
                                        return (parts[0].toLong() * 100L) + (parts.getOrNull(1).orEmpty().padEnd(2, '0').take(2).toLong())
                                    }
                                    fun bps(value: String, field: String): Int = value.trim().toInt().also {
                                        require(it in 0..10000) { "$field must be between 0 and 10,000 bps." }
                                    }
                                    val next = AdminPricingConfig(
                                        ride = AdminPricingRide(pesos(rideBase, "Ride base fare"), pesos(rideKm, "Ride per km"), pesos(rideMinute, "Ride per minute"), pesos(rideMinimum, "Ride minimum"), bps(rideCommission, "Ride commission")),
                                        delivery = AdminPricingDelivery(pesos(deliveryBase, "Delivery base fee"), pesos(deliveryKm, "Delivery per km"), pesos(deliveryMinimum, "Delivery minimum"), bps(deliveryCommission, "Delivery commission")),
                                        food = AdminPricingFood(bps(foodCommission, "Food commission")),
                                        shop = AdminPricingShop(bps(shopCommission, "Shop commission")),
                                        providerFees = AdminPricingProviderFees(AdminPricingGcash(bps(gcashRate, "GCash rate"), pesos(gcashFixed, "GCash fixed fee"), gcashPayer))
                                    )
                                    model.save(next, config?.version ?: 0)
                                }.onFailure { validationError = it.message ?: "Check the pricing values." }
                            },
                            enabled = !saving && !loading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (saving) CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                            else Text(if (configured) "Save new pricing version" else "Save pricing configuration")
                        }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PricingSection(title: String, description: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun PricingInput(label: String, value: String, onValueChange: (String) -> Unit, integerOnly: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> if (next.length <= 12 && (next.isEmpty() || if (integerOnly) next.all { it.isDigit() } else next.all { it.isDigit() || it == '.' })) onValueChange(next) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (integerOnly) KeyboardType.Number else KeyboardType.Decimal)
    )
}

private fun minorToPesos(minor: Long): String = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"
