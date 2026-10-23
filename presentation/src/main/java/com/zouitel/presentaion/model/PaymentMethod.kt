package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

@Serializable
enum class PaymentMethod {
    CASH,
    STRIPE,
    PAYPAL
}