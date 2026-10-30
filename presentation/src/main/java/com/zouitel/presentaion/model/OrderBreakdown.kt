package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

/**
 * Server-calculated order or ride totals and participant allocations.
 *
 * Every Money field is an integer amount in Philippine centavos. The backend returns
 * these same field names as integer values so the object can be decoded consistently
 * by food, goods, delivery and ride flows.
 */
@Serializable
data class OrderBreakdown(
    val subtotal: Money = Money.ZERO,
    val deliveryFee: Money = Money.ZERO,
    val serviceFee: Money = Money.ZERO,
    val discount: Money = Money.ZERO,
    val tax: Money = Money.ZERO,
    val total: Money = Money.ZERO,
    val merchantShare: Money = Money.ZERO,
    val courierShare: Money = Money.ZERO,
    val driverShare: Money = Money.ZERO,
    val platformShare: Money = Money.ZERO,
    val gatewayFee: Money = Money.ZERO,
    val merchantCommission: Money = Money.ZERO,
    val deliveryCommission: Money = Money.ZERO,
    val rideCommission: Money = Money.ZERO
)
