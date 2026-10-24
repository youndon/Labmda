package com.zouitel.presentaion.screens.wallet

import kotlinx.serialization.Serializable

@Serializable
data class WalletSummary(
    val availableBalance: Double = 0.0,
    val pendingBalance: Double = 0.0,
    val lifetimeEarnings: Double = 0.0
)

@Serializable
data class WalletTransaction(
    val id: String = "",
    val description: String = "",
    val type: String = "",
    val amount: Double = 0.0,
    val status: String = ""
)

@Serializable
data class MerchantWallet(
    val summary: WalletSummary = WalletSummary(),
    val transactions: List<WalletTransaction> = emptyList()
)

fun Double.format(): String = "$${"%.2f".format(this)}"
