package com.zouitel.presentaion.screens.wallet

import com.zouitel.presentaion.model.Money
import kotlinx.serialization.Serializable

/**
 * Wallet DTOs returned by trusted Cloud Functions. Money uses integer minor units;
 * client UI must not derive balances from locally-written payout requests.
 */
@Serializable
data class WalletSummary(
    val availableBalance: Money = Money(),
    val pendingBalance: Money = Money(),
    val cashOwed: Money = Money(),
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

@Serializable
data class WalletSnapshot(
    val summary: WalletSummary = WalletSummary(),
    val transactions: List<WalletTransaction> = emptyList(),
    val payoutAccount: PayoutAccount? = null
)

/**
 * Store only a provider-issued opaque reference here; never store bank/card numbers,
 * passwords, or provider secrets in the mobile app.
 */
@Serializable
data class PayoutAccount(
    val provider: String = "",
    val providerAccountId: String = "",
    val countryCode: String = "",
    val accountLabel: String = "",
    val updatedAtEpochMillis: Long = 0L
)

// Kept for existing screens that still display legacy Double-valued prices.
fun Double.format(): String = "$${"%.2f".format(this)}"
