package com.zouitel.presentaion.screens.payment

import com.zouitel.presentaion.model.Money
import com.zouitel.presentaion.model.OrderBreakdown
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions

/**
 * Result returned by a server-created order or trip payment.
 * The amount field remains in PHP for old UI code; amountCentavos is authoritative
 * for money formatting/calculation in new UI code.
 */
data class PaymentCreationResult(
    val entityId: String,
    val paymentId: String,
    val paymentStatus: String,
    val amount: Double,
    val checkoutUrl: String?,
    val amountCentavos: Long = 0L,
    val distanceMeters: Long = 0L,
    val pricingVersion: Long = 0L,
    val breakdown: OrderBreakdown? = null
)

/** Server quote for a food or goods order. Monetary fields are PHP centavos. */
data class PricingOrderQuoteResult(
    val quoteId: String,
    val orderType: String,
    val amount: Double,
    val totalCentavos: Long,
    val subtotalCentavos: Long,
    val deliveryFeeCentavos: Long,
    val distanceMeters: Long,
    val pricingVersion: Long,
    val expiresAtTimestamp: Long,
    val breakdown: OrderBreakdown?
)

/** A route and rate quote produced by Firebase Functions. */
data class PricingQuoteResult(
    val quoteId: String,
    val quoteType: String,
    val amount: Double,
    val amountCentavos: Long,
    val distanceMeters: Long,
    val distanceKm: Double,
    val pricingVersion: Long,
    val expiresAtTimestamp: Long
)

class PaymentRepository {
    private val functions = Firebase.functions("asia-southeast1")

    /**
     * Requests a delivery or ride quote. The server obtains road distance from Google Routes
     * and applies the current administrator pricing version.
     */
    suspend fun createPricingQuote(
        quoteType: String,
        pickupLat: Double,
        pickupLng: Double,
        dropoffLat: Double,
        dropoffLng: Double,
        serviceType: String = "ride"
    ): PricingQuoteResult {
        val response = functions.httpsCallable("createPricingQuote")(
            mapOf(
                "quoteType" to quoteType.trim().uppercase(),
                "serviceType" to serviceType,
                "pickupLat" to pickupLat,
                "pickupLng" to pickupLng,
                "dropoffLat" to dropoffLat,
                "dropoffLng" to dropoffLng
            )
        )
        val result = response.data<Map<String, Any?>>()
        return PricingQuoteResult(
            quoteId = result["quoteId"] as? String
                ?: error("Pricing service did not return a quote ID."),
            quoteType = result["quoteType"] as? String ?: quoteType.uppercase(),
            amount = (result["amount"] as? Number)?.toDouble() ?: 0.0,
            amountCentavos = (result["amountCentavos"] as? Number)?.toLong() ?: 0L,
            distanceMeters = (result["distanceMeters"] as? Number)?.toLong() ?: 0L,
            distanceKm = (result["distanceKm"] as? Number)?.toDouble() ?: 0.0,
            pricingVersion = (result["pricingVersion"] as? Number)?.toLong() ?: 0L,
            expiresAtTimestamp = (result["expiresAtTimestamp"] as? Number)?.toLong() ?: 0L
        )
    }

    /**
     * Requests a server-priced food or goods order quote. Only catalogue IDs and quantities
     * are sent; all unit prices, delivery charges and totals are read/calculated by Functions.
     */
    suspend fun quoteOrder(
        orderType: String,
        sourceId: String,
        items: List<Map<String, Any>>,
        dropoffLocation: String,
        dropoffLat: Double,
        dropoffLng: Double
    ): PricingOrderQuoteResult {
        val response = functions.httpsCallable("quoteOrder")(
            mapOf(
                "orderType" to orderType.trim().uppercase(),
                "sourceId" to sourceId,
                "items" to items,
                "dropoffLocation" to dropoffLocation,
                "dropoffLat" to dropoffLat,
                "dropoffLng" to dropoffLng
            )
        )
        val result = response.data<Map<String, Any?>>()
        return PricingOrderQuoteResult(
            quoteId = result["quoteId"] as? String
                ?: error("Pricing service did not return an order quote ID."),
            orderType = result["orderType"] as? String ?: orderType.uppercase(),
            amount = (result["amount"] as? Number)?.toDouble() ?: 0.0,
            totalCentavos = (result["totalCentavos"] as? Number)?.toLong() ?: 0L,
            subtotalCentavos = (result["subtotalCentavos"] as? Number)?.toLong() ?: 0L,
            deliveryFeeCentavos = (result["deliveryFeeCentavos"] as? Number)?.toLong() ?: 0L,
            distanceMeters = (result["distanceMeters"] as? Number)?.toLong() ?: 0L,
            pricingVersion = (result["pricingVersion"] as? Number)?.toLong() ?: 0L,
            expiresAtTimestamp = (result["expiresAtTimestamp"] as? Number)?.toLong() ?: 0L,
            breakdown = result["breakdown"].toOrderBreakdownOrNull()
        )
    }

    /** Creates an order from a locked quote; no client amount or unit price is accepted. */
    suspend fun createOrderFromQuote(
        quoteId: String,
        idempotencyKey: String
    ): PaymentCreationResult {
        val response = functions.httpsCallable("createOrder")(
            mapOf(
                "quoteId" to quoteId,
                "idempotencyKey" to idempotencyKey
            )
        )
        return response.data<Map<String, Any?>>().toPaymentCreationResult("orderId")
    }

    /**
     * Requests a ride quote. Billable road distance and fare come exclusively from Functions.
     */
    suspend fun quoteTrip(
        pickupLat: Double,
        pickupLng: Double,
        dropoffLat: Double,
        dropoffLng: Double,
        serviceType: String = "ride"
    ): PricingQuoteResult {
        val response = functions.httpsCallable("quoteTrip")(
            mapOf(
                "serviceType" to serviceType,
                "pickupLat" to pickupLat,
                "pickupLng" to pickupLng,
                "dropoffLat" to dropoffLat,
                "dropoffLng" to dropoffLng
            )
        )
        val result = response.data<Map<String, Any?>>()
        return PricingQuoteResult(
            quoteId = result["quoteId"] as? String
                ?: error("Pricing service did not return a ride quote ID."),
            quoteType = result["quoteType"] as? String ?: "RIDE",
            amount = (result["amount"] as? Number)?.toDouble() ?: 0.0,
            amountCentavos = (result["amountCentavos"] as? Number)?.toLong() ?: 0L,
            distanceMeters = (result["distanceMeters"] as? Number)?.toLong() ?: 0L,
            distanceKm = (result["distanceKm"] as? Number)?.toDouble() ?: 0.0,
            pricingVersion = (result["pricingVersion"] as? Number)?.toLong() ?: 0L,
            expiresAtTimestamp = (result["expiresAtTimestamp"] as? Number)?.toLong() ?: 0L
        )
    }

    /** Creates a trip using only a server-issued ride quote and participant metadata. */
    suspend fun createTripFromQuote(
        quoteId: String,
        idempotencyKey: String,
        pickupLocation: String,
        dropoffLocation: String,
        passengerName: String,
        passengerPhone: String,
        targetDriverId: String? = null,
        serviceType: String = "ride"
    ): PaymentCreationResult {
        val response = functions.httpsCallable("createTrip")(
            mapOf(
                "quoteId" to quoteId,
                "idempotencyKey" to idempotencyKey,
                "serviceType" to serviceType,
                "pickupLocation" to pickupLocation,
                "dropoffLocation" to dropoffLocation,
                "passengerName" to passengerName,
                "passengerPhone" to passengerPhone,
                "targetDriverId" to targetDriverId
            )
        )
        return response.data<Map<String, Any?>>().toPaymentCreationResult("tripId")
    }

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
        dropoffLng: Double,
        pricingQuoteId: String? = null
    ): PaymentCreationResult {
        val payload = mutableMapOf<String, Any>(
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
        pricingQuoteId?.takeIf { it.isNotBlank() }?.let { payload["pricingQuoteId"] = it }

        val response = functions.httpsCallable("createOrderPayment")(payload)
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
        paymentMethod: String,
        pricingQuoteId: String? = null
    ): PaymentCreationResult {
        val payload = mutableMapOf<String, Any?>(
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
        pricingQuoteId?.takeIf { it.isNotBlank() }?.let { payload["pricingQuoteId"] = it }

        val response = functions.httpsCallable("createTripPayment")(payload)
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
        return PaymentCreationResult(
            entityId = id,
            paymentId = paymentId,
            paymentStatus = status,
            amount = amount,
            checkoutUrl = checkoutUrl,
            amountCentavos = (this["amountCentavos"] as? Number)?.toLong()
                ?: (this["fareCentavos"] as? Number)?.toLong()
                ?: 0L,
            distanceMeters = (this["distanceMeters"] as? Number)?.toLong() ?: 0L,
            pricingVersion = (this["pricingVersion"] as? Number)?.toLong() ?: 0L,
            breakdown = this["breakdown"].toOrderBreakdownOrNull()
        )
    }

    private fun Any?.toOrderBreakdownOrNull(): OrderBreakdown? {
        val values = this as? Map<*, *> ?: return null
        fun money(key: String): Money =
            Money((values[key] as? Number)?.toLong() ?: 0L)

        return OrderBreakdown(
            subtotal = money("subtotal"),
            deliveryFee = money("deliveryFee"),
            serviceFee = money("serviceFee"),
            discount = money("discount"),
            tax = money("tax"),
            total = money("total"),
            merchantShare = money("merchantShare"),
            courierShare = money("courierShare"),
            driverShare = money("driverShare"),
            platformShare = money("platformShare"),
            gatewayFee = money("gatewayFee"),
            merchantCommission = money("merchantCommission"),
            deliveryCommission = money("deliveryCommission"),
            rideCommission = money("rideCommission")
        )
    }
}
