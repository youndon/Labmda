package com.zouitel.presentaion.screens.payment

import android.net.Uri

/**
 * Parses a deep-link return. A return URL is untrusted input; callers must ask Cloud Functions
 * to verify/capture the provider session before showing a successful payment status.
 */
data class PayPalReturn(
    val orderId: String,
    val sessionId: String,
    val resultToken: String,
    val provider: String = "paypal"
)

class PayPalReturnHandler {
    fun parse(uri: Uri): PayPalReturn? {
        if (uri.scheme != "labmda" || uri.host != "payment-return") return null
        val provider = uri.getQueryParameter("provider")?.lowercase()?.takeIf { it == "paypal" || it == "stripe" } ?: return null
        val orderId = uri.getQueryParameter("orderId")?.takeIf(String::isNotBlank) ?: return null
        val token = uri.getQueryParameter("token")?.takeIf(String::isNotBlank) ?: return null
        val sessionId = uri.getQueryParameter("sessionId")?.takeIf(String::isNotBlank)
            ?: token.takeIf { it != "cancel" }.orEmpty()
        return PayPalReturn(orderId, sessionId, token, provider)
    }
}