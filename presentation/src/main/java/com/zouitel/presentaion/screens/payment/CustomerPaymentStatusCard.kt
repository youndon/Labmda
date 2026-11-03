package com.zouitel.presentaion.screens.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * Customer-facing payment state for order and ride tracking.
 *
 * This component displays only the state persisted by trusted backend operations.
 * Opening a checkout URL never changes the payment state.
 */
@Serializable
private data class XenditCheckoutResponse(
    val paymentId: String = "",
    val status: String = "",
    val amountMinor: Long = 0,
    val currency: String = "PHP",
    val checkoutUrl: String = "",
    val providerRequestId: String = ""
)

@Composable
fun CustomerPaymentStatusCard(
    paymentMethod: String,
    paymentId: String = "",
    paymentStatus: String,
    checkoutUrl: String,
    modifier: Modifier = Modifier
) {
    val method = paymentMethod.trim().uppercase()
    val status = paymentStatus.trim().uppercase()
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var activeCheckoutUrl by remember(checkoutUrl) { mutableStateOf(checkoutUrl) }
    var startingCheckout by remember(paymentId) { mutableStateOf(false) }
    var checkoutError by remember(paymentId) { mutableStateOf<String?>(null) }
    val validCheckoutLink = isSecureCheckoutUrl(activeCheckoutUrl)

    val presentation = when (status) {
        "PAID", "SUCCEEDED", "COLLECTED" -> PaymentStatusPresentation(
            title = "Payment confirmed",
            detail = "Labmda has confirmed this payment.",
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer
        )
        "CASH_DUE" -> PaymentStatusPresentation(
            title = "Cash payment due",
            detail = "Pay the assigned courier or driver when they collect or complete the service.",
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer
        )
        "PENDING", "PENDING_PAYMENT", "PROCESSING" -> PaymentStatusPresentation(
            title = if (method == "GCASH") "GCash payment pending" else "Payment pending",
            detail = if (method == "GCASH") {
                "Complete checkout if a payment link is available. Labmda will show payment as confirmed only after verification."
            } else {
                "Waiting for the assigned collector to confirm the payment."
            },
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer
        )
        "FAILED" -> PaymentStatusPresentation(
            title = "Payment failed",
            detail = "No successful payment was recorded. Contact support before trying another payment.",
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer
        )
        "CANCELLED" -> PaymentStatusPresentation(
            title = "Payment cancelled",
            detail = "This payment was cancelled.",
            container = MaterialTheme.colorScheme.surfaceContainerLow,
            content = MaterialTheme.colorScheme.onSurface
        )
        "EXPIRED" -> PaymentStatusPresentation(
            title = "Payment link expired",
            detail = "This checkout is no longer valid. Contact support for the next step.",
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer
        )
        "REFUNDED" -> PaymentStatusPresentation(
            title = "Payment refunded",
            detail = "A refund has been recorded for this payment.",
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer
        )
        "PARTIALLY_REFUNDED" -> PaymentStatusPresentation(
            title = "Partially refunded",
            detail = "A partial refund has been recorded for this payment.",
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer
        )
        else -> PaymentStatusPresentation(
            title = "Payment status unavailable",
            detail = "Refresh this screen later. Do not pay again until the payment status is confirmed.",
            container = MaterialTheme.colorScheme.surfaceContainerLow,
            content = MaterialTheme.colorScheme.onSurface
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = presentation.container)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Payment · " + when (method) {
                    "GCASH" -> "GCash"
                    "CASH" -> "Cash"
                    else -> "Not specified"
                },
                style = MaterialTheme.typography.labelMedium,
                color = presentation.content
            )
            Text(
                text = presentation.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = presentation.content
            )
            Text(
                text = presentation.detail,
                style = MaterialTheme.typography.bodySmall,
                color = presentation.content
            )

            if (method == "GCASH" && status in setOf("PENDING", "PENDING_PAYMENT", "PROCESSING")) {
                Button(
                    enabled = !startingCheckout && (validCheckoutLink || paymentId.isNotBlank()),
                    onClick = {
                        if (validCheckoutLink) {
                            uriHandler.openUri(activeCheckoutUrl)
                        } else {
                            scope.launch {
                                startingCheckout = true
                                checkoutError = null
                                runCatching {
                                    Firebase.functions.httpsCallable("createXenditGcashCheckout")(
                                        mapOf("paymentId" to paymentId)
                                    ).data<XenditCheckoutResponse>()
                                }.onSuccess { result ->
                                    if (result.status == "SUCCEEDED") {
                                        checkoutError = "Payment is already confirmed. Refresh this screen."
                                    } else if (isSecureCheckoutUrl(result.checkoutUrl)) {
                                        activeCheckoutUrl = result.checkoutUrl
                                        uriHandler.openUri(result.checkoutUrl)
                                    } else {
                                        checkoutError = "Xendit has not returned a checkout link. Refresh payment status before trying again."
                                    }
                                }.onFailure { error ->
                                    checkoutError = error.message ?: "Could not start GCash checkout."
                                }
                                startingCheckout = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            startingCheckout -> "Starting checkout…"
                            validCheckoutLink -> "Continue to GCash checkout"
                            else -> "Start GCash checkout"
                        }
                    )
                }
                Text(
                    "After paying, return to Labmda. The authenticated Xendit notification confirms payment; returning from checkout does not.",
                    style = MaterialTheme.typography.labelSmall,
                    color = presentation.content
                )
                checkoutError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private data class PaymentStatusPresentation(
    val title: String,
    val detail: String,
    val container: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color
)

private fun isSecureCheckoutUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() &&
        !uri.path.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)
