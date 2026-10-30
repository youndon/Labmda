package com.zouitel.presentaion.screens.payment

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions

/**
 * Callable-function boundary for creating orders and trips.
 *
 * The client sends catalog identifiers, quantities and route metadata only. Prices and fares
 * are calculated by Cloud Functions using the Realtime Database catalogue/configuration.
 */
data class PaymentCreationResult(
    val entityId: String,
    val paymentId: String,
    val paymentStatus: String,
    val amount: Double,
    val checkoutUrl: String?
)

class PaymentRepository {
    private val functions = Firebase.functions("asia-southeast1")

    suspend fun createOrder(
        idempotencyKey: String,
        orderType: String,
        sourceId: String,
        items: List<Map<String, Any>>,
        paymentMethod: String,
        pickupLocation: String,
        pickupLat: Double,
        pickupLng: Double,
        dropoffLocation: String,
        dropoffLat: Double,
        dropoffLng: Double
    ): PaymentCreationResult {
        val response = functions.httpsCallable("createOrderPayment")(
            mapOf(
                "idempotencyKey" to idempotencyKey,
                "orderType" to orderType,
                "sourceId" to sourceId,
                "items" to items,
                "paymentMethod" to paymentMethod.toPaymentCode(),
                "pickupLocation" to pickupLocation,
                "pickupLat" to pickupLat,
                "pickupLng" to pickupLng,
                "dropoffLocation" to dropoffLocation,
                "dropoffLat" to dropoffLat,
                "dropoffLng" to dropoffLng
            )
        )
        return response.data<Map<String, Any?>>().toPaymentCreationResult("orderId")
    }

    suspend fun createTrip(
        idempotencyKey: String,
        serviceType: String,
        pickupLocation: String,
        pickupLat: Double,
        pickupLng: Double,
        dropoffLocation: String,
        dropoffLat: Double,
        dropoffLng: Double,
        passengerName: String,
        passengerPhone: String,
        targetDriverId: String?,
        paymentMethod: String
    ): PaymentCreationResult {
        val response = functions.httpsCallable("createTripPayment")(
            mapOf(
                "idempotencyKey" to idempotencyKey,
                "serviceType" to serviceType,
                "pickupLocation" to pickupLocation,
                "pickupLat" to pickupLat,
                "pickupLng" to pickupLng,
                "dropoffLocation" to dropoffLocation,
                "dropoffLat" to dropoffLat,
                "dropoffLng" to dropoffLng,
                "passengerName" to passengerName,
                "passengerPhone" to passengerPhone,
                "targetDriverId" to targetDriverId,
                "paymentMethod" to paymentMethod.toPaymentCode()
            )
        )
        return response.data<Map<String, Any?>>().toPaymentCreationResult("tripId")
    }

    private fun String.toPaymentCode(): String {
        val value = trim().uppercase()
        return when {
            value.contains("GCASH") -> "GCASH"
            value.contains("CASH") -> "CASH"
            else -> value
        }
    }

    private fun Map<String, Any?>.toPaymentCreationResult(idKey: String): PaymentCreationResult {
        val id = this[idKey] as? String
            ?: error("Payment service did not return $idKey.")
        val paymentId = this["paymentId"] as? String ?: ""
        val status = this["paymentStatus"] as? String ?: "PENDING"
        val amount = (this["amount"] as? Number)?.toDouble()
            ?: (this["fare"] as? Number)?.toDouble()
            ?: 0.0
        val checkoutUrl = this["checkoutUrl"] as? String
        return PaymentCreationResult(id, paymentId, status, amount, checkoutUrl)
    }
}
