package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

/**
 * A checkout session returned by the trusted payment backend.
 *
 * GCash sessions normally contain a hosted checkoutUrl. Cash payments do not require
 * a redirect URL, but use the same type so checkout code can share one result contract.
 * Provider credentials and secret API keys must never be included here.
 */
@Serializable
data class PaymentSession(
    val id: String = "",
    val paymentId: String = "",
    val paymentMethod: PaymentMethod = PaymentMethod.GCASH,
    val providerReference: String = "",
    val checkoutUrl: String? = null,
    val expiresAtTimestamp: Long? = null,
    val createdAtTimestamp: Long = 0L
) {
    val requiresRedirect: Boolean
        get() = paymentMethod == PaymentMethod.GCASH && !checkoutUrl.isNullOrBlank()
}
