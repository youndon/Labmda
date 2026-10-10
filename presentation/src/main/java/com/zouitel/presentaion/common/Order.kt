package com.zouitel.presentaion.common

import kotlinx.serialization.Serializable

@Serializable
enum class OrderType {
    FOOD,
    RETAIL
}

@Serializable
enum class OrderStatus {
    PLACED,
    ACCEPTED,
    PREPARING,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED
}

@Serializable
data class CartItem(
    val id: String = "",
    val name: String = "",
    val basePrice: Double = 0.0,
    val quantity: Int = 1,
    val selectedOptions: List<SelectedOption> = emptyList(),
    val specialInstructions: String = ""
) {
    val unitPrice: Double get() = basePrice + selectedOptions.sumOf { it.priceDelta }
    val totalPrice: Double get() = unitPrice * quantity
}

@Serializable
data class SelectedOption(
    val optionGroupId: String = "",
    val optionItemId: String = "",
    val optionName: String = "",
    val priceDelta: Double = 0.0
)

@Serializable
data class Order(
    val id: String = "",
    val userId: String = "",
    val orderType: OrderType = OrderType.FOOD,
    val soldBy: String = "",
    val soldId: String = "",
    //----------------------------
    val restaurantId: String = "",
    val restaurantName: String = "",
    val shopId: String = "",
    val shopName: String = "",
    //-*-------------------------------
    val items: List<CartItem> = emptyList(),
    val subtotal: Double = 0.0,
    val courierFee: Double = 0.0,
    val totalPrice: Double = 0.0,
    val status: OrderStatus = OrderStatus.PLACED,
    val deliveryAddress: String = "",
    val createdAtTimestamp: Long = 0L,
    val paymentMethod: String = "",
    val courierId: String = "",
    val courierName: String = "",
    val courierPhone: String = ""
) {
    // Helper to extract the business fullName regardless of whether it's a restaurant or store
    val businessName: String get() = if (orderType == OrderType.RETAIL) shopName.ifBlank { restaurantName } else restaurantName
    val businessId: String get() = if (orderType == OrderType.RETAIL) shopId.ifBlank { restaurantId } else restaurantId
}