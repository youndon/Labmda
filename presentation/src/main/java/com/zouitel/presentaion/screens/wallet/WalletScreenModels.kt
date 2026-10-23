package com.zouitel.presentaion.screens.wallet

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.Money
import com.zouitel.presentaion.screens.payment.PaymentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

abstract class BaseWalletScreenModel(
    protected val repository: PaymentRepository
) : ScreenModel {
    private val _wallet = MutableStateFlow<WalletSnapshot?>(null)
    val wallet: StateFlow<WalletSnapshot?> = _wallet.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    protected fun loadWallet(load: suspend () -> WalletSnapshot) {
        screenModelScope.launch {
            _isLoading.value = true
            _error.value = null
            runCatching { load() }
                .onSuccess { _wallet.value = it }
                .onFailure { _error.value = it.message ?: "Unable to load wallet." }
            _isLoading.value = false
        }
    }

    fun clearError() { _error.value = null }
}

class MerchantEarningsScreenModel(repository: PaymentRepository) : BaseWalletScreenModel(repository) {
    fun load() = loadWallet { repository.getMerchantWallet() }
    fun requestPayout(amount: Money, onFinished: (Result<Unit>) -> Unit = {}) {
        screenModelScope.launch {
            val result = runCatching { repository.requestPayout(amount) }
            result.onSuccess { load() }
            onFinished(result)
        }
    }
}

class CourierWalletScreenModel(repository: PaymentRepository) : BaseWalletScreenModel(repository) {
    fun load() = loadWallet { repository.getCourierWallet() }
    fun requestPayout(amount: Money, onFinished: (Result<Unit>) -> Unit = {}) {
        screenModelScope.launch {
            val result = runCatching { repository.requestPayout(amount) }
            result.onSuccess { load() }
            onFinished(result)
        }
    }
}

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