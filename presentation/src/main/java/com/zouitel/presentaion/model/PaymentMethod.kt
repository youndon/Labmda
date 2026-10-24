package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

@Serializable
enum class PaymentMethodType {
    STRIPE_CARD,
    PAYPAL,
    CASH,
    CARD_ON_DELIVERY,
    STRIPE
}

@Serializable
data class UserPaymentMethod(
    val id: String = "",
    val userId: String = "",
    val type: PaymentMethodType = PaymentMethodType.STRIPE_CARD,
    val title: String = "", // e.g. "•••• 4242" or "user@paypal.com"
    val subtitle: String = "", // e.g. "Expires 12/28" or "PayPal Account"
    val identifier: String = "", // card last4 or paypal email
    val cardHolderName: String = "",
    val expiryMonth: String = "",
    val expiryYear: String = "",
    val isDefault: Boolean = false,
    val createdAtTimestamp: Long = 0L
)
