package com.zouitel.presentaion.screens.finance

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class EarningsTotals(
    val availableMinor: Long = 0,
    val pendingMinor: Long = 0,
    val cashOwedMinor: Long = 0,
    val clearingMinor: Long = 0
)

@Serializable
data class ProviderWallet(
    val accountId: String = "",
    val providerKind: String = "",
    val providerId: String = "",
    val availableMinor: Long = 0,
    val pendingMinor: Long = 0,
    val cashOwedMinor: Long = 0,
    val clearingMinor: Long = 0
)

@Serializable
data class FinancialPosting(
    val accountId: String = "",
    val bucket: String = "",
    val deltaMinor: Long = 0,
    val partyType: String = "",
    val partyId: String = ""
)

@Serializable
data class FinancialTransaction(
    val operationId: String = "",
    val eventType: String = "",
    val referenceType: String = "",
    val referenceId: String = "",
    val createdAt: Long = 0,
    val description: String = "",
    val postings: List<FinancialPosting> = emptyList()
)

@Serializable
data class EarningsResponse(
    val currency: String = "PHP",
    val totals: EarningsTotals = EarningsTotals(),
    val accounts: List<ProviderWallet> = emptyList(),
    val transactions: List<FinancialTransaction> = emptyList()
)

@Serializable
data class PayoutProfileResponse(
    val configured: Boolean = false,
    val method: String? = null,
    val accountName: String? = null,
    val maskedAccountNumber: String? = null,
    val updatedAt: Long? = null
)

@Serializable
data class PayoutHistoryItem(
    val payoutId: String = "",
    val amountMinor: Long = 0,
    val currency: String = "PHP",
    val method: String = "",
    val maskedAccountNumber: String = "",
    val status: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val transferReference: String? = null,
    val adminNote: String? = null
)

@Serializable
data class PayoutHistoryResponse(val items: List<PayoutHistoryItem> = emptyList())

@Serializable
data class PayoutRequestPayload(val amountMinor: Long, val accountId: String)

class ProviderEarningsScreenModel : ScreenModel {
    private val functions = Firebase.functions

    private val _earnings = MutableStateFlow(EarningsResponse())
    val earnings: StateFlow<EarningsResponse> = _earnings.asStateFlow()

    private val _profile = MutableStateFlow(PayoutProfileResponse())
    val profile: StateFlow<PayoutProfileResponse> = _profile.asStateFlow()

    private val _payouts = MutableStateFlow<List<PayoutHistoryItem>>(emptyList())
    val payouts: StateFlow<List<PayoutHistoryItem>> = _payouts.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { refresh() }

    fun refresh() {
        screenModelScope.launch {
            _isLoading.value = true
            _message.value = null
            runCatching {
                _earnings.value = functions.httpsCallable("getMyEarnings").invoke().data()
                _profile.value = functions.httpsCallable("getMyPayoutProfile").invoke().data()
                _payouts.value = functions.httpsCallable("getMyPayoutHistory").invoke().data()
            }.onFailure { error ->
                _message.value = error.message ?: "Could not load earnings."
            }
            _isLoading.value = false
        }
    }

    fun savePayoutProfile(method: String, accountName: String, accountNumber: String) {
        if (_isSubmitting.value) return
        screenModelScope.launch {
            _isSubmitting.value = true
            _message.value = null
            runCatching {
                functions.httpsCallable("saveMyPayoutProfile").invoke(
                    mapOf("method" to method, "accountName" to accountName.trim(), "accountNumber" to accountNumber.trim())
                )
                _profile.value = functions.httpsCallable("getMyPayoutProfile").invoke().data()
            }.onFailure { error ->
                _message.value = error.message ?: "Could not save payout details."
            }
            _isSubmitting.value = false
        }
    }

    fun requestPayout(amountText: String, accountId: String) {
        val amountMinor = parsePesosToMinor(amountText)
        if (amountMinor == null || amountMinor < 10_000L) {
            _message.value = "Enter at least ₱100.00."
            return
        }
        if (_isSubmitting.value) return
        screenModelScope.launch {
            _isSubmitting.value = true
            _message.value = null
            runCatching {
                functions.httpsCallable("requestMyPayout").invoke(PayoutRequestPayload(amountMinor, accountId))
                _message.value = "Payout request submitted for admin review."
                refresh()
            }.onFailure { error ->
                _message.value = error.message ?: "Could not submit payout request."
            }
            _isSubmitting.value = false
        }
    }

    private fun parsePesosToMinor(value: String): Long? {
        val normalized = value.trim().replace(",", "")
        if (!normalized.matches(Regex("^\\d{1,9}(\\.\\d{1,2})?$"))) return null
        val parts = normalized.split(".")
        val pesos = parts[0].toLongOrNull() ?: return null
        val centavos = (parts.getOrNull(1) ?: "").padEnd(2, '0').ifBlank { "00" }.toLongOrNull() ?: return null
        return (pesos * 100L + centavos).takeIf { it > 0 }
    }
}
