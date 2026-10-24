package com.zouitel.presentaion.screens.wallet

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.screens.payment.PaymentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PayoutAccountScreenModel(
    private val repository: PaymentRepository
) : ScreenModel {
    private val _account = MutableStateFlow<PayoutAccount?>(null)
    val account: StateFlow<PayoutAccount?> = _account.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    fun load() {
        screenModelScope.launch {
            _isLoading.value = true
            _error.value = null
            runCatching { repository.getPayoutAccount() }
                .onSuccess { _account.value = it }
                .onFailure { _error.value = it.message ?: "Unable to load payout account." }
            _isLoading.value = false
        }
    }

    fun save(account: PayoutAccount) {
        screenModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _saved.value = false
            runCatching { repository.savePayoutAccount(account) }
                .onSuccess { _account.value = account; _saved.value = true }
                .onFailure { _error.value = it.message ?: "Unable to save payout account." }
            _isLoading.value = false
        }
    }
}
