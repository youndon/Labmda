package com.zouitel.presentaion.screens.payment

import com.zouitel.presentaion.model.PaymentSession

sealed interface PaymentEvent {
    data class CheckoutReady(val session: PaymentSession) : PaymentEvent
    data object CashPaymentRequested : PaymentEvent
    data class Error(val message: String) : PaymentEvent
}