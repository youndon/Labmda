package com.zouitel.presentaion.admin

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.Money
import com.zouitel.presentaion.screens.payment.PaymentRepository
import com.zouitel.presentaion.model.PaymentRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AdminPaymentsUiState(
    val payments: List<PaymentRecord> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val reconciling: Boolean = false
)

class AdminPaymentsScreenModel(
    private val repository: PaymentRepository
) : ScreenModel {
    private val _state = MutableStateFlow(AdminPaymentsUiState())
    val state: StateFlow<AdminPaymentsUiState> = _state.asStateFlow()

    fun loadLedger() {
        screenModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null, message = null)
            runCatching { repository.getAdminLedger() }
                .onSuccess { result ->
                    _state.value = _state.value.copy(
                        payments = result.payments,
                        isLoading = false,
                        message = "Loaded ${result.payments.size} payment record(s)."
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(isLoading = false, error = error.message ?: "Could not load payment ledger.")
                }
        }
    }

    fun refund(orderId: String, amountMinorUnits: Long?, currencyCode: String?, reason: String) {
        if (orderId.isBlank()) {
            _state.value = _state.value.copy(error = "Order ID is required.")
            return
        }
        screenModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null, message = null)
            val amount = amountMinorUnits?.let { Money(it, currencyCode ?: "USD") }
            runCatching { repository.refundPayment(orderId, amount, reason) }
                .onSuccess {
                    _state.value = _state.value.copy(isLoading = false, message = "Refund request accepted for server processing.")
                    loadLedger()
                }
                .onFailure { e -> _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Refund request failed.") }
        }
    }

    fun reconcile(fromEpochMillis: Long, toEpochMillis: Long) {
        screenModelScope.launch {
            _state.value = _state.value.copy(reconciling = true, error = null, message = null)
            runCatching { repository.reconcilePayments(fromEpochMillis, toEpochMillis) }
                .onSuccess { result ->
                    _state.value = _state.value.copy(
                        reconciling = false,
                        message = result.message.ifBlank {
                            "Reconciliation finished: ${result.matchedCount} matched, ${result.unmatchedCount} unmatched."
                        }
                    )
                    loadLedger()
                }
                .onFailure { e -> _state.value = _state.value.copy(reconciling = false, error = e.message ?: "Reconciliation failed.") }
        }
    }
}