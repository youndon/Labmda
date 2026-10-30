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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import com.zouitel.presentaion.model.Money
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.launch

class AdminPricingScreen : Screen {
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val functions = remember { Firebase.functions("asia-southeast1") }
        val scope = rememberCoroutineScope()
        val scrollState = rememberScrollState()

        var loading by remember { mutableStateOf(true) }
        var saving by remember { mutableStateOf(false) }
        var configured by remember { mutableStateOf(false) }
        var version by remember { mutableStateOf(0L) }
        var message by remember { mutableStateOf("") }

        var deliveryBase by remember { mutableStateOf("0.00") }
        var deliveryPerKm by remember { mutableStateOf("0.00") }
        var deliveryMinimum by remember { mutableStateOf("0.00") }
        var deliveryRoundingIncrement by remember { mutableStateOf("1") }
        var deliveryRoundingMode by remember { mutableStateOf("NEAREST") }

        var rideBase by remember { mutableStateOf("0.00") }
        var ridePerKm by remember { mutableStateOf("0.00") }
        var rideMinimum by remember { mutableStateOf("0.00") }
        var rideRoundingIncrement by remember { mutableStateOf("1") }
        var rideRoundingMode by remember { mutableStateOf("NEAREST") }

        var foodCommission by remember { mutableStateOf("0") }
        var goodsCommission by remember { mutableStateOf("0") }
        var deliveryCommission by remember { mutableStateOf("0") }
        var ridesCommission by remember { mutableStateOf("0") }

        var foodAdditional by remember { mutableStateOf("0.00") }
        var goodsAdditional by remember { mutableStateOf("0.00") }
        var deliveryAdditional by remember { mutableStateOf("0.00") }
        var ridesAdditional by remember { mutableStateOf("0.00") }

        var foodCancellation by remember { mutableStateOf("0.00") }
        var goodsCancellation by remember { mutableStateOf("0.00") }
        var deliveryCancellation by remember { mutableStateOf("0.00") }
        var ridesCancellation by remember { mutableStateOf("0.00") }
        var customerCancellationRate by remember { mutableStateOf("0") }
        var merchantCancellationRate by remember { mutableStateOf("0") }

        var freeCancellationBeforeAcceptance by remember { mutableStateOf(true) }
        var refundDeliveryFeeOnCancellation by remember { mutableStateOf(true) }
        var refundWindowHours by remember { mutableStateOf("24") }

        LaunchedEffect(Unit) {
            try {
                val response = functions.httpsCallable("getPricingConfiguration")()
                val result = response.data<Map<String, Any?>>()
                configured = result["configured"] as? Boolean ?: false
                val config = result["configuration"] as? Map<*, *>
                if (config != null) {
                    version = (config["version"] as? Number)?.toLong() ?: 0L
                    val delivery = config["delivery"] as? Map<*, *> ?: emptyMap<Any, Any>()
                    val rides = config["rides"] as? Map<*, *> ?: emptyMap<Any, Any>()
                    val ride = rides["ride"] as? Map<*, *> ?: emptyMap<Any, Any>()
                    val commissions = config["commissions"] as? Map<*, *> ?: emptyMap<Any, Any>()
                    val additional = config["additionalCharges"] as? Map<*, *> ?: emptyMap<Any, Any>()
                    val cancellation = config["cancellationFees"] as? Map<*, *> ?: emptyMap<Any, Any>()
                    val refunds = config["refundPolicy"] as? Map<*, *> ?: emptyMap<Any, Any>()

                    deliveryBase = amountText(delivery["baseFeeCentavos"])
                    deliveryPerKm = amountText(delivery["pricePerKmCentavos"])
                    deliveryMinimum = amountText(delivery["minimumFeeCentavos"])
                    deliveryRoundingIncrement = numberText(delivery["roundingIncrementCentavos"], "1")
                    deliveryRoundingMode = numberText(delivery["roundingMode"], "NEAREST")

                    rideBase = amountText(ride["baseFareCentavos"])
                    ridePerKm = amountText(ride["pricePerKmCentavos"])
                    rideMinimum = amountText(ride["minimumFareCentavos"])
                    rideRoundingIncrement = numberText(ride["roundingIncrementCentavos"], "1")
                    rideRoundingMode = numberText(ride["roundingMode"], "NEAREST")

                    foodCommission = percentText(commissions["foodBps"])
                    goodsCommission = percentText(commissions["goodsBps"])
                    deliveryCommission = percentText(commissions["deliveryBps"])
                    ridesCommission = percentText(commissions["ridesBps"])

                    foodAdditional = amountText(additional["foodCentavos"])
                    goodsAdditional = amountText(additional["goodsCentavos"])
                    deliveryAdditional = amountText(additional["deliveryCentavos"])
                    ridesAdditional = amountText(additional["ridesCentavos"])

                    foodCancellation = amountText(cancellation["foodCentavos"])
                    goodsCancellation = amountText(cancellation["goodsCentavos"])
                    deliveryCancellation = amountText(cancellation["deliveryCentavos"])
                    ridesCancellation = amountText(cancellation["ridesCentavos"])
                    customerCancellationRate = percentText(cancellation["customerCancellationBps"])
                    merchantCancellationRate = percentText(cancellation["merchantCancellationBps"])

                    freeCancellationBeforeAcceptance = refunds["freeCancellationBeforeAcceptance"] as? Boolean ?: true
                    refundDeliveryFeeOnCancellation = refunds["refundDeliveryFeeOnCancellation"] as? Boolean ?: true
                    refundWindowHours = numberText(refunds["refundWindowHours"], "24")
                }
            } catch (error: Exception) {
                message = error.message ?: "Could not load administrator pricing. Verify your admin access."
            } finally {
                loading = false
            }
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("Pricing & Commissions") }) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (configured) "Published pricing version $version" else "Pricing has not been published yet",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "All amounts are PHP. Route kilometres and final totals are calculated by Firebase Functions, not on this device.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (message.isNotBlank()) {
                    Text(message, color = MaterialTheme.colorScheme.error)
                }

                PricingSectionTitle("Delivery pricing")
                PricingField("Base delivery fee (₱)", deliveryBase) { deliveryBase = it }
                PricingField("Price per kilometre (₱)", deliveryPerKm) { deliveryPerKm = it }
                PricingField("Minimum delivery fee (₱)", deliveryMinimum) { deliveryMinimum = it }
                PricingField("Rounding increment (centavos)", deliveryRoundingIncrement, KeyboardType.Number) { deliveryRoundingIncrement = it }
                PricingField("Rounding mode (CEIL / NEAREST / FLOOR)", deliveryRoundingMode) { deliveryRoundingMode = it.uppercase() }

                HorizontalDivider()
                PricingSectionTitle("Ride pricing")
                PricingField("Base ride fare (₱)", rideBase) { rideBase = it }
                PricingField("Ride price per kilometre (₱)", ridePerKm) { ridePerKm = it }
                PricingField("Minimum ride fare (₱)", rideMinimum) { rideMinimum = it }
                PricingField("Rounding increment (centavos)", rideRoundingIncrement, KeyboardType.Number) { rideRoundingIncrement = it }
                PricingField("Rounding mode (CEIL / NEAREST / FLOOR)", rideRoundingMode) { rideRoundingMode = it.uppercase() }

                HorizontalDivider()
                PricingSectionTitle("Independent commissions (%)")
                PricingField("Food commission (%)", foodCommission) { foodCommission = it }
                PricingField("Goods commission (%)", goodsCommission) { goodsCommission = it }
                PricingField("Delivery commission (%)", deliveryCommission) { deliveryCommission = it }
                PricingField("Ride commission (%)", ridesCommission) { ridesCommission = it }

                HorizontalDivider()
                PricingSectionTitle("Additional charges (₱)")
                PricingField("Food additional charge", foodAdditional) { foodAdditional = it }
                PricingField("Goods additional charge", goodsAdditional) { goodsAdditional = it }
                PricingField("Delivery additional charge", deliveryAdditional) { deliveryAdditional = it }
                PricingField("Ride additional charge", ridesAdditional) { ridesAdditional = it }

                HorizontalDivider()
                PricingSectionTitle("Cancellation fees (₱)")
                PricingField("Food cancellation fee", foodCancellation) { foodCancellation = it }
                PricingField("Goods cancellation fee", goodsCancellation) { goodsCancellation = it }
                PricingField("Delivery cancellation fee", deliveryCancellation) { deliveryCancellation = it }
                PricingField("Ride cancellation fee", ridesCancellation) { ridesCancellation = it }
                PricingField("Customer cancellation rate (%)", customerCancellationRate) { customerCancellationRate = it }
                PricingField("Merchant cancellation rate (%)", merchantCancellationRate) { merchantCancellationRate = it }

                HorizontalDivider()
                PricingSectionTitle("Refund policy")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Free cancellation before acceptance", modifier = Modifier.weight(1f))
                    Switch(
                        checked = freeCancellationBeforeAcceptance,
                        onCheckedChange = { freeCancellationBeforeAcceptance = it }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Refund delivery fee on cancellation", modifier = Modifier.weight(1f))
                    Switch(
                        checked = refundDeliveryFeeOnCancellation,
                        onCheckedChange = { refundDeliveryFeeOnCancellation = it }
                    )
                }
                PricingField("Refund window (hours)", refundWindowHours, KeyboardType.Number) { refundWindowHours = it }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        message = ""
                        saving = true
                        scope.launch {
                            try {
                                val configuration = mapOf(
                                    "currency" to "PHP",
                                    "delivery" to mapOf(
                                        "baseFeeCentavos" to moneyCentavos(deliveryBase),
                                        "pricePerKmCentavos" to moneyCentavos(deliveryPerKm),
                                        "minimumFeeCentavos" to moneyCentavos(deliveryMinimum),
                                        "roundingIncrementCentavos" to integerValue(deliveryRoundingIncrement, "Delivery rounding increment"),
                                        "roundingMode" to deliveryRoundingMode
                                    ),
                                    "rides" to mapOf(
                                        "ride" to mapOf(
                                            "baseFareCentavos" to moneyCentavos(rideBase),
                                            "pricePerKmCentavos" to moneyCentavos(ridePerKm),
                                            "minimumFareCentavos" to moneyCentavos(rideMinimum),
                                            "roundingIncrementCentavos" to integerValue(rideRoundingIncrement, "Ride rounding increment"),
                                            "roundingMode" to rideRoundingMode,
                                            "additionalCharges" to emptyList<Map<String, Any>>()
                                        )
                                    ),
                                    "commissions" to mapOf(
                                        "foodBps" to percentBasisPoints(foodCommission),
                                        "goodsBps" to percentBasisPoints(goodsCommission),
                                        "deliveryBps" to percentBasisPoints(deliveryCommission),
                                        "ridesBps" to percentBasisPoints(ridesCommission)
                                    ),
                                    "additionalCharges" to mapOf(
                                        "foodCentavos" to moneyCentavos(foodAdditional),
                                        "goodsCentavos" to moneyCentavos(goodsAdditional),
                                        "deliveryCentavos" to moneyCentavos(deliveryAdditional),
                                        "ridesCentavos" to moneyCentavos(ridesAdditional)
                                    ),
                                    "cancellationFees" to mapOf(
                                        "foodCentavos" to moneyCentavos(foodCancellation),
                                        "goodsCentavos" to moneyCentavos(goodsCancellation),
                                        "deliveryCentavos" to moneyCentavos(deliveryCancellation),
                                        "ridesCentavos" to moneyCentavos(ridesCancellation),
                                        "customerCancellationBps" to percentBasisPoints(customerCancellationRate),
                                        "merchantCancellationBps" to percentBasisPoints(merchantCancellationRate)
                                    ),
                                    "refundPolicy" to mapOf(
                                        "freeCancellationBeforeAcceptance" to freeCancellationBeforeAcceptance,
                                        "refundDeliveryFeeOnCancellation" to refundDeliveryFeeOnCancellation,
                                        "refundWindowHours" to integerValue(refundWindowHours, "Refund window")
                                    )
                                )
                                val response = functions.httpsCallable("updatePricingConfiguration")(
                                    mapOf("configuration" to configuration)
                                )
                                val result = response.data<Map<String, Any?>>()
                                version = (result["version"] as? Number)?.toLong() ?: version + 1
                                configured = true
                                message = "Pricing version $version published. New quotes will use these rates; existing orders and trips keep their saved pricing."
                            } catch (error: Exception) {
                                message = error.message ?: "Could not publish pricing configuration."
                            } finally {
                                saving = false
                            }
                        }
                    },
                    enabled = !loading && !saving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (saving) {
                        CircularProgressIndicator()
                    } else {
                        Text("Publish pricing configuration")
                    }
                }

                if (loading) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun PricingSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun PricingField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Decimal,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun amountText(value: Any?): String {
    val centavos = (value as? Number)?.toLong() ?: 0L
    return Money(centavos).toPesoString()
}

private fun numberText(value: Any?, fallback: String): String =
    value?.toString() ?: fallback

private fun percentText(value: Any?): String {
    val basisPoints = (value as? Number)?.toLong() ?: 0L
    val whole = basisPoints / 100
    val fraction = (basisPoints % 100).toString().padStart(2, '0').trimEnd('0')
    return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
}

private fun moneyCentavos(value: String): Long =
    Money.parsePesos(value.ifBlank { "0" }).centavos.also {
        require(it >= 0L) { "Money amounts cannot be negative." }
    }

private fun integerValue(value: String, label: String): Long =
    value.trim().toLongOrNull()?.also {
        require(it >= 0L) { "$label must be a non-negative integer." }
    } ?: throw IllegalArgumentException("$label must be a valid integer.")

private fun percentBasisPoints(value: String): Long {
    val percent = BigDecimal(value.trim().ifBlank { "0" })
    require(percent >= BigDecimal.ZERO && percent <= BigDecimal("100")) {
        "Commission and cancellation rates must be between 0% and 100%."
    }
    return percent.multiply(BigDecimal("100"))
        .setScale(0, RoundingMode.UNNECESSARY)
        .longValueExact()
}
