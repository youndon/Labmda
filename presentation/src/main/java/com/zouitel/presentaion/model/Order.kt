package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

@Serializable
enum class OrderType {
    FOOD,
    RETAIL
}

@Serializable
enum class OrderStatus {
    AWAITING_PAYMENT,
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
    val clientId: String = "",
    val orderType: OrderType = OrderType.FOOD,
    val sourceId: String = "",
    val sourceName: String = "",
    val items: List<CartItem> = emptyList(),
    // Monetary values are populated by the trusted backend, never by checkout UI input.
    val subtotal: Double = 0.0,
    val deliveryFee: Double = 0.0,
    val totalPrice: Double = 0.0,
    val status: OrderStatus = OrderStatus.PLACED,
    val pickupLocation: String = "",
    val pickupLat: Double = 0.0,
    val pickupLng: Double = 0.0,
    val dropoffLocation: String = "",
    val dropoffLat: Double = 0.0,
    val dropoffLng: Double = 0.0,
    val createdAtTimestamp: Long = 0L,
    val paymentMethod: String = "",
    val paymentStatus: String = "UNPAID",
    val paymentId: String = "",
    val deliveryId: String = "",
    val deliveryName: String = "",
    val deliveryPhone: String = "",
    val cashCollectionStatus: String = "",
    val cashCollectedBy: String = "",
    val cashCollectedByRole: String = "",
    val cashCollectedAmountCentavos: Long = 0L,
    val cashMerchantOwedCentavos: Long = 0L,
    val cashPlatformOwedCentavos: Long = 0L,
    val cashCollectorEarningsCentavos: Long = 0L,
    val cashRemittedCentavos: Long = 0L,
    val cashOutstandingRemittanceCentavos: Long = 0L,
    val cashRemittanceStatus: String = ""
)