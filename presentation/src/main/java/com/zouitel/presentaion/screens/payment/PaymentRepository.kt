package com.zouitel.presentaion.screens.payment

import com.zouitel.presentaion.admin.CommissionSettings
import com.zouitel.presentaion.admin.PaymentLedgerResponse
import com.zouitel.presentaion.model.Money
import com.zouitel.presentaion.model.OrderBreakdown
import com.zouitel.presentaion.model.PaymentMethod
import com.zouitel.presentaion.model.PaymentRecord
import com.zouitel.presentaion.model.PaymentSession
import com.zouitel.presentaion.model.PaymentStatus
import com.zouitel.presentaion.model.PayPalSession
import com.zouitel.presentaion.model.StripeSession
import com.zouitel.presentaion.screens.wallet.PayoutAccount
import com.zouitel.presentaion.screens.wallet.WalletSnapshot
import dev.gitlive.firebase.functions.FirebaseFunctions
import dev.gitlive.firebase.functions.functions
import dev.gitlive.firebase.database.DatabaseReference
import dev.gitlive.firebase.database.DataSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * Client facade for trusted Cloud Functions and read-only payment observations.
 * All order pricing, commissions, refunds, payouts and provider state are authoritative on the server.
 *
 * Expected callable contracts are documented in functions/*; never grant clients write access
 * to /payments or use client-provided amounts as settlement values.
 */
class PaymentRepository(
    private val database: DatabaseReference,
    private val functions: FirebaseFunctions
) {
    fun observePayment(orderId: String): Flow<PaymentRecord?> =
        database.child("payments").child(orderId).valueEvents.map { snapshot ->
            snapshot.toPaymentRecord(orderId)
        }

    suspend fun createCheckout(orderId: String, method: PaymentMethod): PaymentSession {
        require(method == PaymentMethod.STRIPE || method == PaymentMethod.PAYPAL) {
            "Hosted checkout is only available for Stripe and PayPal."
        }
        val response = functions.httpsCallable("createPaymentSession")(
            CreatePaymentSessionRequest(orderId = orderId, method = method.name)
        ).data<CreatePaymentSessionResponse>()
        require(response.orderId == orderId) { "Payment session did not match the order." }
        require(response.sessionId.isNotBlank() && response.checkoutUrl.startsWith("https://")) {
            "The server returned an invalid checkout session."
        }
        return when (method) {
            PaymentMethod.STRIPE -> StripeSession(
                orderId = response.orderId,
                sessionId = response.sessionId,
                checkoutUrl = response.checkoutUrl,
                expiresAtEpochMillis = response.expiresAtEpochMillis
            )
            PaymentMethod.PAYPAL -> PayPalSession(
                orderId = response.orderId,
                sessionId = response.sessionId,
                checkoutUrl = response.checkoutUrl,
                expiresAtEpochMillis = response.expiresAtEpochMillis
            )
            PaymentMethod.CASH -> error("Cash does not use hosted checkout.")
        }
    }

    suspend fun requestCashPayment(orderId: String) {
        functions.httpsCallable("requestCashPayment")(OrderPaymentRequest(orderId)).data<CallableAcknowledgement>()
    }

    /** The provider redirect is only a signal to ask the server to verify payment; not proof of payment. */
    suspend fun verifyProviderReturn(orderId: String, provider: String, sessionId: String, resultToken: String) {
        functions.httpsCallable("verifyPaymentReturn")(
            VerifyPaymentReturnRequest(orderId, provider, sessionId, resultToken)
        ).data<CallableAcknowledgement>()
    }

    suspend fun getMerchantWallet(): WalletSnapshot =
        functions.httpsCallable("getMerchantWallet")().data<WalletSnapshot>()

    suspend fun getCourierWallet(): WalletSnapshot =
        functions.httpsCallable("getCourierWallet")().data<WalletSnapshot>()

    suspend fun getPayoutAccount(): PayoutAccount? =
        functions.httpsCallable("getPayoutAccount")().data<PayoutAccountResponse>().account

    suspend fun savePayoutAccount(account: PayoutAccount) {
        functions.httpsCallable("updatePayoutAccount")(account).data<CallableAcknowledgement>()
    }

    suspend fun requestPayout(amount: Money) {
        require(amount.minorUnits > 0L) { "Payout amount must be positive." }
        functions.httpsCallable("requestPayout")(PayoutRequest(amount)).data<CallableAcknowledgement>()
    }

    suspend fun getAdminLedger(cursor: String? = null): PaymentLedgerResponse =
        functions.httpsCallable("adminListPayments")(CursorRequest(cursor)).data<PaymentLedgerResponse>()

    suspend fun refundPayment(orderId: String, amount: Money?, reason: String) {
        require(reason.isNotBlank()) { "A refund reason is required." }
        functions.httpsCallable("adminRefundPayment")(
            RefundRequest(orderId, amount?.minorUnits, amount?.currencyCode, reason.trim())
        ).data<CallableAcknowledgement>()
    }

    suspend fun reconcilePayments(fromEpochMillis: Long, toEpochMillis: Long): ReconciliationResponse {
        require(fromEpochMillis >= 0 && toEpochMillis >= fromEpochMillis) { "Invalid reconciliation range." }
        return functions.httpsCallable("adminReconcilePayments")(
            ReconciliationRequest(fromEpochMillis, toEpochMillis)
        ).data<ReconciliationResponse>()
    }

    suspend fun getCommissionSettings(): CommissionSettings =
        functions.httpsCallable("adminGetCommissionSettings")().data<CommissionSettings>()

    suspend fun saveCommissionSettings(settings: CommissionSettings) {
        require(settings.platformCommissionBps in 0..10_000)
        require(settings.courierShareBps in 0..10_000)
        require(settings.platformCommissionBps + settings.courierShareBps <= 10_000)
        functions.httpsCallable("adminUpdateCommissionSettings")(settings).data<CallableAcknowledgement>()
    }

    private fun DataSnapshot.toPaymentRecord(orderId: String): PaymentRecord? {
        if (!exists) return null
        fun string(path: String) = runCatching { child(path).value<String>() }.getOrDefault("")
        fun long(path: String) = runCatching { child(path).value<Long>() }
            .getOrElse { runCatching { child(path).value<Int>().toLong() }.getOrDefault(0L) }
        fun money(path: String): Money {
            val node = child(path)
            val minor = runCatching { node.child("minorUnits").value<Long>() }
                .getOrElse { runCatching { node.child("minorUnits").value<Int>().toLong() }.getOrDefault(0L) }
            val currency = runCatching { node.child("currencyCode").value<String>() }.getOrDefault("USD")
            return Money(minor, currency)
        }
        val method = runCatching { PaymentMethod.valueOf(string("method")) }.getOrDefault(PaymentMethod.CASH)
        val status = runCatching { PaymentStatus.valueOf(string("status")) }.getOrDefault(PaymentStatus.UNKNOWN)
        val breakdownNode = child("breakdown")
        val breakdown = if (breakdownNode.exists) {
            fun split(path: String): Money {
                val node = breakdownNode.child(path)
                val minor = runCatching { node.child("minorUnits").value<Long>() }
                    .getOrElse { runCatching { node.child("minorUnits").value<Int>().toLong() }.getOrDefault(0L) }
                return Money(minor, runCatching { node.child("currencyCode").value<String>() }.getOrDefault("USD"))
            }
            OrderBreakdown(split("merchantShare"), split("courierShare"), split("platformShare"), split("gatewayFee"))
        } else null
        return PaymentRecord(
            orderId = orderId,
            clientId = string("clientId"),
            merchantId = string("merchantId"),
            courierId = string("courierId"),
            method = method,
            status = status,
            amount = money("amount"),
            breakdown = breakdown,
            providerPaymentId = string("providerPaymentId"),
            refundAmount = money("refundAmount"),
            updatedAtEpochMillis = long("updatedAtEpochMillis")
        )
    }
}

@Serializable private data class CreatePaymentSessionRequest(val orderId: String, val method: String)
@Serializable private data class CreatePaymentSessionResponse(
    val orderId: String = "",
    val sessionId: String = "",
    val checkoutUrl: String = "",
    val expiresAtEpochMillis: Long = 0L
)
@Serializable private data class OrderPaymentRequest(val orderId: String)
@Serializable private data class VerifyPaymentReturnRequest(
    val orderId: String,
    val provider: String,
    val sessionId: String,
    val resultToken: String
)
@Serializable private data class CallableAcknowledgement(val accepted: Boolean = false, val message: String = "")
@Serializable private data class PayoutAccountResponse(val account: PayoutAccount? = null)
@Serializable private data class PayoutRequest(val amount: Money)
@Serializable private data class RefundRequest(
    val orderId: String,
    val amountMinorUnits: Long?,
    val currencyCode: String?,
    val reason: String
)
@Serializable private data class CursorRequest(val cursor: String? = null)
@Serializable private data class ReconciliationRequest(val fromEpochMillis: Long, val toEpochMillis: Long)
@Serializable data class ReconciliationResponse(
    val reconciled: Boolean = false,
    val matchedCount: Int = 0,
    val unmatchedCount: Int = 0,
    val message: String = ""
)
