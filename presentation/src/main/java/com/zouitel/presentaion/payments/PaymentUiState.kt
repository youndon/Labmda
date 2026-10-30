package com.zouitel.presentaion.payments

import com.zouitel.presentaion.model.PaymentMethod

data class PaymentUiState(
    val selectedMethod: PaymentMethod = PaymentMethod.GCASH,
    val isSubmitting: Boolean = false,
    val creation: PaymentCreationResult? = null,
    val observedStatus: ObservedPaymentStatus? = null,
    val isResultVisible: Boolean = false,
    val error: String? = null
) {
    val paymentStatus: String
        get() = observedStatus?.status ?: creation?.paymentStatus ?: "UNPAID"
}
