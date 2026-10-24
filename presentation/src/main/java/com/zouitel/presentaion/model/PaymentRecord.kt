package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

/**
 * Server-owned payment ledger projection. Amounts and splits are represented in
 * currency minor units and must never be used as client-authored settlement values.
 */
@Serializable
data class PaymentRecord(
    val orderId: String = "",
    val clientId: String = "",
    val merchantId: String = "",
    val courierId: String = "",
    val method: PaymentMethod = PaymentMethod.CASH,
    val provider: PaymentMethod = PaymentMethod.CASH,
    val providerReference: String = "",
    val status: PaymentStatus = PaymentStatus.UNKNOWN,
    val amount: Money = Money(),
    val breakdown: OrderBreakdown? = null,
    val breakdownStatus: String = "UNALLOCATED",
    val providerPaymentId: String = "",
    val refundAmount: Money = Money(),
    val updatedAtEpochMillis: Long = 0L
)
