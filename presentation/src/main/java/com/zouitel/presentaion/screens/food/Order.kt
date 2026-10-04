package com.zouitel.presentaion.screens.food

import kotlinx.serialization.Serializable

@Serializable
enum class OrderStatus {
    PLACED,             // Customer submitted order
    ACCEPTED,           // Restaurant confirmed order
    PREPARING,          // Kitchen is cooking
    OUT_FOR_DELIVERY,   // Driver picked up order
    DELIVERED,          // Successfully delivered
    CANCELLED           // Rejected by admin or cancelled by user
}

@Serializable
data class SelectedOption(
    val optionGroupId: String,
    val optionItemId: String,
    val optionName: String,
    val priceDelta: Double
)

@Serializable
data class CartItem(
    val mealId: String,
    val mealName: String,
    val basePrice: Double,
    val quantity: Int,
    val selectedOptions: List<SelectedOption>,
    val specialInstructions: String = ""
) {
    val unitPrice: Double get() = basePrice + selectedOptions.sumOf { it.priceDelta }
    val totalPrice: Double get() = unitPrice * quantity
}

@Serializable
data class Order(
    val id: String = "",
    val userId: String = "",
    val restaurantId: String = "",
    val restaurantName: String = "",
    val items: List<CartItem> = emptyList(),
    val subtotal: Double = 0.0,
    val deliveryFee: Double = 0.0,
    val totalPrice: Double = 0.0,
    val status: OrderStatus = OrderStatus.PLACED,
    val deliveryAddress: String = "",
    val createdAtTimestamp: Long = 0L,
    val fcmToken: String = "", // Device token for targeted push notifications
    val paymentMethod: String = "", // UI only, what the user selected
)