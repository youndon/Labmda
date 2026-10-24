package com.zouitel.presentaion.screens.wallet

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.gitlive.firebase.auth.FirebaseAuth
import dev.gitlive.firebase.database.DatabaseReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MerchantEarningsScreenModel(
    private val auth: FirebaseAuth,
    private val db: DatabaseReference
) : ScreenModel {

    private val _wallet = MutableStateFlow<MerchantWallet?>(null)
    val wallet: StateFlow<MerchantWallet?> = _wallet.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun load() {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                db.child("users").child(uid).child("wallet")
                    .valueEvents
                    .collect { snapshot ->
                        if (snapshot.exists) {
                            val w = runCatching { snapshot.value<MerchantWallet>() }.getOrNull()
                            _wallet.value = w ?: MerchantWallet()
                        } else {
                            _wallet.value = MerchantWallet()
                        }
                        _isLoading.value = false
                    }
            } catch (e: Exception) {
                _error.value = e.localizedMessage
                _isLoading.value = false
            }
        }
    }

    fun requestPayout(amount: Double) {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch(Dispatchers.IO) {
            try {
                db.child("payoutRequests").push().setValue(
                    mapOf(
                        "userId" to uid,
                        "amount" to amount,
                        "timestamp" to System.currentTimeMillis(),
                        "status" to "PENDING"
                    )
                )
            } catch (e: Exception) {
                _error.value = e.localizedMessage
            }
        }
    }
}
