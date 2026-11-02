package com.zouitel.presentaion.screens.payment

import com.zouitel.domain.model.CheckoutStatus
import com.zouitel.domain.model.PaymentCheckoutEvent
import com.zouitel.domain.model.PaymentCheckoutReducer
import com.zouitel.domain.model.PaymentCheckoutState
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.FirebaseFunctions
import dev.gitlive.firebase.functions.functions
import dev.gitlive.firebase.functions.httpsCallable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
private data class CreateGcashSessionRequest(val quoteId: String)

@Serializable
private data class SimulateGcashResultRequest(val sessionId: String, val outcome: String)

@Serializable
private data class CreateSimulatedRefundRequest(
    val sessionId: String,
    val reason: String,
    val amountMinor: Long? = null
)

@Serializable
data class SimulatedGcashSessionResponse(
    val sessionId: String,
    val paymentId: String,
    val status: String,
    val amountMinor: Long,
    val currency: String,
    val checkoutUrl: String,
    val expiresAt: Long,
    val simulationOnly: Boolean
)

@Serializable
data class SimulatedGcashResultResponse(
    val duplicate: Boolean,
    val status: String
)

@Serializable
data class SimulatedGcashRefundResponse(
    val refundId: String,
    val status: String,
    val amountMinor: Long,
    val currency: String,
    val simulationOnly: Boolean
)

/**
 * Firebase Functions adapter for local development only.
 *
 * A non-empty emulator host is mandatory. This deliberately prevents this class
 * from silently calling a production Cloud Functions endpoint.
 */
class FirebaseSimulatedPaymentCheckoutRepository(
    emulatorHost: String,
    emulatorPort: Int = 5001,
    private val functions: FirebaseFunctions = Firebase.functions
) {
    init {
        require(emulatorHost.isNotBlank()) {
            "A Firebase Functions emulator host is required for simulated checkout."
        }
        require(emulatorPort in 1..65535) { "Invalid Firebase Functions emulator port." }
        functions.useEmulator(emulatorHost, emulatorPort)
    }

    suspend fun createGcashSession(quoteId: String): PaymentCheckoutState {
        require(quoteId.isNotBlank()) { "Quote ID is required." }
        val creating = PaymentCheckoutReducer.reduce(
            PaymentCheckoutState(),
            PaymentCheckoutEvent.Start
        )
        return try {
            val result = functions.httpsCallable("createSimulatedGcashSession")(
                CreateGcashSessionRequest(quoteId)
            ).data<SimulatedGcashSessionResponse>()
            PaymentCheckoutReducer.reduce(
                creating,
                PaymentCheckoutEvent.SessionCreated(
                    sessionId = result.sessionId,
                    paymentId = result.paymentId,
                    amountMinor = result.amountMinor,
                    expiresAt = result.expiresAt,
                    simulationOnly = result.simulationOnly
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PaymentCheckoutReducer.reduce(
                creating,
                PaymentCheckoutEvent.Failed(error.message ?: "Could not create the simulated checkout.")
            )
        }
    }

    suspend fun simulateResult(
        state: PaymentCheckoutState,
        success: Boolean
    ): PaymentCheckoutState {
        check(state.status == CheckoutStatus.PENDING && state.simulationOnly) {
            "Only a pending simulated checkout can be completed."
        }
        return try {
            val result = functions.httpsCallable("simulateGcashPaymentResult")(
                SimulateGcashResultRequest(
                    sessionId = state.sessionId,
                    outcome = if (success) "SUCCEEDED" else "FAILED"
                )
            ).data<SimulatedGcashResultResponse>()
            when (result.status) {
                "SUCCEEDED" -> PaymentCheckoutReducer.reduce(state, PaymentCheckoutEvent.SimulatedSuccess)
                "FAILED" -> PaymentCheckoutReducer.reduce(state, PaymentCheckoutEvent.SimulatedFailure)
                "EXPIRED" -> PaymentCheckoutReducer.reduce(state, PaymentCheckoutEvent.Expired)
                else -> PaymentCheckoutReducer.reduce(
                    state,
                    PaymentCheckoutEvent.Failed("Unexpected simulated payment status: ${result.status}")
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PaymentCheckoutReducer.reduce(
                state,
                PaymentCheckoutEvent.Failed(error.message ?: "Could not complete the simulated checkout.")
            )
        }
    }

    suspend fun createSimulatedRefund(
        state: PaymentCheckoutState,
        reason: String,
        amountMinor: Long? = null
    ): SimulatedGcashRefundResponse {
        check(state.status == CheckoutStatus.SIMULATED_SUCCEEDED && state.simulationOnly) {
            "Only a simulated successful checkout can be refunded in development."
        }
        require(reason.isNotBlank()) { "A refund reason is required." }
        return functions.httpsCallable("createSimulatedGcashRefund")(
            CreateSimulatedRefundRequest(state.sessionId, reason, amountMinor)
        ).data<SimulatedGcashRefundResponse>()
    }
}
