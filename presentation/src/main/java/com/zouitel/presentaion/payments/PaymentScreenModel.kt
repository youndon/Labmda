package com.zouitel.presentaion.payments

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.PaymentMethod
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Shared method selection, checkout progress, redirects and payment-status observation. */
class PaymentScreenModel(private val repository: PaymentRepository) : ScreenModel {
    private val _uiState = MutableStateFlow(PaymentUiState())
    val uiState: StateFlow<PaymentUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PaymentEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<PaymentEvent> = _events.asSharedFlow()
    private var paymentStatusJob: Job? = null

    fun selectMethod(method: PaymentMethod) {
        _uiState.value = _uiState.value.copy(selectedMethod = method, error = null)
    }

    fun checkoutStarted() {
        _uiState.value = _uiState.value.copy(
            isSubmitting = true, error = null, creation = null,
            observedStatus = null, isResultVisible = false
        )
        paymentStatusJob?.cancel()
        paymentStatusJob = null
    }

    fun checkoutCreated(result: PaymentCreationResult) {
        _uiState.value = _uiState.value.copy(
            isSubmitting = false, creation = result, observedStatus = null,
            isResultVisible = true, error = null
        )
        result.checkoutUrl?.takeIf { it.isNotBlank() }?.let {
            _events.tryEmit(PaymentEvent.OpenCheckout(it, result.webCheckoutUrl))
        }
        paymentStatusJob?.cancel()
        paymentStatusJob = screenModelScope.launch {
            runCatching {
                repository.observePaymentStatus(result.entityId).collect { observed ->
                    _uiState.value = _uiState.value.copy(observedStatus = observed)
                }
            }
        }
    }

    fun checkoutFailed(message: String) {
        _uiState.value = _uiState.value.copy(
            isSubmitting = false,
            error = message.ifBlank { "Payment could not be started. Please try again." }
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun dismissResult() {
        _uiState.value = _uiState.value.copy(isResultVisible = false)
    }
}
