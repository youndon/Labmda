package com.zouitel.presentaion.payments

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions

/** Server-authorized fulfillment and assignment transitions. */
class ServiceActionsRepository {
    private val functions = Firebase.functions("asia-southeast1")
    suspend fun transitionOrderStatus(orderId: String, nextStatus: String) {
        functions.httpsCallable("transitionOrderStatus")(mapOf("orderId" to orderId, "nextStatus" to nextStatus))
    }
    suspend fun claimDeliveryOrder(orderId: String) {
        functions.httpsCallable("claimDeliveryOrder")(mapOf("orderId" to orderId))
    }
    suspend fun releaseDeliveryOrder(orderId: String) {
        functions.httpsCallable("releaseDeliveryOrder")(mapOf("orderId" to orderId))
    }
    suspend fun claimTrip(tripId: String) {
        functions.httpsCallable("claimTrip")(mapOf("tripId" to tripId))
    }
    suspend fun transitionTripStatus(tripId: String, nextStatus: String) {
        functions.httpsCallable("transitionTripStatus")(mapOf("tripId" to tripId, "nextStatus" to nextStatus))
    }
    suspend fun requestPaymentRefund(entityType: String, entityId: String, idempotencyKey: String, reason: String = "REQUESTED_BY_CUSTOMER", note: String = ""): String {
        val response = functions.httpsCallable("requestPaymentRefund")(
            mapOf("entityType" to entityType, "entityId" to entityId, "idempotencyKey" to idempotencyKey, "reason" to reason, "note" to note)
        )
        val result = response.data<Map<String, Any?>>()
        return result["status"] as? String ?: "PENDING_REVIEW"
    }
}