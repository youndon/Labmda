package com.zouitel.presentaion.screens.wallet

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
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
