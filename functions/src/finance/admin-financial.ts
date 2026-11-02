import { randomUUID } from "node:crypto";
import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { reconcileFinancialLedger, createFinancialTransfer, postFinancialOperation, type BalanceBucket, type LedgerPosting } from "./ledger.js";

const db = () => getDatabase();
const idPattern = /^[A-Za-z0-9_-]{1,128}$/;
type AdminCall = { auth?: { uid?: string; token?: Record<string, unknown> } | null };
type PaymentRecord = {
  id: string; payerId: string; status: string; method: string; transactionType: string;
  orderId?: string; tripId?: string; amountMinor: number; refundedAmountMinor?: number;
  currency: string; createdAt: number; updatedAt?: number; completedAt?: number | null;
};
type RefundCase = {
  id: string; paymentId: string; payerId: string; amountMinor: number; currency: "PHP";
  reason: string; status: "REQUESTED" | "APPROVED" | "REJECTED" | "COMPLETED";
  requestedBy: string; createdAt: number; updatedAt: number; reviewedBy?: string;
  reviewedAt?: number; reviewNote?: string; completedBy?: string; completedAt?: number;
  providerReference?: string; method?: string;
};

function admin(call: AdminCall): string {
  if (!call.auth?.uid) throw new HttpsError("unauthenticated", "Sign in to continue.");
  if (call.auth.token?.admin !== true) throw new HttpsError("permission-denied", "Administrator access is required.");
  return call.auth.uid;
}
function safeId(value: unknown, field: string): string {
  if (typeof value !== "string" || !idPattern.test(value)) throw new HttpsError("invalid-argument", `${field} is invalid.`);
  return value;
}
function positiveMinor(value: unknown, field: string): number {
  if (!Number.isSafeInteger(value) || (value as number) <= 0) throw new HttpsError("invalid-argument", `${field} must be a positive PHP-centavo integer.`);
  return value as number;
}
function text(value: unknown, field: string, max = 500): string {
  const result = typeof value === "string" ? value.trim() : "";
  if (!result || result.length > max) throw new HttpsError("invalid-argument", `${field} is required (maximum ${max} characters).`);
  return result;
}

export async function recordAdminFinancialAudit(args: {
  uid: string; action: string; targetType: string; targetId: string;
  before?: unknown; after?: unknown; note?: string;
}): Promise<void> {
  const id = randomUUID();
  await db().ref(`adminAuditLogs/${id}`).set({
    id, actorUid: args.uid, action: args.action, targetType: args.targetType,
    targetId: args.targetId, before: args.before ?? null, after: args.after ?? null,
    note: (args.note ?? "").slice(0, 500), createdAt: Date.now(),
  });
}

/** Recent payment monitor. Returns a bounded newest-first sample without provider secrets. */
export const getAdminPaymentMonitor = onCall({ enforceAppCheck: true }, async (call) => {
  admin(call);
  const requestedLimit = call.data?.limit;
  const limit = Number.isSafeInteger(requestedLimit) ? Math.min(200, Math.max(1, Number(requestedLimit))) : 100;
  const status = typeof call.data?.status === "string" ? call.data.status : "";
  const snapshot = await db().ref("payments").orderByChild("createdAt").limitToLast(limit).get();
  const payments = Object.values((snapshot.val() ?? {}) as Record<string, PaymentRecord>)
    .filter((item) => !status || item.status === status)
    .sort((a, b) => b.createdAt - a.createdAt)
    .map((item) => ({
      paymentId: item.id, payerId: item.payerId, status: item.status, method: item.method,
      transactionType: item.transactionType, orderId: item.orderId ?? "", tripId: item.tripId ?? "",
      amountMinor: item.amountMinor, refundedAmountMinor: item.refundedAmountMinor ?? 0,
      refundableMinor: Math.max(0, item.amountMinor - (item.refundedAmountMinor ?? 0)),
      currency: item.currency, createdAt: item.createdAt, updatedAt: item.updatedAt ?? item.createdAt,
    }));
  return { currency: "PHP", limit, count: payments.length, payments };
});

/** Itemized balances for platform, merchants, drivers and couriers. */
export const getAdminFinancialBalances = onCall({ enforceAppCheck: true }, async (call) => {
  admin(call);
  const snapshot = await db().ref("finance/accounts").get();
  const accounts = Object.entries((snapshot.val() ?? {}) as Record<string, Record<string, unknown>>)
    .map(([accountId, raw]) => {
      const kind = accountId.startsWith("wallet_merchant_") ? "MERCHANT"
        : accountId.startsWith("wallet_driver_") ? "DRIVER"
        : accountId.startsWith("wallet_delivery_") ? "COURIER"
        : accountId === "platform_revenue" || accountId.startsWith("wallet_platform_") ? "PLATFORM"
        : accountId.startsWith("cash_owed_") || accountId.startsWith("cash_retained_") ? "CASH" : "OTHER";
      const ownerId = kind === "MERCHANT" ? accountId.slice("wallet_merchant_".length)
        : kind === "DRIVER" ? accountId.slice("wallet_driver_".length)
        : kind === "COURIER" ? accountId.slice("wallet_delivery_".length) : "";
      const availableMinor = Number.isSafeInteger(raw.availableMinor) ? Number(raw.availableMinor) : 0;
      const pendingMinor = Number.isSafeInteger(raw.pendingMinor) ? Number(raw.pendingMinor) : 0;
      const cashOwedMinor = Number.isSafeInteger(raw.cashOwedMinor) ? Number(raw.cashOwedMinor) : 0;
      const clearingMinor = Number.isSafeInteger(raw.clearingMinor) ? Number(raw.clearingMinor) : 0;
      return { accountId, kind, ownerId, availableMinor, pendingMinor, cashOwedMinor, clearingMinor,
        totalMinor: availableMinor + pendingMinor + cashOwedMinor + clearingMinor,
        updatedAt: Number(raw.updatedAt ?? 0) };
    })
    .sort((a, b) => a.kind.localeCompare(b.kind) || a.accountId.localeCompare(b.accountId));
  const totals = accounts.reduce((sum, item) => ({
    availableMinor: sum.availableMinor + item.availableMinor,
    pendingMinor: sum.pendingMinor + item.pendingMinor,
    cashOwedMinor: sum.cashOwedMinor + item.cashOwedMinor,
    clearingMinor: sum.clearingMinor + item.clearingMinor,
  }), { availableMinor: 0, pendingMinor: 0, cashOwedMinor: 0, clearingMinor: 0 });
  return { currency: "PHP", generatedAt: Date.now(), totals, accounts };
});

/** Rebuilds ledger projections and saves a durable reconciliation snapshot. */
export const runAdminFinancialReconciliation = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = admin(call);
  const result = await reconcileFinancialLedger(db());
  const report = { id: randomUUID(), type: "FINANCIAL_LEDGER", currency: "PHP",
    createdAt: Date.now(), createdBy: uid, ...result };
  await db().ref(`financeReconciliations/${report.id}`).set(report);
  await recordAdminFinancialAudit({ uid, action: "FINANCIAL_RECONCILIATION_RUN",
    targetType: "RECONCILIATION", targetId: report.id, after: report });
  return report;
});

/** Balanced, immutable manual journal transfer for a documented discrepancy. */
export const resolveAdminFinancialDiscrepancy = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = admin(call);
  const data = call.data ?? {};
  const amountMinor = positiveMinor(data.amountMinor, "Amount");
  const referenceId = safeId(data.referenceId, "Reference ID");
  const note = text(data.note, "Resolution note");
  const source = data.source as Record<string, unknown>;
  const destination = data.destination as Record<string, unknown>;
  const validBuckets: BalanceBucket[] = ["AVAILABLE", "PENDING", "CASH_OWED", "CLEARING"];
  const validParties: LedgerPosting["partyType"][] = ["CUSTOMER", "PLATFORM", "MERCHANT", "DRIVER", "COURIER", "PAYMENT_PROVIDER", "CASH"];
  function posting(value: Record<string, unknown>, field: string): LedgerPosting {
    const accountId = safeId(value.accountId, `${field} account`);
    const bucket = value.bucket;
    const partyType = value.partyType;
    const partyId = safeId(value.partyId, `${field} party`);
    if (!validBuckets.includes(bucket as BalanceBucket) || !validParties.includes(partyType as LedgerPosting["partyType"])) {
      throw new HttpsError("invalid-argument", `${field} bucket or party type is invalid.`);
    }
    return { accountId, bucket: bucket as BalanceBucket, partyType: partyType as LedgerPosting["partyType"], partyId, deltaMinor: 0 };
  }
  const sourcePosting = posting(source, "Source");
  const destinationPosting = posting(destination, "Destination");
  if (sourcePosting.accountId === destinationPosting.accountId && sourcePosting.bucket === destinationPosting.bucket) {
    throw new HttpsError("invalid-argument", "Source and destination must be different ledger buckets.");
  }
  const operation = createFinancialTransfer({
    operationId: `admin_adjustment_${randomUUID()}`, eventType: "ADJUSTMENT",
    referenceType: "MANUAL", referenceId, idempotencyKey: `admin_adjustment_${randomUUID()}`,
    createdAt: Date.now(), description: note, amountMinor, source: sourcePosting, destination: destinationPosting,
  });
  const posted = await postFinancialOperation(db(), operation);
  await recordAdminFinancialAudit({ uid, action: "FINANCIAL_DISCREPANCY_RESOLVED",
    targetType: "LEDGER_OPERATION", targetId: posted.operationId, after: operation, note });
  return { ...posted, amountMinor, currency: "PHP" };
});

/** Opens a refund case against an existing succeeded payment; no provider call is made. */
export const createAdminRefundCase = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = admin(call);
  const paymentId = safeId(call.data?.paymentId, "Payment ID");
  const amountMinor = positiveMinor(call.data?.amountMinor, "Refund amount");
  const reason = text(call.data?.reason, "Refund reason");
  const paymentRef = db().ref(`payments/${paymentId}`);
  const caseId = randomUUID();
  const now = Date.now();
  const result = await paymentRef.transaction((raw: PaymentRecord | null) => {
    if (!raw || raw.id !== paymentId || raw.status !== "SUCCEEDED" && raw.status !== "PARTIALLY_REFUNDED") return;
    const refunded = raw.refundedAmountMinor ?? 0;
    if (!Number.isSafeInteger(refunded) || refunded + amountMinor > raw.amountMinor) return;
    return raw;
  }, undefined, false);
  if (!result.committed) throw new HttpsError("failed-precondition", "Payment is not refundable or the requested amount exceeds its remaining balance.");
  const payment = result.snapshot.val() as PaymentRecord;
  const refund: RefundCase = { id: caseId, paymentId, payerId: payment.payerId, amountMinor,
    currency: "PHP", reason, status: "REQUESTED", requestedBy: uid, createdAt: now, updatedAt: now };
  await db().ref(`refundCases/${caseId}`).set(refund);
  await recordAdminFinancialAudit({ uid, action: "REFUND_CASE_CREATED", targetType: "REFUND", targetId: caseId, after: refund });
  return { refundId: caseId, status: refund.status, amountMinor, currency: "PHP" };
});

export const reviewAdminRefundCase = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = admin(call);
  const refundId = safeId(call.data?.refundId, "Refund ID");
  const decision = call.data?.decision;
  if (decision !== "APPROVED" && decision !== "REJECTED") throw new HttpsError("invalid-argument", "Decision must be APPROVED or REJECTED.");
  const note = text(call.data?.note, "Review note");
  const ref = db().ref(`refundCases/${refundId}`);
  let before: RefundCase | null = null;
  const result = await ref.transaction((raw: RefundCase | null) => {
    if (!raw || raw.status !== "REQUESTED") return;
    before = raw;
    return { ...raw, status: decision, reviewedBy: uid, reviewedAt: Date.now(), reviewNote: note, updatedAt: Date.now() };
  }, undefined, false);
  if (!result.committed) throw new HttpsError("failed-precondition", "Refund case is missing or has already been reviewed.");
  const after = result.snapshot.val() as RefundCase;
  await recordAdminFinancialAudit({ uid, action: `REFUND_${decision}`, targetType: "REFUND", targetId: refundId, before, after, note });
  return { refundId, status: after.status };
});

/** Record an externally executed refund after verification in the provider/bank dashboard. */
export const completeAdminRefundCase = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = admin(call);
  const refundId = safeId(call.data?.refundId, "Refund ID");
  const providerReference = text(call.data?.providerReference, "Provider/bank refund reference", 160);
  const ref = db().ref(`refundCases/${refundId}`);
  let before: RefundCase | null = null;
  const result = await ref.transaction((raw: RefundCase | null) => {
    if (!raw || raw.status !== "APPROVED") return;
    before = raw;
    return { ...raw, status: "COMPLETED", providerReference, completedBy: uid,
      completedAt: Date.now(), updatedAt: Date.now() };
  }, undefined, false);
  if (!result.committed) throw new HttpsError("failed-precondition", "Only an approved refund can be completed.");
  const refund = result.snapshot.val() as RefundCase;
  const paymentRef = db().ref(`payments/${refund.paymentId}`);
  let updatedPayment: PaymentRecord | null = null;
  const paymentResult = await paymentRef.transaction((raw: PaymentRecord | null) => {
    if (!raw || raw.status !== "SUCCEEDED" && raw.status !== "PARTIALLY_REFUNDED") return;
    const refunded = raw.refundedAmountMinor ?? 0;
    if (!Number.isSafeInteger(refunded) || refunded + refund.amountMinor > raw.amountMinor) return;
    const next = refunded + refund.amountMinor;
    const status = next === raw.amountMinor ? "REFUNDED" : "PARTIALLY_REFUNDED";
    updatedPayment = { ...raw, refundedAmountMinor: next, status, updatedAt: Date.now() };
    return updatedPayment;
  }, undefined, false);
  if (!paymentResult.committed) {
    await ref.update({ status: "APPROVED", completedBy: null, completedAt: null, providerReference: null });
    throw new HttpsError("failed-precondition", "Payment refund balance changed; refresh the case before retrying.");
  }
  await recordAdminFinancialAudit({ uid, action: "REFUND_COMPLETED", targetType: "REFUND", targetId: refundId,
    before, after: { refund, payment: paymentResult.snapshot.val() }, note: providerReference });
  return { refundId, status: "COMPLETED", paymentId: refund.paymentId, providerReference };
});

export const getAdminRefundCases = onCall({ enforceAppCheck: true }, async (call) => {
  admin(call);
  const snapshot = await db().ref("refundCases").orderByChild("createdAt").limitToLast(200).get();
  const cases = Object.values((snapshot.val() ?? {}) as Record<string, RefundCase>).sort((a, b) => b.createdAt - a.createdAt);
  return { currency: "PHP", cases };
});

export const getAdminAuditTrail = onCall({ enforceAppCheck: true }, async (call) => {
  admin(call);
  const snapshot = await db().ref("adminAuditLogs").orderByChild("createdAt").limitToLast(200).get();
  const logs = Object.values((snapshot.val() ?? {}) as Record<string, Record<string, unknown>>)
    .sort((a, b) => Number(b.createdAt ?? 0) - Number(a.createdAt ?? 0));
  return { logs };
});
