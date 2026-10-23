package com.zouitel.presentaion.screens.wallet

import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.Money
import com.zouitel.presentaion.screens.payment.PaymentRepository
import kotlinx.coroutines.launch

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
