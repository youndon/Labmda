package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

/**
 * Provider-independent payment domain. All monetary values are PHP centavos.
 * These records describe financial state; trusted backend code owns their writes.
 */
@Serializable
enum class PaymentEntityType { ORDER, TRIP, DELIVERY }

@Serializable
enum class PaymentAllocationType { PLATFORM, MERCHANT, COURIER, DRIVER, OTHER }

@Serializable
enum class SettlementStatus { PENDING, PROCESSING, SETTLED, FAILED, REVERSED }

@Serializable
enum class WalletStatus { ACTIVE, SUSPENDED, CLOSED }

@Serializable
enum class LedgerEntryType {
    PAYMENT_CREDIT, ALLOCATION_DEBIT, ALLOCATION_CREDIT,
    CASH_COLLECTION, CASH_REMITTANCE, REFUND_DEBIT,
    PAYOUT_DEBIT, PAYOUT_REVERSAL, ADJUSTMENT
}

@Serializable
enum class LedgerDirection { DEBIT, CREDIT }

@Serializable
enum class RefundStatus { REQUESTED, PENDING, SUCCEEDED, FAILED, CANCELLED }

@Serializable
enum class CashCollectionStatus { EXPECTED, COLLECTED, PARTIALLY_COLLECTED, REMITTED, DISPUTED, VOIDED }

/**
 * One payment belongs to exactly one billable entity. For a delivery attached to
 * an order, entityType is ORDER and deliveryId links the courier leg; standalone
 * delivery charges may use entityType DELIVERY.
 */
@Serializable
data class Payment(
    val id: String = "",
    val entityType: PaymentEntityType = PaymentEntityType.ORDER,
    val entityId: String = "",
    val orderId: String? = null,
    val tripId: String? = null,
    val deliveryId: String? = null,
    val payerId: String = "",
    val paymentMethod: PaymentMethod = PaymentMethod.GCASH,
    val status: PaymentStatus = PaymentStatus.PENDING,
    val amountCentavos: Long = 0L,
    val refundedCentavos: Long = 0L,
    val currency: String = "PHP",
    val idempotencyKey: String = "",
    val createdAtTimestamp: Long = 0L,
    val updatedAtTimestamp: Long = 0L,
    val paidAtTimestamp: Long? = null,
    val failureCode: String? = null
)

/** A server-calculated share of a payment owed to one participant. */
@Serializable
data class PaymentAllocation(
    val id: String = "",
    val paymentId: String = "",
    val entityType: PaymentEntityType = PaymentEntityType.ORDER,
    val entityId: String = "",
    val beneficiaryId: String = "",
    val allocationType: PaymentAllocationType = PaymentAllocationType.MERCHANT,
    val amountCentavos: Long = 0L,
    val currency: String = "PHP",
    val status: SettlementStatus = SettlementStatus.PENDING,
    val createdAtTimestamp: Long = 0L
)

/** Transfer/reconciliation record for one allocation, independent of its calculation. */
@Serializable
data class PaymentSettlement(
    val id: String = "",
    val paymentId: String = "",
    val allocationId: String = "",
    val beneficiaryId: String = "",
    val amountCentavos: Long = 0L,
    val currency: String = "PHP",
    val status: SettlementStatus = SettlementStatus.PENDING,
    val externalReference: String? = null,
    val settledAtTimestamp: Long? = null,
    val createdAtTimestamp: Long = 0L,
    val updatedAtTimestamp: Long = 0L
)

/** Per-user PHP balance summary. Ledger entries remain the accounting source of truth. */
@Serializable
data class WalletAccount(
    val id: String = "",
    val ownerId: String = "",
    val status: WalletStatus = WalletStatus.ACTIVE,
    val currency: String = "PHP",
    val availableCentavos: Long = 0L,
    val pendingCentavos: Long = 0L,
    val cashOwedCentavos: Long = 0L,
    val lifetimeEarnedCentavos: Long = 0L,
    val lifetimePaidOutCentavos: Long = 0L,
    val createdAtTimestamp: Long = 0L,
    val updatedAtTimestamp: Long = 0L
)

/** Immutable accounting movement. Corrections use compensating entries, never edits. */
@Serializable
data class LedgerEntry(
    val id: String = "",
    val walletAccountId: String = "",
    val ownerId: String = "",
    val paymentId: String? = null,
    val allocationId: String? = null,
    val settlementId: String? = null,
    val payoutId: String? = null,
    val refundId: String? = null,
    val cashCollectionId: String? = null,
    val entryType: LedgerEntryType = LedgerEntryType.ADJUSTMENT,
    val direction: LedgerDirection = LedgerDirection.CREDIT,
    val amountCentavos: Long = 0L,
    val balanceAfterCentavos: Long? = null,
    val currency: String = "PHP",
    val description: String = "",
    val createdAtTimestamp: Long = 0L
)

/** A request to transfer an available wallet balance to a verified receiving account. */
@Serializable
data class Payout(
    val id: String = "",
    val walletAccountId: String = "",
    val beneficiaryId: String = "",
    val amountCentavos: Long = 0L,
    val currency: String = "PHP",
    val status: PayoutStatus = PayoutStatus.PENDING,
    val destinationAccountId: String? = null,
    val failureCode: String? = null,
    val requestedAtTimestamp: Long = 0L,
    val processedAtTimestamp: Long? = null,
    val completedAtTimestamp: Long? = null
)

/** Refund request against an original payment; total refunds cannot exceed paid amount. */
@Serializable
data class Refund(
    val id: String = "",
    val paymentId: String = "",
    val entityType: PaymentEntityType = PaymentEntityType.ORDER,
    val entityId: String = "",
    val requestedBy: String = "",
    val amountCentavos: Long = 0L,
    val currency: String = "PHP",
    val status: RefundStatus = RefundStatus.REQUESTED,
    val reason: String = "",
    val createdAtTimestamp: Long = 0L,
    val updatedAtTimestamp: Long = 0L,
    val completedAtTimestamp: Long? = null
)

/**
 * Physical cash received from a customer. Remittance obligations are tracked separately
 * so courier/driver cash handling does not get confused with online settlement.
 */
@Serializable
data class CashCollection(
    val id: String = "",
    val paymentId: String = "",
    val entityType: PaymentEntityType = PaymentEntityType.ORDER,
    val entityId: String = "",
    val orderId: String? = null,
    val tripId: String? = null,
    val deliveryId: String? = null,
    val collectedBy: String = "",
    val collectorRole: String = "",
    val amountCentavos: Long = 0L,
    val merchantOwedCentavos: Long = 0L,
    val platformOwedCentavos: Long = 0L,
    val collectorEarningsCentavos: Long = 0L,
    val remittedCentavos: Long = 0L,
    val outstandingRemittanceCentavos: Long = 0L,
    val currency: String = "PHP",
    val status: CashCollectionStatus = CashCollectionStatus.EXPECTED,
    val collectedAtTimestamp: Long? = null,
    val remittedAtTimestamp: Long? = null,
    val createdAtTimestamp: Long = 0L
)
