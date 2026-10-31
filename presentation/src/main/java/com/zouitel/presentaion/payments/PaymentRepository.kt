package com.zouitel.presentaion.payments

import com.zouitel.presentaion.model.Money
import com.zouitel.presentaion.model.OrderBreakdown
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import dev.gitlive.firebase.functions.functions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
    val breakdown: OrderBreakdown? = null,
    val webCheckoutUrl: String? = null,
    val checkoutActionDescriptor: String? = null,
    val providerPaymentId: String? = null,
    val expiresAtTimestamp: Long = 0L
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

/** Itemized ride fare values returned by the server, all in PHP centavos. */
data class RideFareBreakdown(
    val baseFareCentavos: Long,
    val distanceChargeCentavos: Long,
    val additionalChargesCentavos: Long,
    val adjustmentCentavos: Long,
    val totalCentavos: Long
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
    val expiresAtTimestamp: Long,
    val routePolyline: String = "",
    val routeDuration: String = "",
    val fareBreakdown: RideFareBreakdown? = null
)


/** Server-authoritative payment state observed from payments/{entityId}. */
data class ObservedPaymentStatus(
    val status: String,
    val provider: String,
    val paymentId: String,
    val providerPaymentId: String?,
    val amountCentavos: Long,
    val refundedCentavos: Long,
    val pendingRefundCentavos: Long,
    val currency: String,
    val failureCode: String,
    val updatedAtTimestamp: Long
)

/** Result of an administrator-authorized Xendit refund request. */
data class RefundPaymentResult(
    val entityId: String,
    val refundId: String,
    val refundStatus: String,
    val amountCentavos: Long
)

/** Server-confirmed physical cash collection and outstanding remittance split. */
data class CashCollectionResult(
    val collectionId: String,
    val entityId: String,
    val collectionStatus: String,
    val collectedAmountCentavos: Long,
    val merchantOwedCentavos: Long,
    val platformOwedCentavos: Long,
    val collectorEarningsCentavos: Long,
    val remittableCentavos: Long,
    val remittedCentavos: Long,
    val outstandingRemittanceCentavos: Long,
    val reused: Boolean
)

/** Administrator-confirmed cash remittance received from a courier or driver. */
data class CashRemittanceResult(
    val remittanceId: String,
    val entityId: String,
    val amountCentavos: Long,
    val remittedCentavos: Long,
    val outstandingRemittanceCentavos: Long,
    val status: String,
    val reused: Boolean
)

class PaymentRepository {
    private val functions = Firebase.functions("asia-southeast1")
    private val db = Firebase.database.reference()

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
            expiresAtTimestamp = (result["expiresAtTimestamp"] as? Number)?.toLong() ?: 0L,
            routePolyline = result["routePolyline"] as? String ?: "",
            routeDuration = result["routeDuration"] as? String ?: "",
            fareBreakdown = (result["fareBreakdown"] as? Map<*, *>)?.let { values ->
                RideFareBreakdown(
                    baseFareCentavos = (values["baseFareCentavos"] as? Number)?.toLong() ?: 0L,
                    distanceChargeCentavos = (values["distanceChargeCentavos"] as? Number)?.toLong() ?: 0L,
                    additionalChargesCentavos = (values["additionalChargesCentavos"] as? Number)?.toLong() ?: 0L,
                    adjustmentCentavos = (values["adjustmentCentavos"] as? Number)?.toLong() ?: 0L,
                    totalCentavos = (values["totalCentavos"] as? Number)?.toLong() ?: 0L
                )
            }
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


    /**
     * Starts one-time GCash collection for an already-created server-priced order or trip.
     * The checkoutUrl prefers Xendit's GCash deeplink and falls back to the web checkout URL.
     * Returning from GCash must never be treated as proof of payment; observePaymentStatus()
     * returns the authoritative server-updated state.
     */
    suspend fun createGcashPayment(
        entityType: String,
        entityId: String,
        idempotencyKey: String
    ): PaymentCreationResult {
        val normalizedType = entityType.trim().uppercase()
        require(normalizedType == "ORDER" || normalizedType == "TRIP") {
            "entityType must be ORDER or TRIP."
        }
        val response = functions.httpsCallable("createGcashPayment")(
            mapOf(
                "entityType" to normalizedType,
                "entityId" to entityId,
                "idempotencyKey" to idempotencyKey
            )
        )
        val result = response.data<Map<String, Any?>>().toMutableMap()
        val idKey = if (normalizedType == "TRIP") "tripId" else "orderId"
        result[idKey] = result["entityId"] as? String ?: entityId
        result["amountCentavos"] = result["amountCentavos"]
            ?: result["totalCentavos"]
        return result.toPaymentCreationResult(idKey)
    }

    /** Observe the payment record written by verified Xendit events and reconciliation. */
    fun observePaymentStatus(entityId: String): Flow<ObservedPaymentStatus?> {
        return db.child("payments").child(entityId).valueEvents.map { snapshot ->
            if (!snapshot.exists) {
                null
            } else {
                fun string(key: String, default: String = ""): String =
                    runCatching { snapshot.child(key).value<String?>() ?: default }.getOrDefault(default)

                fun long(key: String): Long {
                    val value = snapshot.child(key)
                    return runCatching { value.value<Long?>() }.getOrNull()
                        ?: runCatching { value.value<Double?>()?.toLong() }.getOrNull()
                        ?: 0L
                }

                ObservedPaymentStatus(
                    status = string("status", "UNPAID"),
                    provider = string("provider"),
                    paymentId = string("providerReference"),
                    providerPaymentId = string("providerPaymentId").ifBlank { null },
                    amountCentavos = long("amountCentavos"),
                    refundedCentavos = long("refundedCentavos"),
                    pendingRefundCentavos = long("pendingRefundCentavos"),
                    currency = string("currency", "PHP"),
                    failureCode = string("failureCode"),
                    updatedAtTimestamp = long("updatedAtTimestamp")
                )
            }
        }
    }

    /** Call this only from an administrator surface; Functions verifies the admin claim. */
    suspend fun refundPayment(
        entityType: String,
        entityId: String,
        idempotencyKey: String,
        reason: String = "REQUESTED_BY_CUSTOMER",
        amountCentavos: Long? = null
    ): RefundPaymentResult {
        val payload = mutableMapOf<String, Any>(
            "entityType" to entityType.trim().uppercase(),
            "entityId" to entityId,
            "idempotencyKey" to idempotencyKey,
            "reason" to reason.trim().uppercase()
        )
        amountCentavos?.let { payload["amountCentavos"] = it }
        val response = functions.httpsCallable("refundPayment")(payload)
        val result = response.data<Map<String, Any?>>()
        return RefundPaymentResult(
            entityId = result["entityId"] as? String ?: entityId,
            refundId = result["refundId"] as? String ?: "",
            refundStatus = result["refundStatus"] as? String ?: "PENDING",
            amountCentavos = (result["amountCentavos"] as? Number)?.toLong() ?: 0L
        )
    }

    /**
     * Confirms physical cash received by the assigned courier (ORDER) or ride driver (TRIP).
     * No amount is accepted from the device; Functions reads the saved PHP-centavo total.
     */
    suspend fun confirmCashCollected(
        entityType: String,
        entityId: String
    ): CashCollectionResult {
        val normalizedType = entityType.trim().uppercase()
        require(normalizedType == "ORDER" || normalizedType == "TRIP") {
            "entityType must be ORDER or TRIP."
        }
        val response = functions.httpsCallable("confirmCashCollected")(
            mapOf(
                "entityType" to normalizedType,
                "entityId" to entityId
            )
        )
        val result = response.data<Map<String, Any?>>()
        return CashCollectionResult(
            collectionId = result["collectionId"] as? String ?: "",
            entityId = result["entityId"] as? String ?: entityId,
            collectionStatus = result["collectionStatus"] as? String ?: "COLLECTED",
            collectedAmountCentavos = (result["collectedAmountCentavos"] as? Number)?.toLong() ?: 0L,
            merchantOwedCentavos = (result["merchantOwedCentavos"] as? Number)?.toLong() ?: 0L,
            platformOwedCentavos = (result["platformOwedCentavos"] as? Number)?.toLong() ?: 0L,
            collectorEarningsCentavos = (result["collectorEarningsCentavos"] as? Number)?.toLong() ?: 0L,
            remittableCentavos = (result["remittableCentavos"] as? Number)?.toLong() ?: 0L,
            remittedCentavos = (result["remittedCentavos"] as? Number)?.toLong() ?: 0L,
            outstandingRemittanceCentavos = (result["outstandingRemittanceCentavos"] as? Number)?.toLong() ?: 0L,
            reused = result["reused"] as? Boolean ?: false
        )
    }

    /**
     * Administrator records the actual amount received from a cash collector.
     * Functions checks that it does not exceed the entity's remaining remittance due.
     */
    suspend fun recordCashRemittance(
        entityType: String,
        entityId: String,
        amountCentavos: Long,
        idempotencyKey: String
    ): CashRemittanceResult {
        require(amountCentavos > 0L) { "Remittance amount must be positive." }
        val response = functions.httpsCallable("recordCashRemittance")(
            mapOf(
                "entityType" to entityType.trim().uppercase(),
                "entityId" to entityId,
                "amountCentavos" to amountCentavos,
                "idempotencyKey" to idempotencyKey
            )
        )
        val result = response.data<Map<String, Any?>>()
        return CashRemittanceResult(
            remittanceId = result["remittanceId"] as? String ?: "",
            entityId = result["entityId"] as? String ?: entityId,
            amountCentavos = (result["amountCentavos"] as? Number)?.toLong() ?: amountCentavos,
            remittedCentavos = (result["remittedCentavos"] as? Number)?.toLong() ?: 0L,
            outstandingRemittanceCentavos = (result["outstandingRemittanceCentavos"] as? Number)?.toLong() ?: 0L,
            status = result["status"] as? String ?: "PENDING",
            reused = result["reused"] as? Boolean ?: false
        )
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

        if (paymentMethod.toPaymentCode() == "GCASH") {
            val quote = quoteOrder(
                orderType = orderType,
                sourceId = sourceId,
                items = items,
                dropoffLocation = dropoffLocation,
                dropoffLat = dropoffLat,
                dropoffLng = dropoffLng
            )
            // Retrying with the same order key returns the same order even if a new short-lived
            // quote had to be requested after a previous checkout initiation failure.
            val order = createOrderFromQuote(quote.quoteId, idempotencyKey)
            val checkout = createGcashPayment(
                entityType = "ORDER",
                entityId = order.entityId,
                idempotencyKey = idempotencyKey.take(72) + "_gcash"
            )
            return checkout.copy(
                amount = order.amount,
                amountCentavos = order.amountCentavos,
                distanceMeters = order.distanceMeters,
                pricingVersion = order.pricingVersion,
                breakdown = order.breakdown
            )
        }

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

        if (paymentMethod.toPaymentCode() == "GCASH") {
            // Reuse the exact quote reviewed by the passenger. Only request a replacement
            // when this call was initiated without a previously generated quote.
            val quoteId = pricingQuoteId?.takeIf { it.isNotBlank() } ?: quoteTrip(
                pickupLat = pickupLat,
                pickupLng = pickupLng,
                dropoffLat = dropoffLat,
                dropoffLng = dropoffLng,
                serviceType = serviceType.trim().lowercase()
            ).quoteId
            val trip = createTripFromQuote(
                quoteId = quoteId,
                idempotencyKey = idempotencyKey,
                pickupLocation = pickupLocation,
                dropoffLocation = dropoffLocation,
                passengerName = passengerName,
                passengerPhone = passengerPhone,
                targetDriverId = targetDriverId,
                serviceType = serviceType.trim().lowercase()
            )
            val checkout = createGcashPayment(
                entityType = "TRIP",
                entityId = trip.entityId,
                idempotencyKey = idempotencyKey.take(72) + "_gcash"
            )
            return checkout.copy(
                amount = trip.amount,
                amountCentavos = trip.amountCentavos,
                distanceMeters = trip.distanceMeters,
                pricingVersion = trip.pricingVersion,
                breakdown = trip.breakdown
            )
        }

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
        val status = this["paymentStatus"] as? String ?: "UNPAID"
        val amount = (this["amount"] as? Number)?.toDouble()
            ?: (this["fare"] as? Number)?.toDouble()
            ?: 0.0
        val checkoutUrl = this["checkoutUrl"] as? String
        val webCheckoutUrl = this["webCheckoutUrl"] as? String
        val checkoutActionDescriptor = this["checkoutActionDescriptor"] as? String
        val providerPaymentId = this["providerPaymentId"] as? String
        val expiresAtTimestamp = (this["expiresAtTimestamp"] as? Number)?.toLong() ?: 0L
        return PaymentCreationResult(
            entityId = id,
            paymentId = paymentId,
            paymentStatus = status,
            amount = amount,
            checkoutUrl = checkoutUrl,
            amountCentavos = (this["amountCentavos"] as? Number)?.toLong()
                ?: (this["totalCentavos"] as? Number)?.toLong()
                ?: (this["fareCentavos"] as? Number)?.toLong()
                ?: 0L,
            distanceMeters = (this["distanceMeters"] as? Number)?.toLong() ?: 0L,
            pricingVersion = (this["pricingVersion"] as? Number)?.toLong() ?: 0L,
            breakdown = this["breakdown"].toOrderBreakdownOrNull(),
            webCheckoutUrl = webCheckoutUrl,
            checkoutActionDescriptor = checkoutActionDescriptor,
            providerPaymentId = providerPaymentId,
            expiresAtTimestamp = expiresAtTimestamp
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
