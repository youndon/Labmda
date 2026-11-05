import type { Database } from "firebase-admin/database";
import type { CashCollection, Payment, PaymentAllocation } from "../models/payment.js";
import { createPaymentReceiptOperation } from "./payment-ledger.js";
import { postFinancialOperation } from "./ledger.js";
import { PermanentPaymentLedgerError } from "./payment-ledger-errors.js";

function assertFirebaseId(value: string): void {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(value)) {
    throw new PermanentPaymentLedgerError("Payment ID must be a valid Firebase-safe identifier.");
  }
}

/**
 * Loads the authoritative payment, its persisted allocations, and (for cash)
 * the confirmed collection record, then posts one idempotent ledger receipt.
 *
 * This is a backend-only service. Call it only after a trusted payment
 * verification process has written the payment's SUCCEEDED state, or after a
 * trusted cash-collection process has written the matching COLLECTED record.
 * Never expose it as a callable accepting client-provided payment data.
 *
 * Realtime Database contract:
 * - payments/{paymentId}: Payment
 * - paymentAllocations/{paymentId}/{allocationId}: PaymentAllocation
 * - cashCollections/{paymentId}: CashCollection (cash only)
 */
export async function postPaymentReceiptFromDatabase(
  database: Database,
  paymentId: string,
): Promise<{ operationId: string; duplicate: boolean }> {
  assertFirebaseId(paymentId);

  const paymentSnapshot = await database.ref(`payments/${paymentId}`).get();
  if (!paymentSnapshot.exists()) throw new PermanentPaymentLedgerError("Payment record was not found.");
  const payment = paymentSnapshot.val() as Payment;
  if (!payment || payment.id !== paymentId) {
    throw new PermanentPaymentLedgerError("Payment record identity does not match its database path.");
  }

  const allocationsSnapshot = await database.ref(`paymentAllocations/${paymentId}`).get();
  if (!allocationsSnapshot.exists()) throw new PermanentPaymentLedgerError("Payment allocations were not found.");
  const rawAllocations = allocationsSnapshot.val() as Record<string, PaymentAllocation>;
  if (!rawAllocations || typeof rawAllocations !== "object" || Array.isArray(rawAllocations)) {
    throw new PermanentPaymentLedgerError("Payment allocations must be stored as an ID-keyed object.");
  }
  const allocations = Object.entries(rawAllocations).map(([allocationId, allocation]) => {
    if (!allocation || allocation.id !== allocationId) {
      throw new PermanentPaymentLedgerError("Payment allocation identity does not match its database key.");
    }
    return allocation;
  });

  let cashCollection: CashCollection | undefined;
  if (payment.method === "CASH") {
    const cashSnapshot = await database.ref(`cashCollections/${paymentId}`).get();
    if (!cashSnapshot.exists()) throw new PermanentPaymentLedgerError("Confirmed cash collection record was not found.");
    cashCollection = cashSnapshot.val() as CashCollection;
    if (!cashCollection || cashCollection.paymentId !== paymentId) {
      throw new PermanentPaymentLedgerError("Cash collection identity does not match its payment.");
    }
  }

  let operation;
  try {
    operation = createPaymentReceiptOperation(payment, allocations, { cashCollection });
  } catch (error) {
    throw new PermanentPaymentLedgerError(error instanceof Error ? error.message : "Payment receipt validation failed.", { cause: error });
  }
  return postFinancialOperation(operation);
}
