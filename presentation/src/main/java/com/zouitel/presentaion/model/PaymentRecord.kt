package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

/** Read-only client representation of a server-owned payments/{orderId} record. */
@Serializable
data class PaymentRecord(
    val orderId: String = "",
    val clientId: String = "",
    val merchantId: String = "",
    val courierId: String = "",
    val method: PaymentMethod = PaymentMethod.CASH,
    val status: PaymentStatus = PaymentStatus.UNKNOWN,
    val amount: Money = Money(),
    val breakdown: OrderBreakdown? = null,
    val providerPaymentId: String = "",
    val refundAmount: Money = Money(),
    val updatedAtEpochMillis: Long = 0L
)