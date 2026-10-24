package com.zouitel.presentaion.screens.payment

import com.zouitel.presentaion.model.PaymentMethodType
import com.zouitel.presentaion.model.PaymentRecord
import com.zouitel.presentaion.model.PaymentSession

data class PaymentUiState(
    val selectedMethod: PaymentMethodType = PaymentMethodType.CASH,
    val payment: PaymentRecord? = null,
    val session: PaymentSession? = null,
    val isSubmitting: Boolean = false,
    val isLoadingPayment: Boolean = false,
    val errorMessage: String? = null
)