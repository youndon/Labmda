package com.zouitel.presentaion.screens.payment

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PaymentCenterScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val uid = Firebase.auth.currentUser?.uid

    private val _payments = MutableStateFlow<List<PaymentActivityItem>>(emptyList())
    val payments: StateFlow<List<PaymentActivityItem>> = _payments.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToPaymentActivity()
    }

    private fun listenToPaymentActivity() {
        val currentUid = uid
        if (currentUid.isNullOrBlank()) {
            _isLoading.value = false
            return
        }

        val paymentRef = db.child("payments")
        val customerPayments = paymentRef.orderByChild("clientId").equalTo(currentUid).valueEvents
        val merchantPayments = paymentRef.orderByChild("merchantId").equalTo(currentUid).valueEvents

        screenModelScope.launch {
            runCatching {
                combine(customerPayments, merchantPayments) { customerSnapshot, merchantSnapshot ->
                    customerSnapshot.children + merchantSnapshot.children
                }.collect { snapshots ->
                    _payments.value = snapshots
                        .distinctBy { it.key }
                        .mapNotNull { snapshot ->
                            val id = snapshot.key ?: return@mapNotNull null
                            runCatching {
                                fun string(key: String): String =
                                    runCatching { snapshot.child(key).value<String>() }.getOrDefault("")
                                fun double(key: String): Double =
                                    runCatching { snapshot.child(key).value<Double>() }.getOrDefault(0.0)
                                fun long(key: String): Long =
                                    runCatching { snapshot.child(key).value<Long>() }.getOrDefault(0L)

                                PaymentActivityItem(
                                    id = id,
                                    entityType = string("entityType"),
                                    provider = string("provider"),
                                    channelCode = string("channelCode"),
                                    status = string("status").ifBlank { "UNKNOWN" },
                                    amount = double("amount"),
                                    currency = string("currency").ifBlank { "PHP" },
                                    createdAtTimestamp = long("createdAtTimestamp")
                                )
                            }.getOrNull()
                        }
                        .sortedByDescending { it.createdAtTimestamp }
                    _isLoading.value = false
                }
            }.onFailure {
                // Preserve the screen and surface no records if a security rule denies a query.
                _payments.value = emptyList()
                _isLoading.value = false
            }
        }
    }
}
