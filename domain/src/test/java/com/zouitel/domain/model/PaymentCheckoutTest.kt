package com.zouitel.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentCheckoutTest {
    @Test
    fun simulatedSuccessIsNotRealPaymentSuccess() {
        val pending = PaymentCheckoutReducer.reduce(
            PaymentCheckoutReducer.reduce(PaymentCheckoutState(), PaymentCheckoutEvent.Start),
            PaymentCheckoutEvent.SessionCreated("session", "payment", 12000, 5000, true)
        )
        val completed = PaymentCheckoutReducer.reduce(pending, PaymentCheckoutEvent.SimulatedSuccess)

        assertEquals(CheckoutStatus.SIMULATED_SUCCEEDED, completed.status)
        assertTrue(completed.simulationOnly)
        assertFalse(completed.status.name == PaymentStatus.SUCCEEDED.name)
    }

    @Test
    fun failureAndExpiryAreTerminal() {
        val pending = PaymentCheckoutState(status = CheckoutStatus.PENDING, simulationOnly = true)
        val failed = PaymentCheckoutReducer.reduce(pending, PaymentCheckoutEvent.SimulatedFailure)
        assertEquals(CheckoutStatus.SIMULATED_FAILED, failed.status)
        assertEquals(failed, PaymentCheckoutReducer.reduce(failed, PaymentCheckoutEvent.SimulatedSuccess))

        val expired = PaymentCheckoutReducer.reduce(pending, PaymentCheckoutEvent.Expired)
        assertEquals(CheckoutStatus.EXPIRED, expired.status)
        assertEquals(expired, PaymentCheckoutReducer.reduce(expired, PaymentCheckoutEvent.SimulatedSuccess))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonSimulatedSession() {
        PaymentCheckoutReducer.reduce(
            PaymentCheckoutState(status = CheckoutStatus.CREATING_SESSION),
            PaymentCheckoutEvent.SessionCreated("session", "payment", 100, 5000, false)
        )
    }
}
