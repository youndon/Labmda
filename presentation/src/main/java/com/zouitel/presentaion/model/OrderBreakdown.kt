package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

/**
 * Server-calculated money totals and allocation for an order or ride.
 *
 * For a ride, subtotal can represent the fare and driverShare the driver's share.
 * For food or goods, merchantShare and courierShare represent the corresponding
 * allocations. Values are stored in Philippine centavos through Money.
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
    val gatewayFee: Money = Money.ZERO
)
