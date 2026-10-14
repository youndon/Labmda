package com.zouitel.presentaion.screens.shops

import com.zouitel.presentaion.model.OrderStatus
import kotlinx.serialization.Serializable

@Serializable
data class ShopCartItem(
    val productId: String = "",
    val productName: String = "",
    val price: Double = 0.0,
    val quantity: Int = 1,
    val specialInstructions: String = ""
) {
    val totalPrice: Double get() = price * quantity
}

@Serializable
data class ShopOrder(
    val id: String = "",
    val shopId: String = "",
    val shopName: String = "",
    val userId: String = "",
    val items: List<ShopCartItem> = emptyList(),
    val subtotal: Double = 0.0,
    val courierFee: Double = 3.00,
    val totalPrice: Double = 0.0,
    val status: OrderStatus = OrderStatus.PLACED,
    val deliveryAddress: String = "",
    val createdAtTimestamp: Long = 0L,
    val paymentMethod: String = "",
    val courierId: String = "",
    val courierName: String = "",
    val courierPhone: String = ""
)