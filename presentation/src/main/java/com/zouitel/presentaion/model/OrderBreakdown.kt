package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

/** Server-computed split. Clients may display this but must never use it to settle a payment. */
@Serializable
data class OrderBreakdown(
    val merchantShare: Money = Money(),
    val courierShare: Money = Money(),
    val platformShare: Money = Money(),
    val gatewayFee: Money = Money()
)