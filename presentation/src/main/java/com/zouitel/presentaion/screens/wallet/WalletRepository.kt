package com.zouitel.presentaion.screens.wallet

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions

data class WalletPayout(val id:String,val amountCentavos:Long,val status:String,val providerPayoutId:String,val failureCode:String,val createdAtTimestamp:Long,val maskedAccountNumber:String)
data class PayoutAccountSummary(val status:String="NOT_CONFIGURED",val accountType:String="",val channelCode:String="",val accountHolderName:String="",val maskedAccountNumber:String="",val submittedAtTimestamp:Long=0L,val reviewedAtTimestamp:Long=0L,val reviewNote:String="")
data class WalletSummary(val availableCentavos:Long=0L,val pendingCentavos:Long=0L,val cashOwedCentavos:Long=0L,val cashEarningsCentavos:Long=0L,val pendingPayoutCentavos:Long=0L,val paidOutCentavos:Long=0L,val refundDebtCentavos:Long=0L,val payoutAccount:PayoutAccountSummary=PayoutAccountSummary(),val payouts:List<WalletPayout> = emptyList())
data class AdminPaymentRow(val id:String,val entityType:String,val status:String,val provider:String,val clientId:String,val merchantId:String,val amountCentavos:Long,val currency:String)
data class AdminLedgerRow(val id:String,val entryType:String,val operationId:String,val totalCentavos:Long,val createdAtTimestamp:Long,val platformCommissionCentavos:Long)
data class AdminPayoutRow(val id:String,val userId:String,val status:String,val amountCentavos:Long,val providerPayoutId:String,val maskedAccountNumber:String,val failureCode:String)
data class AdminPayoutReviewRow(val userId:String,val status:String,val accountHolderName:String,val maskedAccountNumber:String,val reviewNote:String)
data class AdminPaymentsDashboard(val payments:List<AdminPaymentRow> = emptyList(),val ledgerEntries:List<AdminLedgerRow> = emptyList(),val payouts:List<AdminPayoutRow> = emptyList(),val payoutAccountReviews:List<AdminPayoutReviewRow> = emptyList(),val walletCount:Int=0,val availableCentavos:Long=0L,val pendingCentavos:Long=0L,val cashOwedCentavos:Long=0L,val paidOutCentavos:Long=0L,val cashPlatformOwedCentavos:Long=0L,val platformCommissionCentavos:Long=0L,val refundDebtCentavos:Long=0L)

class WalletRepository {
    private val functions=Firebase.functions("asia-southeast1")
    private suspend fun call(name:String,payload:Map<String,Any> = emptyMap()):Map<String,Any?> {
        val response=functions.httpsCallable(name)(payload)
        return response.data<Map<String,Any?>>()
    }
    private fun rec(value:Any?):Map<String,Any?> = value as? Map<String,Any?> ?: emptyMap()
    private fun Map<String,Any?>.s(key:String)=this[key] as? String ?: ""
    private fun Map<String,Any?>.n(key:String)=(this[key] as? Number)?.toLong() ?: 0L
    private fun Map<String,Any?>.commission():Long = (this["lines"] as? List<*>)?.sumOf { raw ->
        val line=rec(raw)
        if(line["accountType"]=="PLATFORM_COMMISSION_REVENUE") line.n("creditCentavos") else 0L
    } ?: 0L
    private fun rows(value: Any?): List<Map<String, Any?>> = (value as? List<*>)?.mapNotNull { rec(it).takeIf { x->x.isNotEmpty() } } ?: emptyList()
    private fun Map<String,Any?>.account()=PayoutAccountSummary(s("status").ifBlank{"NOT_CONFIGURED"},s("accountType"),s("channelCode"),s("accountHolderName"),s("maskedAccountNumber"),n("submittedAtTimestamp"),n("reviewedAtTimestamp"),s("reviewNote"))

    suspend fun loadWallet():WalletSummary {
        val r=call("getMyWallet")
        val payouts=rows(r["payouts"]).map { WalletPayout(it.s("id"),it.n("amountCentavos"),it.s("status"),it.s("providerPayoutId"),it.s("failureCode"),it.n("createdAtTimestamp"),it.s("maskedAccountNumber")) }
        return WalletSummary(r.n("availableCentavos"),r.n("pendingCentavos"),r.n("cashOwedCentavos"),r.n("cashEarningsCentavos"),r.n("pendingPayoutCentavos"),r.n("paidOutCentavos"),r.n("refundDebtCentavos"),rec(r["payoutAccount"]).account(),payouts)
    }
    suspend fun loadPayoutAccount()=call("getMyPayoutAccount").account()
    suspend fun saveGcashPayoutAccount(givenName:String,surname:String,accountHolderName:String,accountNumber:String,personalMobileNumber:String,streetLine1:String,city:String,provinceState:String,postalCode:String):PayoutAccountSummary =
        call("savePayoutAccount",mapOf("givenName" to givenName,"surname" to surname,"accountHolderName" to accountHolderName,"accountNumber" to accountNumber,"personalMobileNumber" to personalMobileNumber,"streetLine1" to streetLine1,"city" to city,"provinceState" to provinceState,"postalCode" to postalCode)).account()
    suspend fun requestPayout(key:String):WalletPayout {
        val r=call("requestPayout",mapOf("idempotencyKey" to key))
        return WalletPayout(r.s("payoutId"),r.n("amountCentavos"),r.s("status"),r.s("providerPayoutId"),"",0L,r.s("maskedAccountNumber"))
    }
    suspend fun loadAdminDashboard():AdminPaymentsDashboard {
        val r=call("getAdminPaymentDashboard")
        val payments=rows(r["payments"]).map { AdminPaymentRow(it.s("id"),it.s("entityType"),it.s("status"),it.s("provider"),it.s("clientId"),it.s("merchantId"),it.n("amountCentavos"),it.s("currency")) }
        val ledger=rows(r["ledgerEntries"]).map { AdminLedgerRow(it.s("id"),it.s("entryType"),it.s("operationId"),it.n("totalCentavos"),it.n("createdAtTimestamp"),it.commission()) }
        val payouts=rows(r["payouts"]).map { AdminPayoutRow(it.s("id"),it.s("userId"),it.s("status"),it.n("amountCentavos"),it.s("providerPayoutId"),it.s("maskedAccountNumber"),it.s("failureCode")) }
        val reviews=rows(r["payoutAccountReviews"]).map { AdminPayoutReviewRow(it.s("userId"),it.s("status"),it.s("accountHolderName"),it.s("maskedAccountNumber"),it.s("reviewNote")) }
        val wallets=rows(r["wallets"]); val cash=rows(r["cashCollections"])
        return AdminPaymentsDashboard(payments,ledger,payouts,reviews,wallets.size,wallets.sumOf{it.n("availableCentavos")},wallets.sumOf{it.n("pendingCentavos")},wallets.sumOf{it.n("cashOwedCentavos")},wallets.sumOf{it.n("paidOutCentavos")},cash.sumOf{it.n("platformOwedCentavos")},ledger.sumOf{it.platformCommissionCentavos},wallets.sumOf{it.n("refundDebtCentavos")})
    }
    suspend fun reviewPayoutAccount(uid:String,approved:Boolean,note:String) { call("adminReviewPayoutAccount",mapOf("userId" to uid,"approved" to approved,"note" to note)) }
    suspend fun refundPayment(type:String,id:String,key:String) { call("refundPayment",mapOf("entityType" to type,"entityId" to id,"idempotencyKey" to key)) }
}
