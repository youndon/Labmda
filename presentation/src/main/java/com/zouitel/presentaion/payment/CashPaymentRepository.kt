package com.zouitel.presentaion.payment

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
data class CashQuotePoint(
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class CashQuoteItem(
    val itemId: String,
    val quantity: Int
)

@Serializable
data class CashQuoteRequest(
    val pickup: CashQuotePoint,
    val dropoff: CashQuotePoint,
    val paymentMethod: String = "CASH",
    val sourceId: String? = null,
    val items: List<CashQuoteItem>? = null,
    val rideType: String? = null
)

@Serializable
data class CashQuoteResponse(
    val quoteId: String,
    val uid: String = "",
    val kind: String = "",
    val pricingVersion: Int = 0,
    val currency: String = "PHP",
    val request: JsonObject = JsonObject(emptyMap()),
    val breakdown: JsonObject,
    val sourceId: String? = null,
    val items: JsonArray? = null,
    val route: JsonObject? = null,
    val paymentMethod: String? = null,
    val rideType: String? = null,
    val createdAt: Long = 0L,
    val expiresAt: Long,
    val status: String = ""
) {
    fun amountMinor(field: String): Long =
        breakdown[field]?.jsonPrimitive?.longOrNull
            ?: error("The server quote did not include a valid $field amount.")
}

@Serializable
data class CreateCashPaymentResponse(
    val paymentId: String,
    val amountMinor: Long,
    val currency: String,
    val status: String,
    val duplicate: Boolean = false
)

/**
 * Client adapter for server-authoritative quotes and cash payment creation.
 * No payment totals, commissions or beneficiary allocations are accepted from
 * the app when creating the payment.
 */
class CashPaymentRepository {
    private val functions = Firebase.functions

    private fun requireCash(method: String) {
        val normalized = method.trim().uppercase()
        require(normalized == "CASH" || normalized == "CASH ON DELIVERY") {
            "Cash checkout is currently enabled. GCash checkout will be added in the next payment phase."
        }
    }

    suspend fun quoteFoodOrder(
        sourceId: String,
        items: List<CashQuoteItem>,
        pickup: CashQuotePoint,
        dropoff: CashQuotePoint,
        paymentMethod: String
    ): CashQuoteResponse {
        requireCash(paymentMethod)
        val request = CashQuoteRequest(pickup, dropoff, "CASH", sourceId, items)
        return functions.httpsCallable("quoteFoodOrder")(request).data()
    }

    suspend fun quoteShopOrder(
        sourceId: String,
        items: List<CashQuoteItem>,
        pickup: CashQuotePoint,
        dropoff: CashQuotePoint,
        paymentMethod: String
    ): CashQuoteResponse {
        requireCash(paymentMethod)
        val request = CashQuoteRequest(pickup, dropoff, "CASH", sourceId, items)
        return functions.httpsCallable("quoteShopOrder")(request).data()
    }

    suspend fun quoteRide(
        pickup: CashQuotePoint,
        dropoff: CashQuotePoint,
        rideType: String = "STANDARD",
        paymentMethod: String = "CASH"
    ): CashQuoteResponse {
        requireCash(paymentMethod)
        val request = CashQuoteRequest(pickup, dropoff, "CASH", rideType = rideType)
        return functions.httpsCallable("quoteRide")(request).data()
    }

    suspend fun quoteDelivery(
        pickup: CashQuotePoint,
        dropoff: CashQuotePoint,
        paymentMethod: String = "CASH"
    ): CashQuoteResponse {
        requireCash(paymentMethod)
        val request = CashQuoteRequest(pickup, dropoff, "CASH")
        return functions.httpsCallable("quoteDelivery")(request).data()
    }

    suspend fun createCashPayment(
        quoteId: String,
        transactionId: String
    ): CreateCashPaymentResponse {
        require(quoteId.isNotBlank()) { "A server quote is required." }
        require(transactionId.isNotBlank()) { "An order or trip ID is required." }
        return functions.httpsCallable("createCashPaymentFromQuote")(
            mapOf("quoteId" to quoteId, "transactionId" to transactionId)
        ).data()
    }
}
