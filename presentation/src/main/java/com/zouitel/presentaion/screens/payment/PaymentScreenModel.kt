package com.zouitel.presentaion.screens.payment

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.PaymentMethod
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PaymentScreenModel(
    private val repository: PaymentRepository
) : ScreenModel {
    private val _uiState = MutableStateFlow(PaymentUiState())
    val uiState: StateFlow<PaymentUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PaymentEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun selectMethod(method: PaymentMethod) {
        _uiState.value = _uiState.value.copy(selectedMethod = method, errorMessage = null, session = null)
    }

    fun observeOrderPayment(orderId: String) {
        if (orderId.isBlank()) {
            _uiState.value = _uiState.value.copy(isLoadingPayment = false, errorMessage = "Missing order ID.")
            return
        }
        screenModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingPayment = true)
            try {
                repository.observePayment(orderId).collect { payment ->
                    _uiState.value = _uiState.value.copy(payment = payment, isLoadingPayment = false)
                }
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingPayment = false,
                    errorMessage = error.message ?: "Unable to observe payment status."
                )
            }
        }
    }

    fun pay(orderId: String) {
        if (_uiState.value.isSubmitting) return
        if (orderId.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Missing order ID.")
            return
        }
        val method = _uiState.value.selectedMethod
        _uiState.value = _uiState.value.copy(isSubmitting = true, errorMessage = null)
        screenModelScope.launch {
            try {
                when (method) {
                    PaymentMethod.CASH -> {
                        repository.requestCashPayment(orderId)
                        _events.emit(PaymentEvent.CashPaymentRequested)
                    }
                    PaymentMethod.STRIPE, PaymentMethod.PAYPAL -> {
                        val session = repository.createCheckout(orderId, method)
                        _uiState.value = _uiState.value.copy(session = session)
                        _events.emit(PaymentEvent.CheckoutReady(session))
                    }
                }
            } catch (error: Exception) {
                val message = error.message ?: "Could not start payment."
                _uiState.value = _uiState.value.copy(errorMessage = message)
                _events.emit(PaymentEvent.Error(message))
            } finally {
                _uiState.value = _uiState.value.copy(isSubmitting = false)
            }
        }
    }

    fun handleProviderReturn(orderId: String, provider: String, sessionId: String, resultToken: String) {
        screenModelScope.launch {
            try {
                repository.verifyProviderReturn(orderId, provider, sessionId, resultToken)
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = error.message ?: "The server could not verify the payment result."
                )
            }
        }
    }

    fun reportCheckoutLaunchError(message: String) {
        _uiState.value = _uiState.value.copy(
            errorMessage = message.ifBlank { "Could not open the payment provider. Please try again." }
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}