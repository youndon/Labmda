package com.zouitel.presentaion.screens.payment

import android.net.Uri

/**
 * Parses a deep-link return. A return URL is untrusted input; callers must ask Cloud Functions
 * to verify/capture the provider session before showing a successful payment status.
 */
data class PayPalReturn(
    val orderId: String,
    val sessionId: String,
    val resultToken: String
)

class PayPalReturnHandler {
    fun parse(uri: Uri): PayPalReturn? {
        if (uri.scheme != "labmda" || uri.host != "payment-return") return null
        if (uri.getQueryParameter("provider") != "paypal") return null
        val orderId = uri.getQueryParameter("orderId")?.takeIf(String::isNotBlank) ?: return null
        val sessionId = uri.getQueryParameter("sessionId")?.takeIf(String::isNotBlank) ?: return null
        val token = uri.getQueryParameter("token")?.takeIf(String::isNotBlank) ?: return null
        return PayPalReturn(orderId, sessionId, token)
    }
}