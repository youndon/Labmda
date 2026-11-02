package com.zouitel.domain.model

import kotlinx.serialization.Serializable

/** Payment methods currently supported by Labmda. */
@Serializable
enum class PaymentMethod {
    GCASH,
    CASH
}

/** Overall lifecycle of a payment. Cash remains PENDING until collection is confirmed. */
@Serializable
enum class PaymentStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED,
    PARTIALLY_REFUNDED,
    REFUNDED
}

@Serializable
enum class PaymentTransactionType {
    ORDER,
    RIDE
}

@Serializable
enum class PaymentBeneficiaryType {
    PLATFORM,
    MERCHANT,
    DELIVERY,
    DRIVER
}

@Serializable
enum class AllocationStatus {
    PENDING,
    READY,
    SETTLED,
    REVERSED
}

@Serializable
enum class SettlementStatus {
    PENDING,
    PROCESSING,
    SETTLED,
    FAILED,
    REVERSED
}

@Serializable
enum class LedgerEntryType {
    PAYMENT_RECEIVED,
    ALLOCATION_CREDIT,
    PLATFORM_FEE,
    DELIVERY_EARNING,
    RIDE_EARNING,
    MERCHANT_EARNING,
    CASH_COLLECTED,
    PAYOUT_DEBIT,
    REFUND_DEBIT,
    ADJUSTMENT
}

@Serializable
enum class LedgerDirection {
    CREDIT,
    DEBIT
}

@Serializable
enum class PayoutStatus {
    PENDING,
    PROCESSING,
    PAID,
    FAILED,
    CANCELLED
}

@Serializable
enum class RefundStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

@Serializable
enum class CashCollectionStatus {
    PENDING,
    COLLECTED,
    FAILED,
    DISPUTED
}

/**
 * A payment belongs to exactly one commercial transaction:
 * - ORDER: orderId is required; tripId must be blank. deliveryId optionally links
 *   the courier assigned to that order.
 * - RIDE: tripId is required; orderId and deliveryId must be blank.
 *
 * Amounts are integer PHP centavos (₱1.00 = 100). Do not use Double for money.
 */
@Serializable
data class Payment(
    val id: String = "",
    val transactionType: PaymentTransactionType = PaymentTransactionType.ORDER,
    val orderId: String = "",
    val tripId: String = "",
    val deliveryId: String = "",
    val payerId: String = "",
    val method: PaymentMethod = PaymentMethod.CASH,
    val status: PaymentStatus = PaymentStatus.PENDING,
    val amountMinor: Long = 0L,
    val currency: String = "PHP",
    val refundedAmountMinor: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val completedAt: Long? = null
)

/** A planned split of a successful payment. Sum of allocations must not exceed payment amount. */
@Serializable
data class PaymentAllocation(
    val id: String = "",
    val paymentId: String = "",
    val transactionType: PaymentTransactionType = PaymentTransactionType.ORDER,
    val orderId: String = "",
    val tripId: String = "",
    val deliveryId: String = "",
    val beneficiaryId: String = "",
    val beneficiaryType: PaymentBeneficiaryType = PaymentBeneficiaryType.PLATFORM,
    val amountMinor: Long = 0L,
    val currency: String = "PHP",
    val status: AllocationStatus = AllocationStatus.PENDING,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/** Records an actual or attempted movement of allocated funds to a beneficiary. */
@Serializable
data class PaymentSettlement(
    val id: String = "",
    val paymentId: String = "",
    val allocationId: String = "",
    val beneficiaryId: String = "",
    val beneficiaryType: PaymentBeneficiaryType = PaymentBeneficiaryType.PLATFORM,
    val amountMinor: Long = 0L,
    val currency: String = "PHP",
    val status: SettlementStatus = SettlementStatus.PENDING,
    val settledAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/** Cached wallet balance. Ledger entries remain the accounting source of truth. */
@Serializable
data class WalletAccount(
    val id: String = "",
    val ownerId: String = "",
    val ownerType: PaymentBeneficiaryType = PaymentBeneficiaryType.MERCHANT,
    val currency: String = "PHP",
    val availableBalanceMinor: Long = 0L,
    val pendingBalanceMinor: Long = 0L,
    val cashOwedMinor: Long = 0L,
    val updatedAt: Long = 0L
)

/** Append-only accounting record. amountMinor is always positive; direction determines its effect. */
@Serializable
data class LedgerEntry(
    val id: String = "",
    val walletId: String = "",
    val ownerId: String = "",
    val paymentId: String = "",
    val allocationId: String = "",
    val settlementId: String = "",
    val payoutId: String = "",
    val refundId: String = "",
    val cashCollectionId: String = "",
    val entryType: LedgerEntryType = LedgerEntryType.ADJUSTMENT,
    val direction: LedgerDirection = LedgerDirection.CREDIT,
    val amountMinor: Long = 0L,
    val currency: String = "PHP",
    val description: String = "",
    val createdAt: Long = 0L
)

@Serializable
data class Payout(
    val id: String = "",
    val walletId: String = "",
    val beneficiaryId: String = "",
    val beneficiaryType: PaymentBeneficiaryType = PaymentBeneficiaryType.MERCHANT,
    val amountMinor: Long = 0L,
    val currency: String = "PHP",
    val status: PayoutStatus = PayoutStatus.PENDING,
    val settlementIds: List<String> = emptyList(),
    val requestedAt: Long = 0L,
    val processedAt: Long? = null,
    val completedAt: Long? = null,
    val failureReason: String = ""
)

@Serializable
data class Refund(
    val id: String = "",
    val paymentId: String = "",
    val orderId: String = "",
    val tripId: String = "",
    val requestedBy: String = "",
    val amountMinor: Long = 0L,
    val currency: String = "PHP",
    val reason: String = "",
    val status: RefundStatus = RefundStatus.PENDING,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val completedAt: Long? = null
)

/** Cash handover evidence. A confirmed collection can reconcile a CASH payment. */
@Serializable
data class CashCollection(
    val id: String = "",
    val paymentId: String = "",
    val orderId: String = "",
    val tripId: String = "",
    val deliveryId: String = "",
    val collectorId: String = "",
    val payerId: String = "",
    val amountMinor: Long = 0L,
    val currency: String = "PHP",
    val status: CashCollectionStatus = CashCollectionStatus.PENDING,
    val collectedAt: Long? = null,
    val createdAt: Long = 0L,
    val notes: String = ""
)
