package com.zouitel.presentaion.screens.courier

import com.zouitel.presentaion.common.CartItem
import com.zouitel.presentaion.common.OrderStatus
import kotlinx.serialization.Serializable

@Serializable
data class Courier(
    val id: String = "",
    val orderId: String = "",
    val restaurantId: String = "",
    val restaurantName: String = "",
    val courierId: String = "",
    val courierName: String = "",
    val courierPhone: String = "",
    val customerAddress: String = "",
    val items: List<CartItem> = emptyList(),
    val totalPrice: Double = 0.0,
    val courierFee: Double = 0.0,
    val status: OrderStatus = OrderStatus.OUT_FOR_DELIVERY,
    val dispatchedAtTimestamp: Long = 0L,
    val deliveredAtTimestamp: Long = 0L
)