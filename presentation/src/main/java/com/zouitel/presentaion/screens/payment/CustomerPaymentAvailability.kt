package com.zouitel.presentaion.screens.payment

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions
import kotlinx.serialization.Serializable

@Serializable
data class CustomerPaymentServices(
    val FOOD: Boolean = false,
    val SHOP: Boolean = false,
    val DELIVERY: Boolean = false,
    val RIDE: Boolean = false
)

@Serializable
data class CustomerPaymentConfiguration(
    val version: Int = 0,
    val provider: String = "",
    val currency: String = "PHP",
    val enabledMethods: List<String> = emptyList(),
    val services: CustomerPaymentServices = CustomerPaymentServices(),
    val checkoutMode: String = "",
    val payoutMode: String = ""
)

data class CustomerPaymentAvailability(
    val isLoading: Boolean = true,
    val serviceEnabled: Boolean = false,
    val methods: List<String> = emptyList(),
    val error: String? = null,
    val configurationVersion: Int = 0
)

/**
 * Reads the customer-safe, server-owned payment configuration.
 *
 * This is a UI availability hint only. Quote callables remain authoritative and
 * re-check both the selected payment method and service before creating a quote.
 */
@Composable
fun rememberCustomerPaymentAvailability(service: String): CustomerPaymentAvailability {
    var availability by remember(service) { mutableStateOf(CustomerPaymentAvailability()) }

    LaunchedEffect(service) {
        availability = CustomerPaymentAvailability()
        runCatching {
            Firebase.functions.httpsCallable("getPaymentConfiguration")
                .invoke()
                .data<CustomerPaymentConfiguration>()
        }.onSuccess { configuration ->
            val serviceEnabled = when (service) {
                "FOOD" -> configuration.services.FOOD
                "SHOP" -> configuration.services.SHOP
                "DELIVERY" -> configuration.services.DELIVERY
                "RIDE" -> configuration.services.RIDE
                else -> false
            }
            availability = CustomerPaymentAvailability(
                isLoading = false,
                serviceEnabled = serviceEnabled,
                methods = if (serviceEnabled) configuration.enabledMethods.distinct() else emptyList(),
                configurationVersion = configuration.version
            )
        }.onFailure { error ->
            availability = CustomerPaymentAvailability(
                isLoading = false,
                error = error.message ?: "Payment options are temporarily unavailable."
            )
        }
    }

    return availability
}
