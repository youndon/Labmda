package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

/**
 * The state of a disbursement to a merchant, courier, or ride driver.
 *
 * Payout progress is tracked separately from customer payment and order/trip progress.
 * For example, an order may be DELIVERED while its payout remains PENDING.
 */
@Serializable
enum class PayoutStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    ON_HOLD
}
