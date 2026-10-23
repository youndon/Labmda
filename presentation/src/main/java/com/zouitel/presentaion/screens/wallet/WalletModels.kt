package com.zouitel.presentaion.screens.wallet

import com.zouitel.presentaion.model.Money
import kotlinx.serialization.Serializable

@Serializable
data class WalletSummary(
    val availableBalance: Money = Money(),
    val pendingBalance: Money = Money(),
    val lifetimeEarnings: Money = Money(),
    val updatedAtEpochMillis: Long = 0L
)

@Serializable
data class WalletTransaction(
    val id: String = "",
    val orderId: String = "",
    val type: String = "",
    val status: String = "",
    val amount: Money = Money(),
    val description: String = "",
    val createdAtEpochMillis: Long = 0L
)

/** Only opaque provider account references belong here; never store raw card/bank credentials. */
@Serializable
data class PayoutAccount(
    val provider: String = "",
    val providerAccountId: String = "",
    val countryCode: String = "",
    val accountLabel: String = "",
    val updatedAtEpochMillis: Long = 0L
)

@Serializable
data class WalletSnapshot(
    val summary: WalletSummary = WalletSummary(),
    val transactions: List<WalletTransaction> = emptyList(),
    val payoutAccount: PayoutAccount? = null
)