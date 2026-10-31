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

data class PaymentRefundActivity(
    val id: String,
    val amountCentavos: Long,
    val status: String,
    val failureCode: String,
    val walletSettlementStatus: String,
    val createdAtTimestamp: Long
)

data class PaymentActivityItem(
    val id: String,
    val entityType: String,
    val provider: String,
    val channelCode: String,
    val status: String,
    val amountCentavos: Long,
    val currency: String,
    val createdAtTimestamp: Long,
    val updatedAtTimestamp: Long,
    val failureCode: String,
    val refundedCentavos: Long,
    val pendingRefundCentavos: Long,
    val refundStatus: String,
    val settlementStatus: String,
    val earningsSettlementStatus: String,
    val earningsPendingStatus: String,
    val refunds: List<PaymentRefundActivity>
)

class PaymentCenterScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val uid = Firebase.auth.currentUser?.uid
    private val _payments = MutableStateFlow<List<PaymentActivityItem>>(emptyList())
    val payments: StateFlow<List<PaymentActivityItem>> = _payments.asStateFlow()
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { listenToPaymentActivity() }

    private fun listenToPaymentActivity() {
        val currentUid = uid
        if (currentUid.isNullOrBlank()) {
            _isLoading.value = false
            _error.value = "Sign in to view payment activity."
            return
        }
        val ref = db.child("payments")
        val customer = ref.orderByChild("clientId").equalTo(currentUid).valueEvents
        val merchant = ref.orderByChild("merchantId").equalTo(currentUid).valueEvents
        val courier = ref.orderByChild("deliveryId").equalTo(currentUid).valueEvents
        val driver = ref.orderByChild("driverId").equalTo(currentUid).valueEvents

        screenModelScope.launch {
            runCatching {
                val customerAndMerchant = combine(customer, merchant) { a, b -> a.children + b.children }
                val courierAndDriver = combine(courier, driver) { a, b -> a.children + b.children }
                customerAndMerchant.combine(courierAndDriver) { first, second -> first + second }
                    .collect { snapshots ->
                        _payments.value = snapshots.distinctBy { it.key }.mapNotNull { snapshot ->
                            val id = snapshot.key ?: return@mapNotNull null
                            runCatching {
                                fun string(key: String): String = runCatching { snapshot.child(key).value<String>() }.getOrDefault("")
                                fun long(key: String): Long = runCatching { snapshot.child(key).value<Long>() }.getOrDefault(0L)
                                val refunds = snapshot.child("refunds").children.mapNotNull { refund ->
                                    val refundId = refund.key ?: return@mapNotNull null
                                    runCatching {
                                        fun rs(key: String) = runCatching { refund.child(key).value<String>() }.getOrDefault("")
                                        fun rn(key: String) = runCatching { refund.child(key).value<Long>() }.getOrDefault(0L)
                                        PaymentRefundActivity(
                                            id = refundId,
                                            amountCentavos = rn("amountCentavos"),
                                            status = rs("status"),
                                            failureCode = rs("failureCode"),
                                            walletSettlementStatus = rs("walletSettlementStatus"),
                                            createdAtTimestamp = rn("createdAtTimestamp")
                                        )
                                    }.getOrNull()
                                }.sortedByDescending { it.createdAtTimestamp }
                                PaymentActivityItem(
                                    id = id,
                                    entityType = string("entityType"),
                                    provider = string("provider"),
                                    channelCode = string("channelCode"),
                                    status = string("status").ifBlank { "UNKNOWN" },
                                    amountCentavos = long("amountCentavos").takeIf { it > 0L }
                                        ?: ((runCatching { snapshot.child("amount").value<Double>() }.getOrDefault(0.0)) * 100).toLong(),
                                    currency = string("currency").ifBlank { "PHP" },
                                    createdAtTimestamp = long("createdAtTimestamp"),
                                    updatedAtTimestamp = long("updatedAtTimestamp"),
                                    failureCode = string("failureCode"),
                                    refundedCentavos = long("refundedCentavos"),
                                    pendingRefundCentavos = long("pendingRefundCentavos"),
                                    refundStatus = string("refundStatus"),
                                    settlementStatus = string("settlementStatus"),
                                    earningsSettlementStatus = string("earningsSettlementStatus"),
                                    earningsPendingStatus = string("earningsPendingStatus"),
                                    refunds = refunds
                                )
                            }.getOrNull()
                        }.sortedByDescending { it.createdAtTimestamp }
                        _isLoading.value = false
                        _error.value = null
                    }
            }.onFailure {
                _payments.value = emptyList()
                _isLoading.value = false
                _error.value = it.message ?: "Payment activity could not be loaded. Please refresh later."
            }
        }
    }
}
