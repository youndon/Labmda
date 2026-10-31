package com.zouitel.presentaion.screens.wallet

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions

data class WalletPayoutRequest(
    val id: String,
    val amountCentavos: Long,
    val status: String,
    val providerStatus: String,
    val createdAtTimestamp: Long,
    val updatedAtTimestamp: Long,
    val maskedAccountNumber: String,
    val rejectionNote: String = ""
)

data class WalletPayout(
    val id: String,
    val amountCentavos: Long,
    val status: String,
    val providerPayoutId: String,
    val failureCode: String,
    val createdAtTimestamp: Long,
    val maskedAccountNumber: String,
    val failureMessage: String = ""
)

data class PayoutAccountSummary(
    val status: String = "NOT_CONFIGURED",
    val accountType: String = "",
    val channelCode: String = "",
    val accountHolderName: String = "",
    val maskedAccountNumber: String = "",
    val submittedAtTimestamp: Long = 0L,
    val reviewedAtTimestamp: Long = 0L,
    val reviewNote: String = ""
)

data class WalletSummary(
    val availableCentavos: Long = 0L,
    val pendingCentavos: Long = 0L,
    val cashOwedCentavos: Long = 0L,
    val cashEarningsCentavos: Long = 0L,
    val pendingPayoutCentavos: Long = 0L,
    val paidOutCentavos: Long = 0L,
    val refundDebtCentavos: Long = 0L,
    val payoutAccount: PayoutAccountSummary = PayoutAccountSummary(),
    val payouts: List<WalletPayout> = emptyList(),
    val payoutRequests: List<WalletPayoutRequest> = emptyList(),
    val activePayoutRequest: WalletPayoutRequest? = null
)

data class AdminPaymentRow(val id:String,val entityType:String,val status:String,val provider:String,val clientId:String,val merchantId:String,val amountCentavos:Long,val refundedCentavos:Long,val pendingRefundCentavos:Long,val refundStatus:String,val currency:String)
data class AdminLedgerRow(val id:String,val entryType:String,val operationId:String,val totalCentavos:Long,val createdAtTimestamp:Long,val platformCommissionCentavos:Long)
data class AdminPayoutRow(val id:String,val userId:String,val status:String,val amountCentavos:Long,val providerPayoutId:String,val maskedAccountNumber:String,val failureCode:String,val failureMessage:String = "")
data class AdminPayoutRequestRow(
    val id: String,
    val userId: String,
    val status: String,
    val amountCentavos: Long,
    val maskedAccountNumber: String,
    val createdAtTimestamp: Long,
    val accountChannelCode: String,
    val rejectionNote: String
)
data class AdminPayoutReviewRow(val userId:String,val status:String,val accountHolderName:String,val maskedAccountNumber:String,val reviewNote:String)
data class AdminPaymentsDashboard(
    val payments:List<AdminPaymentRow> = emptyList(),
    val ledgerEntries:List<AdminLedgerRow> = emptyList(),
    val payouts:List<AdminPayoutRow> = emptyList(),
    val payoutRequests:List<AdminPayoutRequestRow> = emptyList(),
    val payoutAccountReviews:List<AdminPayoutReviewRow> = emptyList(),
    val walletCount:Int=0,
    val availableCentavos:Long=0L,
    val pendingCentavos:Long=0L,
    val cashOwedCentavos:Long=0L,
    val paidOutCentavos:Long=0L,
    val cashPlatformOwedCentavos:Long=0L,
    val platformCommissionCentavos:Long=0L,
    val refundDebtCentavos:Long=0L
)

class WalletRepository {
    private val functions = Firebase.functions("asia-southeast1")

    private suspend fun call(name: String, payload: Map<String, Any> = emptyMap()): Map<String, Any?> {
        val response = functions.httpsCallable(name)(payload)
        return response.data<Map<String, Any?>>()
    }

    private fun rec(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()
    private fun Map<String, Any?>.s(key: String) = this[key] as? String ?: ""
    private fun Map<String, Any?>.n(key: String) = (this[key] as? Number)?.toLong() ?: 0L
    private fun Map<String, Any?>.commission(): Long = (this["lines"] as? List<*>)?.sumOf { raw ->
        val line = rec(raw)
        if (line["accountType"] == "PLATFORM_COMMISSION_REVENUE") line.n("creditCentavos") - line.n("debitCentavos") else 0L
    } ?: 0L
    private fun rows(value: Any?): List<Map<String, Any?>> =
        (value as? List<*>)?.mapNotNull { rec(it).takeIf { row -> row.isNotEmpty() } } ?: emptyList()

    private fun Map<String, Any?>.account() = PayoutAccountSummary(
        status = s("status").ifBlank { "NOT_CONFIGURED" },
        accountType = s("accountType"),
        channelCode = s("channelCode"),
        accountHolderName = s("accountHolderName"),
        maskedAccountNumber = s("maskedAccountNumber"),
        submittedAtTimestamp = n("submittedAtTimestamp"),
        reviewedAtTimestamp = n("reviewedAtTimestamp"),
        reviewNote = s("reviewNote")
    )

    private fun Map<String, Any?>.payoutRequest() = WalletPayoutRequest(
        id = s("id"),
        amountCentavos = n("amountCentavos"),
        status = s("status"),
        providerStatus = s("providerStatus"),
        createdAtTimestamp = n("createdAtTimestamp"),
        updatedAtTimestamp = n("updatedAtTimestamp"),
        maskedAccountNumber = s("maskedAccountNumber"),
        rejectionNote = s("rejectionNote")
    )

    private fun Map<String, Any?>.payout() = WalletPayout(
        id = s("id"),
        amountCentavos = n("amountCentavos"),
        status = s("status"),
        providerPayoutId = s("providerPayoutId"),
        failureCode = s("failureCode"),
        createdAtTimestamp = n("createdAtTimestamp"),
        maskedAccountNumber = s("maskedAccountNumber"),
        failureMessage = s("failureMessage").ifBlank { s("failureReason") }
    )

    suspend fun loadWallet(): WalletSummary {
        val r = call("getMyWallet")
        val payoutRequests = rows(r["payoutRequests"]).map { it.payoutRequest() }
        val payouts = rows(r["payouts"]).map { it.payout() }
        val activeRequest = rec(r["activePayoutRequest"]).takeIf { it.isNotEmpty() }?.payoutRequest()
        return WalletSummary(
            availableCentavos = r.n("availableCentavos"),
            pendingCentavos = r.n("pendingCentavos"),
            cashOwedCentavos = r.n("cashOwedCentavos"),
            cashEarningsCentavos = r.n("cashEarningsCentavos"),
            pendingPayoutCentavos = r.n("pendingPayoutCentavos"),
            paidOutCentavos = r.n("paidOutCentavos"),
            refundDebtCentavos = r.n("refundDebtCentavos"),
            payoutAccount = rec(r["payoutAccount"]).account(),
            payouts = payouts,
            payoutRequests = payoutRequests,
            activePayoutRequest = activeRequest
        )
    }

    suspend fun loadPayoutAccount() = call("getMyPayoutAccount").account()

    suspend fun saveGcashPayoutAccount(
        givenName: String, surname: String, accountHolderName: String, accountNumber: String,
        personalMobileNumber: String, streetLine1: String, city: String, provinceState: String, postalCode: String
    ): PayoutAccountSummary = call(
        "savePayoutAccount",
        mapOf(
            "givenName" to givenName, "surname" to surname, "accountHolderName" to accountHolderName,
            "accountNumber" to accountNumber, "personalMobileNumber" to personalMobileNumber,
            "streetLine1" to streetLine1, "city" to city, "provinceState" to provinceState, "postalCode" to postalCode
        )
    ).account()

    suspend fun requestPayout(key: String): WalletPayoutRequest {
        val r = call("requestPayout", mapOf("idempotencyKey" to key))
        return WalletPayoutRequest(
            id = r.s("payoutRequestId"),
            amountCentavos = r.n("amountCentavos"),
            status = r.s("status"),
            providerStatus = "",
            createdAtTimestamp = 0L,
            updatedAtTimestamp = 0L,
            maskedAccountNumber = r.s("maskedAccountNumber")
        )
    }

    suspend fun loadAdminDashboard(): AdminPaymentsDashboard {
        val r = call("getAdminPaymentDashboard")
        val payments = rows(r["payments"]).map {
            AdminPaymentRow(it.s("id"), it.s("entityType"), it.s("status"), it.s("provider"), it.s("clientId"), it.s("merchantId"), it.n("amountCentavos"), it.n("refundedCentavos"), it.n("pendingRefundCentavos"), it.s("refundStatus"), it.s("currency"))
        }
        val ledger = rows(r["ledgerEntries"]).map {
            AdminLedgerRow(it.s("id"), it.s("entryType"), it.s("operationId"), it.n("totalCentavos"), it.n("createdAtTimestamp"), it.commission())
        }
        val payouts = rows(r["payouts"]).map { it.payout().let { p -> AdminPayoutRow(p.id, it.s("userId"), p.status, p.amountCentavos, p.providerPayoutId, p.maskedAccountNumber, p.failureCode, p.failureMessage) } }
        val requests = rows(r["payoutRequests"]).map {
            AdminPayoutRequestRow(it.s("id"), it.s("userId"), it.s("status"), it.n("amountCentavos"), it.s("maskedAccountNumber"), it.n("createdAtTimestamp"), it.s("accountChannelCode"), it.s("rejectionNote"))
        }
        val reviews = rows(r["payoutAccountReviews"]).map {
            AdminPayoutReviewRow(it.s("userId"), it.s("status"), it.s("accountHolderName"), it.s("maskedAccountNumber"), it.s("reviewNote"))
        }
        val wallets = rows(r["wallets"])
        val cash = rows(r["cashCollections"])
        return AdminPaymentsDashboard(
            payments = payments, ledgerEntries = ledger, payouts = payouts, payoutRequests = requests,
            payoutAccountReviews = reviews, walletCount = wallets.size,
            availableCentavos = wallets.sumOf { it.n("availableCentavos") },
            pendingCentavos = wallets.sumOf { it.n("pendingCentavos") },
            cashOwedCentavos = wallets.sumOf { it.n("cashOwedCentavos") },
            paidOutCentavos = wallets.sumOf { it.n("paidOutCentavos") },
            cashPlatformOwedCentavos = cash.sumOf { it.n("platformOwedCentavos") },
            platformCommissionCentavos = ledger.sumOf { it.platformCommissionCentavos },
            refundDebtCentavos = wallets.sumOf { it.n("refundDebtCentavos") }
        )
    }

    suspend fun approvePayoutRequest(id: String) {
        call("adminApprovePayoutRequest", mapOf("payoutRequestId" to id))
    }

    suspend fun rejectPayoutRequest(id: String, note: String) {
        call("adminRejectPayoutRequest", mapOf("payoutRequestId" to id, "note" to note))
    }

    suspend fun reviewPayoutAccount(uid: String, approved: Boolean, note: String) {
        call("adminReviewPayoutAccount", mapOf("userId" to uid, "approved" to approved, "note" to note))
    }

    suspend fun refundPayment(type: String, id: String, key: String) {
        call("refundPayment", mapOf("entityType" to type, "entityId" to id, "idempotencyKey" to key))
    }
}
