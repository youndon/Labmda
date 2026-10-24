package com.zouitel.presentaion.screens.orders

import com.zouitel.presentaion.model.OrderType
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions
import kotlinx.serialization.Serializable

/**
 * Creates orders through trusted server code. Prices, fees, merchant approval and payment records
 * are resolved by the server; this client sends only catalog IDs, quantities and destination.
 */
class OrderCreationRepository {
    private val functions = Firebase.functions

    suspend fun createOrder(
        orderType: OrderType,
        sourceId: String,
        items: List<RequestedOrderItem>,
        dropoffLocation: String,
        dropoffLat: Double,
        dropoffLng: Double,
        paymentMethod: String
    ): String {
        require(items.isNotEmpty()) { "Your cart is empty." }
        val response = functions.httpsCallable("createOrder")(
            CreateOrderRequest(
                orderType = orderType.name,
                sourceId = sourceId,
                items = items,
                dropoffLocation = dropoffLocation,
                dropoffLat = dropoffLat,
                dropoffLng = dropoffLng,
                paymentMethod = paymentMethod
            )
        ).data<CreateOrderResponse>()
        require(response.orderId.isNotBlank()) { "The server did not return an order ID." }
        return response.orderId
    }
}

@Serializable
data class RequestedOrderItem(val id: String, val quantity: Int)

@Serializable
private data class CreateOrderRequest(
    val orderType: String,
    val sourceId: String,
    val items: List<RequestedOrderItem>,
    val dropoffLocation: String,
    val dropoffLat: Double,
    val dropoffLng: Double,
    val paymentMethod: String
)

@Serializable
private data class CreateOrderResponse(
    val orderId: String = "",
    val subtotalMinor: Long = 0L,
    val deliveryFeeMinor: Long = 0L,
    val totalMinor: Long = 0L,
    val currencyCode: String = "USD"
)
