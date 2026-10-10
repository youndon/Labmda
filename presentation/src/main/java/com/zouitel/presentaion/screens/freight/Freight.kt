package com.zouitel.presentaion.screens.freight

import com.zouitel.presentaion.common.CartItem
import com.zouitel.presentaion.common.OrderStatus
import kotlinx.serialization.Serializable

@Serializable
data class Freight(
    val id: String = "",
    val orderId: String = "",
    val businessId: String = "",
    val businessName: String = "",
    val freightId: String = "",
    val freightName: String = "",
    val freightPhone: String = "",
    val customerAddress: String = "",
    val items: List<CartItem> = emptyList(),
    val totalPrice: Double = 0.0,
    val freightFee: Double = 0.0,
    val status: OrderStatus = OrderStatus.OUT_FOR_DELIVERY,
    val dispatchedAtTimestamp: Long = 0L,
    val deliveredAtTimestamp: Long = 0L
)