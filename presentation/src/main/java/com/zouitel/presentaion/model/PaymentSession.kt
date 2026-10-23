package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

@Serializable
sealed class PaymentSession {
    abstract val orderId: String
    abstract val sessionId: String
    abstract val checkoutUrl: String
    abstract val expiresAtEpochMillis: Long
}

@Serializable
data class StripeSession(
    override val orderId: String,
    override val sessionId: String,
    override val checkoutUrl: String,
    override val expiresAtEpochMillis: Long = 0L
) : PaymentSession()

@Serializable
data class PayPalSession(
    override val orderId: String,
    override val sessionId: String,
    override val checkoutUrl: String,
    override val expiresAtEpochMillis: Long = 0L
) : PaymentSession()