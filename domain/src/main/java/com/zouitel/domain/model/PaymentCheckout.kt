package com.zouitel.domain.model

/**
 * UI-facing checkout state. SIMULATED_* states are deliberately distinct from
 * PaymentStatus.SUCCEEDED so the app cannot confuse a test transaction with money received.
 */
enum class CheckoutStatus {
    IDLE,
    CREATING_SESSION,
    PENDING,
    SIMULATED_SUCCEEDED,
    SIMULATED_FAILED,
    EXPIRED,
    ERROR
}

data class PaymentCheckoutState(
    val status: CheckoutStatus = CheckoutStatus.IDLE,
    val sessionId: String = "",
    val paymentId: String = "",
    val amountMinor: Long = 0L,
    val currency: String = "PHP",
    val expiresAt: Long = 0L,
    val simulationOnly: Boolean = true,
    val message: String = ""
)

sealed interface PaymentCheckoutEvent {
    data object Start : PaymentCheckoutEvent
    data class SessionCreated(
        val sessionId: String,
        val paymentId: String,
        val amountMinor: Long,
        val expiresAt: Long,
        val simulationOnly: Boolean
    ) : PaymentCheckoutEvent
    data object SimulatedSuccess : PaymentCheckoutEvent
    data object SimulatedFailure : PaymentCheckoutEvent
    data object Expired : PaymentCheckoutEvent
    data class Failed(val reason: String) : PaymentCheckoutEvent
}

/** Pure reducer keeps Compose/ViewModel checkout transitions deterministic and testable. */
object PaymentCheckoutReducer {
    fun reduce(
        state: PaymentCheckoutState,
        event: PaymentCheckoutEvent
    ): PaymentCheckoutState = when (event) {
        PaymentCheckoutEvent.Start -> PaymentCheckoutState(
            status = CheckoutStatus.CREATING_SESSION,
            simulationOnly = true
        )
        is PaymentCheckoutEvent.SessionCreated -> {
            require(event.sessionId.isNotBlank()) { "Session ID is required." }
            require(event.paymentId.isNotBlank()) { "Payment ID is required." }
            require(event.amountMinor > 0L) { "Checkout amount must be positive PHP centavos." }
            require(event.expiresAt > 0L) { "Session expiry is required." }
            require(event.simulationOnly) { "This development checkout reducer accepts simulated sessions only." }
            state.copy(
                status = CheckoutStatus.PENDING,
                sessionId = event.sessionId,
                paymentId = event.paymentId,
                amountMinor = event.amountMinor,
                currency = "PHP",
                expiresAt = event.expiresAt,
                simulationOnly = true,
                message = ""
            )
        }
        PaymentCheckoutEvent.SimulatedSuccess -> state.terminalOrCopy(CheckoutStatus.SIMULATED_SUCCEEDED)
        PaymentCheckoutEvent.SimulatedFailure -> state.terminalOrCopy(CheckoutStatus.SIMULATED_FAILED)
        PaymentCheckoutEvent.Expired -> state.terminalOrCopy(CheckoutStatus.EXPIRED)
        is PaymentCheckoutEvent.Failed -> state.terminalOrCopy(CheckoutStatus.ERROR, event.reason)
    }

    private fun PaymentCheckoutState.terminalOrCopy(
        next: CheckoutStatus,
        reason: String = ""
    ): PaymentCheckoutState {
        if (status != CheckoutStatus.PENDING && status != CheckoutStatus.CREATING_SESSION) return this
        return copy(status = next, message = reason)
    }
}
