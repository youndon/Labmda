import { assertMinorAmount, assertPaymentRelationship } from "../models/payment.js";
import type { Payment, PaymentAllocation } from "../models/payment.js";
import type { FinancialOperation, LedgerPosting } from "./ledger.js";

/**
 * Converts a server-verified, succeeded payment and its persisted allocations
 * into one balanced journal operation. Callers must load these records from
 * Realtime Database in trusted Cloud Functions; never pass client-calculated
 * amounts directly into this adapter.
 */
export function createPaymentReceiptOperation(
  payment: Payment,
  allocations: PaymentAllocation[],
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
    if (!["PLATFORM", "MERCHANT", "DELIVERY", "DRIVER"].includes(allocation.beneficiaryType)) throw new Error("Payment allocation beneficiary type is invalid.");
    if (allocation.status !== "PENDING" && allocation.status !== "READY") {
      throw new Error("Only pending or ready allocations can be included in the initial payment receipt.");
    }
    assertMinorAmount(allocation.amountMinor, "allocation.amountMinor");
    if (allocation.amountMinor === 0) throw new Error("Allocation amount must be greater than zero.");
    if (!allocation.beneficiaryId) throw new Error("Allocation beneficiary ID is required.");

    allocatedMinor += allocation.amountMinor;
    if (!Number.isSafeInteger(allocatedMinor)) throw new Error("Payment allocation total exceeds the safe integer range.");

    const partyType: LedgerPosting["partyType"] = allocation.beneficiaryType === "DELIVERY"
      ? "COURIER"
      : allocation.beneficiaryType;
    const bucket = allocation.status === "READY" ? "AVAILABLE" : "PENDING";
    return {
      accountId: `wallet_${allocation.beneficiaryType.toLowerCase()}_${allocation.beneficiaryId}`,
      bucket,
      deltaMinor: allocation.amountMinor,
      partyType,
      partyId: allocation.beneficiaryId,
    };
  });

  if (allocatedMinor !== payment.amountMinor) {
    throw new Error("Payment allocations must exactly equal the succeeded payment amount.");
  }

  const isCash = payment.method === "CASH";
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
