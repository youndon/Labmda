package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

@Serializable
enum class PaymentStatus {
    PENDING,
    PROCESSING,
    REQUIRES_ACTION,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REFUNDED,
    PARTIALLY_REFUNDED
}

@Serializable
data class PaymentRecord(
    val id: String = "",
    val clientId: String = "",
    val merchantId: String = "",
    val orderId: String = "",
    val amount: Double = 0.0,
    val currency: String = "USD",
    val status: PaymentStatus = PaymentStatus.PENDING,
    val paymentMethodId: String = "",
    val createdAtTimestamp: Long = 0L
)
