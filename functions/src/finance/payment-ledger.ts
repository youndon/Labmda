import { assertMinorAmount, assertPaymentRelationship } from "../models/payment.js";
import type { CashCollection, Payment, PaymentAllocation } from "../models/payment.js";
import { createFinancialTransfer, type FinancialOperation, type LedgerPosting } from "./ledger.js";

/** Reject beneficiary roles that do not belong to the payment transaction. */
function assertAllocationBeneficiary(payment: Payment, allocation: PaymentAllocation, cashCollection?: CashCollection): void {
  switch (allocation.beneficiaryType) {
    case "PLATFORM": return;
    case "MERCHANT":
      if (payment.transactionType !== "ORDER") throw new Error("Merchant allocations are valid only for order payments.");
      return;
    case "DELIVERY":
      if (payment.transactionType !== "ORDER" || !payment.deliveryId || allocation.beneficiaryId !== payment.deliveryId) {
        throw new Error("Courier allocation must match the assigned order delivery provider.");
      }
      if (payment.method === "CASH" && cashCollection?.collectorId !== allocation.beneficiaryId) {
        throw new Error("Cash courier earnings must belong to the assigned cash collector.");
      }
      return;
    case "DRIVER":
      if (payment.transactionType !== "RIDE") throw new Error("Driver allocations are valid only for ride payments.");
      if (payment.method === "CASH" && cashCollection?.collectorId !== allocation.beneficiaryId) {
        throw new Error("Cash driver earnings must belong to the assigned cash collector.");
      }
      return;
    default: throw new Error("Payment allocation beneficiary type is invalid.");
  }
}
/**
 * Converts a server-verified, succeeded payment and its persisted allocations
 * into one balanced journal operation. Callers must load these records from
 * Realtime Database in trusted Cloud Functions; never pass client-calculated
 * amounts directly into this adapter.
 */
export function createPaymentReceiptOperation(
  payment: Payment,
  allocations: PaymentAllocation[],
  options: { cashCollection?: CashCollection } = {},
): FinancialOperation {
  if (payment.transactionType !== "ORDER" && payment.transactionType !== "RIDE") throw new Error("Payment transaction type is invalid.");
  if (payment.method !== "GCASH" && payment.method !== "CASH") throw new Error("Payment method is unsupported.");
  assertPaymentRelationship(payment);
  if (payment.status !== "SUCCEEDED") {
    throw new Error("Only a succeeded payment can be posted to the financial ledger.");
  }
  if (payment.currency !== "PHP") throw new Error("Payment currency must be PHP.");
  assertMinorAmount(payment.amountMinor, "payment.amountMinor");
  if (payment.amountMinor === 0) throw new Error("Payment amount must be greater than zero.");
  const isCash = payment.method === "CASH";
  const cashCollection = options.cashCollection;
  if (isCash) {
    if (!cashCollection || cashCollection.status !== "COLLECTED") {
      throw new Error("Cash payments require a confirmed cash collection record.");
    }
    if (!cashCollection.id || cashCollection.paymentId !== payment.id || cashCollection.amountMinor !== payment.amountMinor
      || cashCollection.currency !== "PHP" || cashCollection.payerId !== payment.payerId
      || cashCollection.orderId !== payment.orderId || cashCollection.tripId !== payment.tripId
      || cashCollection.deliveryId !== payment.deliveryId || !cashCollection.collectorId
      || (payment.transactionType === "ORDER" && cashCollection.collectorId !== payment.deliveryId)
      || !Number.isSafeInteger(cashCollection.collectedAt) || !cashCollection.collectedAt) {
      throw new Error("Cash collection evidence does not match the succeeded payment.");
    }
  }
  if (!Array.isArray(allocations) || allocations.length === 0) {
    throw new Error("A succeeded payment requires at least one persisted allocation.");
  }

  const seenAllocationIds = new Set<string>();
  let allocatedMinor = 0;
  const allocationPostings: LedgerPosting[] = allocations.map((allocation) => {
    if (!allocation.id || allocation.paymentId !== payment.id) {
      throw new Error("Every allocation must reference this payment and have an ID.");
    }
    if (seenAllocationIds.has(allocation.id)) throw new Error("Duplicate payment allocation ID.");
    seenAllocationIds.add(allocation.id);
    if (allocation.currency !== "PHP") throw new Error("Allocation currency must be PHP.");
    if (allocation.transactionType !== payment.transactionType
      || allocation.orderId !== payment.orderId
      || allocation.tripId !== payment.tripId
      || allocation.deliveryId !== payment.deliveryId) {
      throw new Error("Payment allocation transaction references do not match the payment.");
    }
    assertAllocationBeneficiary(payment, allocation, cashCollection);
    if (allocation.status !== "PENDING" && allocation.status !== "READY") {
      throw new Error("Only pending or ready allocations can be included in the initial payment receipt.");
    }
    assertMinorAmount(allocation.amountMinor, "allocation.amountMinor");
    if (allocation.amountMinor === 0) throw new Error("Allocation amount must be greater than zero.");
    if (!allocation.beneficiaryId) throw new Error("Allocation beneficiary ID is required.");

    allocatedMinor += allocation.amountMinor;
    if (!Number.isSafeInteger(allocatedMinor)) throw new Error("Payment allocation total exceeds the safe integer range.");

    if (isCash && (allocation.beneficiaryType === "PLATFORM" || allocation.beneficiaryType === "MERCHANT")) {
      // The collector physically holds the merchant and platform portions until
      // each is handed over. Keep merchant and platform obligations separate.
      return {
        accountId: allocation.beneficiaryType === "PLATFORM"
          ? `cash_owed_${cashCollection!.collectorId}`
          : `cash_owed_${cashCollection!.collectorId}_merchant_${allocation.beneficiaryId}`,
        bucket: "CASH_OWED",
        deltaMinor: allocation.amountMinor,
        partyType: "CASH",
        partyId: cashCollection!.collectorId,
      };
    }
    const partyType: LedgerPosting["partyType"] = allocation.beneficiaryType === "DELIVERY"
      ? "COURIER"
      : allocation.beneficiaryType;
    if (isCash && (allocation.beneficiaryType === "DRIVER" || allocation.beneficiaryType === "DELIVERY")) {
      // The driver/courier already holds this earning in the collected notes;
      // it is recorded as retained cash, not as a second withdrawable wallet liability.
      return {
        accountId: `cash_retained_${cashCollection!.collectorId}`,
        bucket: "AVAILABLE",
        deltaMinor: allocation.amountMinor,
        partyType,
        partyId: allocation.beneficiaryId,
      };
    }
    const bucket = allocation.status === "READY" ? "AVAILABLE" : "PENDING";
    return {
      accountId: `wallet_${allocation.beneficiaryType.toLowerCase()}_${allocation.beneficiaryId}`,
      bucket,
      deltaMinor: allocation.amountMinor,
      partyType,
      partyId: allocation.beneficiaryId,
    };
  });

  if (allocatedMinor > payment.amountMinor) {
    throw new Error("Payment allocations cannot exceed the succeeded payment amount.");
  }
  const unallocatedMinor = payment.amountMinor - allocatedMinor;
  if (isCash && unallocatedMinor !== 0) {
    throw new Error("Cash payment allocations must exactly equal the amount collected.");
  }
  if (unallocatedMinor > 0) {
    allocationPostings.push({
      accountId: `unallocated_payment_${payment.id}`,
      bucket: "CLEARING",
      deltaMinor: unallocatedMinor,
      partyType: "PLATFORM",
      partyId: "platform",
    });
  }

  const source: LedgerPosting = {
    accountId: isCash ? "cash_collection_clearing" : "payment_provider_clearing",
    bucket: "CLEARING",
    deltaMinor: -payment.amountMinor,
    partyType: isCash ? "CASH" : "PAYMENT_PROVIDER",
    partyId: isCash ? "cash_collection" : "payment_provider",
  };
  const createdAt = payment.completedAt ?? payment.updatedAt;
  if (!Number.isSafeInteger(createdAt) || createdAt <= 0) {
    throw new Error("A succeeded payment must have a valid completion or update timestamp.");
  }

  return {
    operationId: `payment_${payment.id}_received`,
    eventType: "CUSTOMER_PAYMENT",
    referenceType: "PAYMENT",
    referenceId: payment.id,
    currency: "PHP",
    idempotencyKey: `payment_${payment.id}_succeeded`,
    createdAt,
    description: `Customer ${payment.method.toLowerCase()} payment allocated`,
    postings: [source, ...allocationPostings],
  };
}


/**
 * Moves a confirmed cash remittance from the collector's cash-owed balance
 * into platform revenue. The caller must verify the actual handover and the
 * collector's outstanding balance before posting.
 */
export function createCashRemittanceOperation(args: {
  cashCollectionId: string;
  collectorId: string;
  amountMinor: number;
  createdAt: number;
}): FinancialOperation {
  return createFinancialTransfer({
    operationId: `cash_remittance_${args.cashCollectionId}`,
    eventType: "CASH_COLLECTION",
    referenceType: "PAYMENT",
    referenceId: args.cashCollectionId,
    idempotencyKey: `cash_remittance_${args.cashCollectionId}`,
    createdAt: args.createdAt,
    description: "Confirmed cash remittance to platform",
    amountMinor: args.amountMinor,
    source: {
      accountId: `cash_owed_${args.collectorId}`,
      bucket: "CASH_OWED",
      deltaMinor: 0,
      partyType: "CASH",
      partyId: args.collectorId,
    },
    destination: {
      accountId: "platform_revenue",
      bucket: "AVAILABLE",
      deltaMinor: 0,
      partyType: "PLATFORM",
      partyId: "platform",
    },
  });
}
