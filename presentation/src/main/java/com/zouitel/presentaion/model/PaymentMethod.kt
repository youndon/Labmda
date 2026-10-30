package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

/**
 * Payment methods currently supported by Labmda in the Philippines.
 */
@Serializable
enum class PaymentMethod {
    GCASH,
    CASH
}
