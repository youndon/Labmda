package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

/**
 * The state of collecting or refunding a customer's payment.
 *
 * This is deliberately independent of OrderStatus and TripStatus. A delivered order
 * or completed trip can still have a pending refund or a separate payout.
 */
@Serializable
enum class PaymentStatus {
    UNPAID,
    CREATING,
    INITIATION_FAILED,
    PENDING,
    PAID,
    COLLECTED,
    FAILED,
    CANCELLED,
    EXPIRED,
    REFUND_PENDING,
    PARTIALLY_REFUNDED,
    REFUNDED
}
