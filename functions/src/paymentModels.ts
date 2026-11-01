/**
 * Provider-independent payment domain shared by callable functions and database records.
 * Monetary amounts are integer PHP centavos. These interfaces do not perform I/O.
 */

export type PaymentMethod = "GCASH" | "CASH";

export type PaymentStatus =
  | "CREATED"
  | "PENDING"
  | "PROCESSING"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED"
  | "EXPIRED"
  | "PARTIALLY_REFUNDED"
  | "REFUNDED";

export type PaymentEntityType = "ORDER" | "TRIP" | "DELIVERY";
export type PaymentAllocationType = "PLATFORM" | "MERCHANT" | "COURIER" | "DRIVER" | "OTHER";
export type SettlementStatus = "PENDING" | "PROCESSING" | "SETTLED" | "FAILED" | "REVERSED";
export type WalletStatus = "ACTIVE" | "SUSPENDED" | "CLOSED";
export type LedgerEntryType =
  | "PAYMENT_CREDIT"
  | "ALLOCATION_DEBIT"
  | "ALLOCATION_CREDIT"
  | "CASH_COLLECTION"
  | "CASH_REMITTANCE"
  | "REFUND_DEBIT"
  | "PAYOUT_DEBIT"
  | "PAYOUT_REVERSAL"
  | "ADJUSTMENT";
export type LedgerDirection = "DEBIT" | "CREDIT";
export type PayoutStatus = "REQUESTED" | "PENDING" | "PROCESSING" | "PAID" | "FAILED" | "CANCELLED";
export type RefundStatus = "REQUESTED" | "PENDING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
export type CashCollectionStatus =
  | "EXPECTED"
  | "COLLECTED"
  | "PARTIALLY_COLLECTED"
  | "REMITTED"
  | "DISPUTED"
  | "VOIDED";

export interface Payment {
  id: string;
  entityType: PaymentEntityType;
  entityId: string;
  orderId?: string | null;
  tripId?: string | null;
  deliveryId?: string | null;
  payerId: string;
  paymentMethod: PaymentMethod;
  status: PaymentStatus;
  amountCentavos: number;
  refundedCentavos: number;
  currency: "PHP";
  idempotencyKey: string;
  createdAtTimestamp: number;
  updatedAtTimestamp: number;
  paidAtTimestamp?: number | null;
  failureCode?: string | null;
}

export interface PaymentAllocation {
  id: string;
  paymentId: string;
  entityType: PaymentEntityType;
  entityId: string;
  beneficiaryId: string;
  allocationType: PaymentAllocationType;
  amountCentavos: number;
  currency: "PHP";
  status: SettlementStatus;
  createdAtTimestamp: number;
}

export interface PaymentSettlement {
  id: string;
  paymentId: string;
  allocationId: string;
  beneficiaryId: string;
  amountCentavos: number;
  currency: "PHP";
  status: SettlementStatus;
  externalReference?: string | null;
  settledAtTimestamp?: number | null;
  createdAtTimestamp: number;
  updatedAtTimestamp: number;
}

export interface WalletAccount {
  id: string;
  ownerId: string;
  status: WalletStatus;
  currency: "PHP";
  availableCentavos: number;
  pendingCentavos: number;
  cashOwedCentavos: number;
  lifetimeEarnedCentavos: number;
  lifetimePaidOutCentavos: number;
  createdAtTimestamp: number;
  updatedAtTimestamp: number;
}

export interface LedgerEntry {
  id: string;
  walletAccountId: string;
  ownerId: string;
  paymentId?: string | null;
  allocationId?: string | null;
  settlementId?: string | null;
  payoutId?: string | null;
  refundId?: string | null;
  cashCollectionId?: string | null;
  entryType: LedgerEntryType;
  direction: LedgerDirection;
  amountCentavos: number;
  balanceAfterCentavos?: number | null;
  currency: "PHP";
  description: string;
  createdAtTimestamp: number;
}

export interface Payout {
  id: string;
  walletAccountId: string;
  beneficiaryId: string;
  amountCentavos: number;
  currency: "PHP";
  status: PayoutStatus;
  destinationAccountId?: string | null;
  failureCode?: string | null;
  requestedAtTimestamp: number;
  processedAtTimestamp?: number | null;
  completedAtTimestamp?: number | null;
}

export interface Refund {
  id: string;
  paymentId: string;
  entityType: PaymentEntityType;
  entityId: string;
  requestedBy: string;
  amountCentavos: number;
  currency: "PHP";
  status: RefundStatus;
  reason: string;
  createdAtTimestamp: number;
  updatedAtTimestamp: number;
  completedAtTimestamp?: number | null;
}

export interface CashCollection {
  id: string;
  paymentId: string;
  entityType: PaymentEntityType;
  entityId: string;
  orderId?: string | null;
  tripId?: string | null;
  deliveryId?: string | null;
  collectedBy: string;
  collectorRole: string;
  amountCentavos: number;
  merchantOwedCentavos: number;
  platformOwedCentavos: number;
  collectorEarningsCentavos: number;
  remittedCentavos: number;
  outstandingRemittanceCentavos: number;
  currency: "PHP";
  status: CashCollectionStatus;
  collectedAtTimestamp?: number | null;
  remittedAtTimestamp?: number | null;
  createdAtTimestamp: number;
}
