/** Shared payment-domain types. Amounts are integer PHP centavos (₱1.00 = 100). */

export const PAYMENT_METHODS = ["GCASH", "CASH"] as const;
export type PaymentMethod = (typeof PAYMENT_METHODS)[number];

export const PAYMENT_STATUSES = [
  "PENDING", "PROCESSING", "SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED",
  "PARTIALLY_REFUNDED", "REFUNDED",
] as const;
export type PaymentStatus = (typeof PAYMENT_STATUSES)[number];

export type PaymentTransactionType = "ORDER" | "RIDE";
export type PaymentBeneficiaryType = "PLATFORM" | "MERCHANT" | "DELIVERY" | "DRIVER";
export type AllocationStatus = "PENDING" | "READY" | "SETTLED" | "REVERSED";
export type SettlementStatus = "PENDING" | "PROCESSING" | "SETTLED" | "FAILED" | "REVERSED";
export type LedgerEntryType =
  | "PAYMENT_RECEIVED" | "ALLOCATION_CREDIT" | "PLATFORM_FEE"
  | "DELIVERY_EARNING" | "RIDE_EARNING" | "MERCHANT_EARNING"
  | "CASH_COLLECTED" | "PAYOUT_DEBIT" | "REFUND_DEBIT" | "ADJUSTMENT";
export type LedgerDirection = "CREDIT" | "DEBIT";
export type PayoutStatus = "PENDING" | "PROCESSING" | "PAID" | "FAILED" | "CANCELLED";
export type RefundStatus = "PENDING" | "PROCESSING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
export type CashCollectionStatus = "PENDING" | "COLLECTED" | "FAILED" | "DISPUTED";

export interface Payment {
  id: string;
  transactionType: PaymentTransactionType;
  /** Required for ORDER; empty for RIDE. */
  orderId: string;
  /** Required for RIDE; empty for ORDER. */
  tripId: string;
  /** Optional courier relationship for an order payment. */
  deliveryId: string;
  payerId: string;
  method: PaymentMethod;
  status: PaymentStatus;
  amountMinor: number;
  currency: "PHP";
  refundedAmountMinor: number;
  /** Amount reserved by pending/approved admin refund cases, in PHP centavos. */
  refundReservedAmountMinor?: number;
  createdAt: number;
  updatedAt: number;
  completedAt: number | null;
}

export interface PaymentAllocation {
  id: string;
  paymentId: string;
  transactionType: PaymentTransactionType;
  orderId: string;
  tripId: string;
  deliveryId: string;
  beneficiaryId: string;
  beneficiaryType: PaymentBeneficiaryType;
  amountMinor: number;
  currency: "PHP";
  status: AllocationStatus;
  createdAt: number;
  updatedAt: number;
}

export interface PaymentSettlement {
  id: string;
  paymentId: string;
  allocationId: string;
  beneficiaryId: string;
  beneficiaryType: PaymentBeneficiaryType;
  amountMinor: number;
  currency: "PHP";
  status: SettlementStatus;
  settledAt: number | null;
  createdAt: number;
  updatedAt: number;
}

export interface WalletAccount {
  id: string;
  ownerId: string;
  ownerType: PaymentBeneficiaryType;
  currency: "PHP";
  availableBalanceMinor: number;
  pendingBalanceMinor: number;
  cashOwedMinor: number;
  updatedAt: number;
}

export interface LedgerEntry {
  id: string;
  walletId: string;
  ownerId: string;
  paymentId: string;
  allocationId: string;
  settlementId: string;
  payoutId: string;
  refundId: string;
  cashCollectionId: string;
  entryType: LedgerEntryType;
  direction: LedgerDirection;
  /** Always positive; direction determines the accounting effect. */
  amountMinor: number;
  currency: "PHP";
  description: string;
  createdAt: number;
}

export interface Payout {
  id: string;
  walletId: string;
  beneficiaryId: string;
  beneficiaryType: PaymentBeneficiaryType;
  amountMinor: number;
  currency: "PHP";
  status: PayoutStatus;
  settlementIds: string[];
  requestedAt: number;
  processedAt: number | null;
  completedAt: number | null;
  failureReason: string;
}

export interface Refund {
  id: string;
  paymentId: string;
  orderId: string;
  tripId: string;
  requestedBy: string;
  amountMinor: number;
  currency: "PHP";
  reason: string;
  status: RefundStatus;
  createdAt: number;
  updatedAt: number;
  completedAt: number | null;
}

export interface CashCollection {
  id: string;
  paymentId: string;
  orderId: string;
  tripId: string;
  deliveryId: string;
  collectorId: string;
  payerId: string;
  amountMinor: number;
  currency: "PHP";
  status: CashCollectionStatus;
  collectedAt: number | null;
  createdAt: number;
  notes: string;
}

/** Runtime guard for the payment-to-transaction relationship. */
export function assertPaymentRelationship(payment: Payment): void {
  const isOrder = payment.transactionType === "ORDER";
  const valid = isOrder
    ? payment.orderId.trim().length > 0 && payment.tripId.trim().length === 0
    : payment.tripId.trim().length > 0 && payment.orderId.trim().length === 0 && payment.deliveryId.trim().length === 0;
  if (!valid) {
    throw new Error("Payment must reference exactly one transaction: an order or a trip. Delivery is an optional order relationship.");
  }
}

/** Validate non-negative integer minor-unit amounts before persistence/accounting. */
export function assertMinorAmount(amountMinor: number, field = "amountMinor"): void {
  if (!Number.isSafeInteger(amountMinor) || amountMinor < 0) {
    throw new Error(`${field} must be a non-negative safe integer in PHP centavos.`);
  }
}
