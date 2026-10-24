package com.zouitel.presentaion.screens.orders

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions
import kotlinx.serialization.Serializable

/** Order status transitions are authorized and persisted by trusted server code. */
class OrderStatusRepository {
    private val functions = Firebase.functions

    suspend fun updateStatus(orderId: String, status: String) {
        require(orderId.isNotBlank())
        functions.httpsCallable("updateOrderStatus")(
            OrderStatusRequest(orderId, status)
        ).data<OrderStatusResponse>()
    }
}

@Serializable
private data class OrderStatusRequest(val orderId: String, val status: String)

@Serializable
private data class OrderStatusResponse(val accepted: Boolean = false, val status: String = "")
