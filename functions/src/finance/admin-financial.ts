import { createHash, randomUUID } from "node:crypto";
import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { reconcileFinancialLedger, createFinancialTransfer, postFinancialOperation, getFinancialOperation, listFinancialAccounts, type BalanceBucket, type LedgerPosting } from "./ledger.js";
import { buildRefundReversal } from "./refund-ledger.js";

const db = () => getDatabase();
const idPattern = /^[A-Za-z0-9_-]{1,128}$/;
type AdminCall = { auth?: { uid?: string; token?: Record<string, unknown> } | null };
type PaymentRecord = {
  id: string; payerId: string; status: string; method: string; transactionType: string;
  orderId?: string; tripId?: string; amountMinor: number; refundedAmountMinor?: number; refundReservedAmountMinor?: number;
  currency: string; createdAt: number; updatedAt?: number; completedAt?: number | null;
  provider?: string; checkoutUrl?: string; providerReference?: string; refundProcessingId?: string | null;
};
type RefundCase = {
  id: string; paymentId: string; payerId: string; amountMinor: number; currency: "PHP";
  reason: string; status: "REQUESTED" | "APPROVED" | "PROCESSING" | "REJECTED" | "COMPLETED";
  requestedBy: string; createdAt: number; updatedAt: number; reviewedBy?: string;
  reviewedAt?: number; reviewNote?: string; completedBy?: string; completedAt?: number;
  providerReference?: string; method?: string; ledgerOperationId?: string; processingAt?: number;
  orderId?: string; tripId?: string; requestedByRole?: string; origin?: string;
  verifiedBy?: string; verifiedAt?: number; verificationNote?: string; verifiedAmountMinor?: number;
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
      amountMinor: item.amountMinor, provider: item.provider ?? "", checkoutUrl: item.checkoutUrl ?? "", providerReference: item.providerReference ?? "", refundedAmountMinor: item.refundedAmountMinor ?? 0,
      refundReservedAmountMinor: item.refundReservedAmountMinor ?? 0,
      refundableMinor: Math.max(0, item.amountMinor - (item.refundedAmountMinor ?? 0) - (item.refundReservedAmountMinor ?? 0)),
      currency: item.currency, createdAt: item.createdAt, updatedAt: item.updatedAt ?? item.createdAt,
    }));
  return { currency: "PHP", limit, count: payments.length, payments };
});

/** Itemized balances for platform, merchants, drivers and couriers. */
export const getAdminFinancialBalances = onCall({ enforceAppCheck: true }, async (call) => {
  admin(call);
  const accountRecords = await listFinancialAccounts();
  const accounts = accountRecords.map((raw) => [raw.accountId, raw] as const)
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
  const result = await reconcileFinancialLedger();
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
  if (!data.source || typeof data.source !== "object" || !data.destination || typeof data.destination !== "object") {
    throw new HttpsError("invalid-argument", "Source and destination ledger postings are required.");
  }
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
  const requestRef = db().ref(`financialAdjustmentRequests/${referenceId}`);
  const requested = { referenceId, amountMinor, note, source: sourcePosting, destination: destinationPosting,
    createdAt: Date.now(), createdBy: uid };
  const requestResult = await requestRef.transaction((current) => current ?? requested, undefined, false);
  const savedRequest = requestResult.snapshot.val() as typeof requested;
  const samePosting = (saved: LedgerPosting, current: LedgerPosting) =>
    saved.accountId === current.accountId && saved.bucket === current.bucket
      && saved.partyType === current.partyType && saved.partyId === current.partyId;
  if (!savedRequest || savedRequest.amountMinor !== amountMinor || savedRequest.note !== note
    || !samePosting(savedRequest.source, sourcePosting)
    || !samePosting(savedRequest.destination, destinationPosting)) {
    throw new HttpsError("already-exists", "This resolution reference was already used with different adjustment details.");
  }
  const operation = createFinancialTransfer({
    operationId: `admin_adjustment_${referenceId}`, eventType: "ADJUSTMENT",
    referenceType: "MANUAL", referenceId, idempotencyKey: `admin_adjustment_${referenceId}`,
    createdAt: savedRequest.createdAt, description: savedRequest.note, amountMinor: savedRequest.amountMinor,
    source: savedRequest.source, destination: savedRequest.destination,
  });
  const posted = await postFinancialOperation(operation);
  if (!posted.duplicate) await recordAdminFinancialAudit({ uid, action: "FINANCIAL_DISCREPANCY_RESOLVED",
    targetType: "LEDGER_OPERATION", targetId: posted.operationId, after: operation, note });
  return { ...posted, amountMinor, currency: "PHP" };
});

/** Opens a refund case against an existing succeeded payment; no provider call is made. */
export const createAdminRefundCase = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = admin(call);
  const paymentId = safeId(call.data?.paymentId, "Payment ID");
  const amountMinor = positiveMinor(call.data?.amountMinor, "Refund amount");
  const reason = text(call.data?.reason, "Refund reason");
  const caseId = randomUUID();
  const now = Date.now();
  const refund: RefundCase = { id: caseId, paymentId, payerId: "", amountMinor,
    currency: "PHP", reason, status: "REQUESTED", requestedBy: uid, createdAt: now, updatedAt: now };
  const rootResult = await db().ref().transaction((root: Record<string, any> | null) => {
    if (root === null) return null;
    const payment = root.payments?.[paymentId] as PaymentRecord | undefined;
    if (!payment || payment.id !== paymentId || payment.currency !== "PHP"
      || (payment.status !== "SUCCEEDED" && payment.status !== "PARTIALLY_REFUNDED")
      || !Number.isSafeInteger(payment.amountMinor)) return;
    const refunded = payment.refundedAmountMinor ?? 0;
    const reserved = payment.refundReservedAmountMinor ?? 0;
    if (!Number.isSafeInteger(refunded) || !Number.isSafeInteger(reserved)
      || refunded + reserved + amountMinor > payment.amountMinor) return;
    const savedRefund = { ...refund, payerId: payment.payerId };
    return {
      ...root,
      payments: { ...root.payments, [paymentId]: { ...payment, refundReservedAmountMinor: reserved + amountMinor } },
      refundCases: { ...(root.refundCases ?? {}), [caseId]: savedRefund },
    };
  }, undefined, false);
  if (!rootResult.committed) throw new HttpsError("failed-precondition", "Payment is not refundable or the requested amount exceeds its unreserved balance.");
  const savedRefund = rootResult.snapshot.child(`refundCases/${caseId}`).val() as RefundCase;
  await recordAdminFinancialAudit({ uid, action: "REFUND_CASE_CREATED", targetType: "REFUND", targetId: caseId, after: savedRefund });
  return { refundId: caseId, status: savedRefund.status, amountMinor, currency: "PHP" };
});

export const reviewAdminRefundCase = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = admin(call);
  const refundId = safeId(call.data?.refundId, "Refund ID");
  const decision = call.data?.decision;
  if (decision !== "APPROVED" && decision !== "REJECTED") throw new HttpsError("invalid-argument", "Decision must be APPROVED or REJECTED.");
  const note = text(call.data?.note, "Review note");
  let before: RefundCase | null = null;
  const result = await db().ref().transaction((root: Record<string, any> | null) => {
    if (root === null) return null;
    const current = root.refundCases?.[refundId] as RefundCase | undefined;
    if (!current || current.status !== "REQUESTED") return;
    before = current;
    const nextCase = { ...current, status: decision, reviewedBy: uid,
      reviewedAt: Date.now(), reviewNote: note, updatedAt: Date.now() };
    if (decision === "REJECTED") {
      const payment = root.payments?.[current.paymentId] as PaymentRecord | undefined;
      if (!payment) return;
      const reserved = payment.refundReservedAmountMinor ?? 0;
      if (!Number.isSafeInteger(reserved) || reserved < current.amountMinor) return;
      return {
        ...root,
        payments: { ...root.payments, [current.paymentId]: { ...payment, refundReservedAmountMinor: reserved - current.amountMinor } },
        refundCases: { ...root.refundCases, [refundId]: nextCase },
      };
    }
    return { ...root, refundCases: { ...root.refundCases, [refundId]: nextCase } };
  }, undefined, false);
  if (!result.committed) throw new HttpsError("failed-precondition", "Refund case is missing, already reviewed, or its reservation is inconsistent.");
  const after = result.snapshot.child(`refundCases/${refundId}`).val() as RefundCase;
  await recordAdminFinancialAudit({ uid, action: `REFUND_${decision}`, targetType: "REFUND", targetId: refundId, before, after, note });
  return { refundId, status: after.status };
});

/**
 * Complete an approved refund after the actual transfer is confirmed in the
 * BDO Checkout/bank records. The immutable journal reverses the original payment
 * allocations proportionally; no provider API call is made here.
 */
export const completeAdminRefundCase = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = admin(call);
  const refundId = safeId(call.data?.refundId, "Refund ID");
  const providerReference = text(call.data?.providerReference, "Provider/bank refund reference", 160);
  const verificationNote = text(call.data?.verificationNote, "BDO refund verification note", 500);
  const verifiedAmountMinor = positiveMinor(call.data?.verifiedAmountMinor, "Verified BDO refund amount");
  const referenceKey = createHash("sha256").update(providerReference.trim().toUpperCase()).digest("hex");
  const now = Date.now();
  const claim = await db().ref().transaction((root: Record<string, any> | null) => {
    if (root === null) return null;
    const current = root.refundCases?.[refundId] as RefundCase | undefined;
    if (!current) return;
    if (current.status === "COMPLETED") {
      if (current.providerReference !== providerReference || current.verificationNote !== verificationNote
        || current.verifiedAmountMinor !== verifiedAmountMinor) return;
      return root;
    }
    if (current.status !== "APPROVED" && current.status !== "PROCESSING") return;
    if (current.status === "PROCESSING"
      && (current.providerReference !== providerReference || current.verificationNote !== verificationNote
        || current.verifiedAmountMinor !== verifiedAmountMinor)) return;
    if (current.amountMinor !== verifiedAmountMinor) return;
    const payment = root.payments?.[current.paymentId] as PaymentRecord | undefined;
    if (!payment || payment.id !== current.paymentId || payment.method !== "GCASH"
      || payment.provider !== "BDO_CHECKOUT" || payment.currency !== "PHP") return;
    if (payment.refundProcessingId && payment.refundProcessingId !== refundId) return;
    const referenceOwner = root.refundProviderReferenceIndex?.[referenceKey];
    if (referenceOwner && referenceOwner !== refundId) return;
    const nextCase = current.status === "PROCESSING"
      ? current
      : { ...current, status: "PROCESSING", completedBy: uid, providerReference,
          verifiedBy: uid, verifiedAt: now, verificationNote, verifiedAmountMinor, processingAt: now, updatedAt: now };
    return {
      ...root,
      payments: { ...root.payments, [current.paymentId]: { ...payment, refundProcessingId: refundId } },
      refundCases: { ...root.refundCases, [refundId]: nextCase },
      refundProviderReferenceIndex: { ...(root.refundProviderReferenceIndex ?? {}), [referenceKey]: refundId },
    };
  }, undefined, false);
  if (!claim.committed) {
    const current = claim.snapshot.child(`refundCases/${refundId}`).val() as RefundCase | null;
    if (current?.status === "COMPLETED"
      && current.providerReference === providerReference
      && current.verificationNote === verificationNote
      && current.verifiedAmountMinor === verifiedAmountMinor) {
      return { refundId, status: "COMPLETED", duplicate: true, paymentId: current.paymentId,
        providerReference, verifiedAmountMinor, ledgerOperationId: current.ledgerOperationId ?? "" };
    }
    if (current?.status !== "PROCESSING" || current.providerReference !== providerReference
      || current.verificationNote !== verificationNote || current.verifiedAmountMinor !== verifiedAmountMinor) {
      throw new HttpsError("failed-precondition", "Refund is not approved, another refund for this payment is processing, or the transfer reference is already assigned.");
    }
  }
  const claimedCase = claim.snapshot.child(`refundCases/${refundId}`).val() as RefundCase;
  if (claimedCase.status === "COMPLETED") {
    return { refundId, status: "COMPLETED", duplicate: true, paymentId: claimedCase.paymentId,
      providerReference, verifiedAmountMinor, ledgerOperationId: claimedCase.ledgerOperationId ?? "" };
  }
  const refund = claimedCase;
  const paymentSnapshot = await db().ref(`payments/${refund.paymentId}`).get();
  if (!paymentSnapshot.exists()) throw new HttpsError("not-found", "Refund payment was not found.");
  const payment = paymentSnapshot.val() as PaymentRecord;
  if (payment.currency !== "PHP" || payment.method !== "GCASH" || payment.provider !== "BDO_CHECKOUT"
    || !Number.isSafeInteger(payment.amountMinor)) {
    throw new HttpsError("failed-precondition", "Payment record is invalid.");
  }
  const original = await getFinancialOperation(`payment_${refund.paymentId}_received`);
  if (!original) throw new HttpsError("failed-precondition", "The original payment journal entry is missing; refund cannot be posted safely.");
  const postings = original.postings ?? [];
  if (!postings.length || !Number.isSafeInteger(original.createdAt)) {
    throw new HttpsError("failed-precondition", "The original payment journal entry is invalid.");
  }
  const reversal = buildRefundReversal({
    refundId, paymentId: refund.paymentId, paymentAmountMinor: payment.amountMinor,
    refundAmountMinor: refund.amountMinor, createdAt: refund.processingAt ?? refund.updatedAt, reason: refund.reason,
    originalPostings: postings,
  });
  const posted = await postFinancialOperation(reversal);
  const rootResult = await db().ref().transaction((root: Record<string, any> | null) => {
    if (root === null) return null;
    const currentCase = root.refundCases?.[refundId] as RefundCase | undefined;
    const currentPayment = root.payments?.[refund.paymentId] as PaymentRecord | undefined;
    if (!currentCase || currentCase.status !== "PROCESSING" || currentCase.providerReference !== providerReference
      || !currentPayment || currentPayment.refundProcessingId !== refundId) return;
    const refunded = currentPayment.refundedAmountMinor ?? 0;
    const reserved = currentPayment.refundReservedAmountMinor ?? 0;
    if (!Number.isSafeInteger(refunded) || !Number.isSafeInteger(reserved) || reserved < refund.amountMinor
      || refunded + refund.amountMinor > currentPayment.amountMinor
      || (currentPayment.status !== "SUCCEEDED" && currentPayment.status !== "PARTIALLY_REFUNDED")) return;
    const nextRefunded = refunded + refund.amountMinor;
    const nextPayment = { ...currentPayment, refundedAmountMinor: nextRefunded,
      refundReservedAmountMinor: reserved - refund.amountMinor,
      refundProcessingId: null,
      status: nextRefunded === currentPayment.amountMinor ? "REFUNDED" : "PARTIALLY_REFUNDED",
      updatedAt: Date.now() };
    const nextCase = { ...currentCase, status: "COMPLETED", providerReference,
      completedBy: uid, completedAt: Date.now(), updatedAt: Date.now(), ledgerOperationId: posted.operationId };
    return { ...root, payments: { ...root.payments, [refund.paymentId]: nextPayment },
      refundCases: { ...root.refundCases, [refundId]: nextCase } };
  }, undefined, false);
  if (!rootResult.committed) {
    throw new HttpsError("failed-precondition", "Payment refund balance changed or this refund is no longer processable. Review the case before retrying.");
  }
  const completed = rootResult.snapshot.child(`refundCases/${refundId}`).val() as RefundCase;
  await recordAdminFinancialAudit({ uid, action: "REFUND_COMPLETED", targetType: "REFUND", targetId: refundId,
    before: refund, after: completed, note: providerReference + " — " + verificationNote });
  return { refundId, status: "COMPLETED", paymentId: refund.paymentId,
    providerReference, verifiedAmountMinor, verificationNote, ledgerOperationId: posted.operationId };
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
