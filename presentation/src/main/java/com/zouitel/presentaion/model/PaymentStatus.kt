package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

@Serializable
enum class PaymentStatus {
    CREATED,
    PENDING,
    REQUIRES_ACTION,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    UNKNOWN
}