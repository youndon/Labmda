import { randomUUID } from "node:crypto";
import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import type { CashCollection, Payment, PaymentAllocation } from "../models/payment.js";
import { postPaymentReceiptFromDatabase } from "./payment-repository.js";
import { createFinancialTransfer, postFinancialOperation, reconcileFinancialLedger } from "./ledger.js";

const db = () => getDatabase();
const idPattern = /^[A-Za-z0-9_-]{1,128}$/;

function requireUid(uid?: string): string {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to continue.");
  return uid;
}
function requireAdmin(claims: Record<string, unknown> | undefined): void {
  if (claims?.admin !== true) throw new HttpsError("permission-denied", "Administrator access is required.");
}
function requireId(value: unknown, label: string): string {
  if (typeof value !== "string" || !idPattern.test(value)) throw new HttpsError("invalid-argument", `${label} is invalid.`);
  return value;
}
function requireMinor(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) <= 0) throw new HttpsError("invalid-argument", "Amount must be a positive integer number of PHP centavos.");
  return value as number;
}
async function loadPayment(paymentId: string): Promise<Payment> {
  const snapshot = await db().ref(`payments/${paymentId}`).get();
  if (!snapshot.exists()) throw new HttpsError("not-found", "Payment was not found.");
  const payment = snapshot.val() as Payment;
  if (payment.id !== paymentId || payment.currency !== "PHP") throw new HttpsError("failed-precondition", "Payment record is invalid.");
  if (payment.method !== "CASH") throw new HttpsError("failed-precondition", "This payment is not a cash payment.");
  return payment;
}
async function assignedCollector(payment: Payment): Promise<string> {
  if (payment.transactionType === "ORDER") {
    if (!payment.deliveryId) throw new HttpsError("failed-precondition", "Assign a courier before cash collection.");
    return payment.deliveryId;
  }
  const trip = await db().ref(`trips/${payment.tripId}`).get();
  const driverId = trip.child("driverId").val();
  if (typeof driverId !== "string" || !driverId) throw new HttpsError("failed-precondition", "Assign a driver before cash collection.");
  return driverId;
}

/**
 * Collector attests receipt of the exact server-recorded amount. The client
 * never supplies beneficiary splits or changes payment totals.
 */
export const confirmCashCollection = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const paymentId = requireId(call.data?.paymentId, "Payment ID");
  const payment = await loadPayment(paymentId);
  const collectorId = await assignedCollector(payment);
  if (collectorId !== uid) throw new HttpsError("permission-denied", "Only the assigned driver or courier can confirm collection.");
  if (!Number.isSafeInteger(payment.amountMinor) || payment.amountMinor <= 0) throw new HttpsError("failed-precondition", "Payment amount is invalid.");

  const collectionRef = db().ref(`cashCollections/${paymentId}`);
  const now = Date.now();
  const existing = await collectionRef.get();
  if (existing.exists()) {
    const prior = existing.val() as CashCollection;
    if (prior.paymentId !== paymentId || prior.collectorId !== uid || prior.amountMinor !== payment.amountMinor) {
      throw new HttpsError("already-exists", "A conflicting cash collection record already exists.");
    }
    if (prior.status !== "COLLECTED" && prior.status !== "PENDING") {
      throw new HttpsError("failed-precondition", "Cash collection is not in a confirmable state.");
    }
  }

  const collection: CashCollection = {
    id: `cash_${paymentId}`, paymentId, orderId: payment.orderId, tripId: payment.tripId,
    deliveryId: payment.deliveryId, collectorId: uid, payerId: payment.payerId,
    amountMinor: payment.amountMinor, currency: "PHP", status: "COLLECTED",
    collectedAt: now, createdAt: existing.exists() ? (existing.val() as CashCollection).createdAt : now,
    notes: typeof call.data?.notes === "string" ? call.data.notes.slice(0, 500) : "",
  };
  await collectionRef.set(collection);

  const paymentRef = db().ref(`payments/${paymentId}`);
  const result = await paymentRef.transaction((current: Payment | null) => {
    if (!current) return;
    if (current.id !== paymentId || current.method !== "CASH" || current.amountMinor !== payment.amountMinor) return;
    if (current.status === "SUCCEEDED") return current;
    if (current.status !== "PENDING") return;
    return { ...current, status: "SUCCEEDED", updatedAt: now, completedAt: now };
  }, undefined, false);
  const updated = result.snapshot.val() as Payment | null;
  if (!updated || updated.status !== "SUCCEEDED") {
    throw new HttpsError("failed-precondition", "Payment could not be confirmed. The cash evidence was retained for safe retry.");
  }
  const posted = await postPaymentReceiptFromDatabase(db(), paymentId);
  return { paymentId, status: "COLLECTED", ...posted };
});

/** Merchant confirms physical receipt of its cash-funded order earnings. */
export const confirmMerchantCashSettlement = onCall({ enforceAppCheck: true }, async (call) => {
  const merchantId = requireUid(call.auth?.uid);
  const paymentId = requireId(call.data?.paymentId, "Payment ID");
  const payment = await loadPayment(paymentId);
  if (payment.status !== "SUCCEEDED") throw new HttpsError("failed-precondition", "Cash must be collected before merchant settlement.");
  const collectionSnapshot = await db().ref(`cashCollections/${paymentId}`).get();
  if (!collectionSnapshot.exists()) throw new HttpsError("failed-precondition", "Cash collection evidence is missing.");
  const collection = collectionSnapshot.val() as CashCollection;
  const allocationsSnapshot = await db().ref(`paymentAllocations/${paymentId}`).get();
  const raw = allocationsSnapshot.val() as Record<string, PaymentAllocation> | null;
  const allocation = Object.values(raw ?? {}).find((item) => item.beneficiaryType === "MERCHANT" && item.beneficiaryId === merchantId);
  if (!allocation) throw new HttpsError("permission-denied", "This payment has no earnings allocation for your merchant account.");
  if (allocation.transactionType !== payment.transactionType || allocation.orderId !== payment.orderId || allocation.tripId !== payment.tripId) {
    throw new HttpsError("failed-precondition", "Merchant allocation does not match the payment.");
  }
  const handoverId = `merchant_${paymentId}_${merchantId}`;
  const existing = await db().ref(`cashHandovers/${handoverId}`).get();
  if (existing.exists() && (existing.child("status").val() === "CONFIRMED")) return { handoverId, duplicate: true };

  const operation = createFinancialTransfer({
    operationId: `cash_merchant_settlement_${handoverId}`, eventType: "MERCHANT_EARNING",
    referenceType: "PAYMENT", referenceId: paymentId, idempotencyKey: `cash_merchant_settlement_${handoverId}`,
    createdAt: Date.now(), description: "Merchant confirmed receipt of cash order earnings",
    amountMinor: allocation.amountMinor,
    source: { accountId: `cash_owed_${collection.collectorId}_merchant_${merchantId}`, bucket: "CASH_OWED", deltaMinor: 0, partyType: "CASH", partyId: collection.collectorId },
    destination: { accountId: `wallet_merchant_${merchantId}`, bucket: "AVAILABLE", deltaMinor: 0, partyType: "MERCHANT", partyId: merchantId },
  });
  const posted = await postFinancialOperation(db(), operation);
  await db().ref(`cashHandovers/${handoverId}`).set({
    id: handoverId, paymentId, merchantId, collectorId: collection.collectorId,
    amountMinor: allocation.amountMinor, currency: "PHP", status: "CONFIRMED",
    confirmedAt: Date.now(), ledgerOperationId: posted.operationId,
  });
  return { handoverId, ...posted };
});

/** Admin confirms cash physically remitted by a driver/courier to the platform. */
export const confirmCashRemittance = onCall({ enforceAppCheck: true }, async (call) => {
  requireUid(call.auth?.uid);
  requireAdmin(call.auth?.token as Record<string, unknown> | undefined);
  const remittanceId = requireId(call.data?.remittanceId, "Remittance ID");
  const collectorId = requireId(call.data?.collectorId, "Collector ID");
  const amountMinor = requireMinor(call.data?.amountMinor);
  const existing = await db().ref(`cashRemittances/${remittanceId}`).get();
  if (existing.exists()) {
    const prior = existing.val() as { collectorId: string; amountMinor: number };
    if (prior.collectorId !== collectorId || prior.amountMinor !== amountMinor) throw new HttpsError("already-exists", "Remittance ID was reused with different details.");
    return { remittanceId, duplicate: true };
  }
  const operation = createFinancialTransfer({
    operationId: `cash_platform_remittance_${remittanceId}`, eventType: "CASH_COLLECTION",
    referenceType: "MANUAL", referenceId: remittanceId, idempotencyKey: `cash_platform_remittance_${remittanceId}`,
    createdAt: Date.now(), description: "Admin-confirmed driver/courier cash remittance to platform",
    amountMinor,
    source: { accountId: `cash_owed_${collectorId}`, bucket: "CASH_OWED", deltaMinor: 0, partyType: "CASH", partyId: collectorId },
    destination: { accountId: "platform_revenue", bucket: "AVAILABLE", deltaMinor: 0, partyType: "PLATFORM", partyId: "platform" },
  });
  const posted = await postFinancialOperation(db(), operation);
  await db().ref(`cashRemittances/${remittanceId}`).set({
    id: remittanceId, collectorId, amountMinor, currency: "PHP", status: "CONFIRMED",
    confirmedBy: call.auth!.uid, confirmedAt: Date.now(), ledgerOperationId: posted.operationId,
  });
  return { remittanceId, ...posted };
});

/** Collector-only view of outstanding platform and merchant cash obligations. */
export const getMyCashOutstanding = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const snapshot = await db().ref("finance/accounts").get();
  const accounts = (snapshot.val() ?? {}) as Record<string, { cashOwedMinor?: number }>;
  const prefix = `cash_owed_${uid}`;
  const breakdown = Object.entries(accounts)
    .filter(([accountId]) => accountId === prefix || accountId.startsWith(`${prefix}_merchant_`))
    .map(([accountId, value]) => ({ accountId, amountMinor: value.cashOwedMinor ?? 0 }));
  return { collectorId: uid, currency: "PHP", totalOutstandingMinor: breakdown.reduce((sum, item) => sum + item.amountMinor, 0), breakdown };
});

/** Admin-only ledger reconciliation plus a durable audit snapshot. */
export const reconcileCashPayments = onCall({ enforceAppCheck: true }, async (call) => {
  requireUid(call.auth?.uid);
  requireAdmin(call.auth?.token as Record<string, unknown> | undefined);
  const result = await reconcileFinancialLedger(db());
  const reconciliationId = randomUUID();
  const snapshot = {
    id: reconciliationId, type: "CASH_LEDGER", currency: "PHP",
    createdAt: Date.now(), createdBy: call.auth!.uid, ...result,
  };
  await db().ref(`cashReconciliations/${reconciliationId}`).set(snapshot);
  return snapshot;
});
