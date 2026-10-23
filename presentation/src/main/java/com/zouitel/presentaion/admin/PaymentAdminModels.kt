package com.zouitel.presentaion.admin

import kotlinx.serialization.Serializable

@Serializable
data class PaymentLedgerResponse(
    val payments: List<com.zouitel.presentaion.model.PaymentRecord> = emptyList(),
    val nextCursor: String? = null
)

@Serializable
data class CommissionSettings(
    val currencyCode: String = "USD",
    val platformCommissionBps: Int = 1000,
    val courierShareBps: Int = 0,
    val updatedAtEpochMillis: Long = 0L
)