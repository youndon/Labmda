package com.zouitel.presentaion.payments

sealed interface PaymentEvent {
    data class OpenCheckout(
        val checkoutUrl: String,
        val fallbackUrl: String?
    ) : PaymentEvent
}
