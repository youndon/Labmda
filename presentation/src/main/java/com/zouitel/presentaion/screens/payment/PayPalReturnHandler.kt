package com.zouitel.presentaion.screens.payment

import android.content.Intent
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
    fun parse(intent: Intent): PayPalReturn? = intent.data?.let(::parse)

    fun parse(uri: Uri): PayPalReturn? {
        if (uri.scheme != "labmda" || uri.host != "payment-return") return null
        val provider = uri.getQueryParameter("provider")?.lowercase()?.takeIf { it == "paypal" || it == "stripe" } ?: return null
        val orderId = uri.getQueryParameter("orderId")?.takeIf(String::isNotBlank) ?: return null
        val cancelled = uri.getQueryParameter("cancelled") == "1"
        val token = if (cancelled) "cancel"
            else uri.getQueryParameter("token")?.takeIf(String::isNotBlank) ?: return null
        val sessionId = if (cancelled) ""
            else uri.getQueryParameter("sessionId")?.takeIf(String::isNotBlank) ?: token
        return PayPalReturn(orderId, sessionId, token, provider)
    }
}