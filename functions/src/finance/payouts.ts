import { randomUUID } from "node:crypto";
import { resolvePayoutTransferReference, PayoutTransferReferenceError } from "./payout-transfer-reference.js";
import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { recordAdminFinancialAudit } from "./admin-financial.js";
import {
  createFinancialTransfer,
  postFinancialOperation,
  getFinancialAccount,
  listCashOwedAccounts,
  listProviderOperations,
  type FinancialOperation,
  type LedgerPosting,
} from "./ledger.js";

const db = () => getDatabase();
type PayoutMethod = "GCASH" | "BDO";
type ProviderKind = "MERCHANT" | "DRIVER" | "DELIVERY";
type PayoutStatus = "RESERVING" | "REQUESTED" | "APPROVED" | "REJECTING" | "PAYING" | "REJECTED" | "PAID" | "FAILED";

function uidFrom(call: { auth?: { uid?: string } | null }): string {
  const uid = call.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to continue.");
  return uid;
}
function requireAdmin(claims: Record<string, unknown> | undefined): void {
  if (claims?.admin !== true) throw new HttpsError("permission-denied", "Administrator access is required.");
}
function safeId(value: string): boolean {
  return /^[A-Za-z0-9_-]{1,128}$/.test(value);
}
function validatePayoutProfile(data: Record<string, unknown>): {
  method: PayoutMethod; accountName: string; accountNumber: string;
} {
  const method = data.method;
  const accountName = typeof data.accountName === "string" ? data.accountName.trim() : "";
  const accountNumber = typeof data.accountNumber === "string" ? data.accountNumber.replace(/[\s-]/g, "") : "";
  if (method !== "GCASH" && method !== "BDO") throw new HttpsError("invalid-argument", "Choose GCash or BDO.");
  if (accountName.length < 2 || accountName.length > 100) throw new HttpsError("invalid-argument", "Enter the account holder's full name.");
  if (method === "GCASH" && !/^(09\d{9}|639\d{9}|\+639\d{9})$/.test(accountNumber)) {
    throw new HttpsError("invalid-argument", "Enter a valid Philippine GCash mobile number.");
  }
  if (method === "BDO" && !/^\d{8,12}$/.test(accountNumber)) {
    throw new HttpsError("invalid-argument", "Enter a valid BDO account number (8–12 digits).");
  }
  return { method, accountName, accountNumber };
}
async function ownsMerchant(uid: string): Promise<boolean> {
  const roots = ["shops", "restaurants"];
  const ownershipChecks = await Promise.all(roots.map(async (root) => {
    const direct = await db().ref(`${root}/${uid}`).get();
    if (direct.exists()) return true;
    const owned = await db().ref(root).orderByChild("ownerUid").equalTo(uid).limitToFirst(1).get();
    return owned.exists();
  }));
  return ownershipChecks.some(Boolean);
}
async function providerAccounts(uid: string): Promise<Array<{
  accountId: string; providerKind: ProviderKind; providerId: string;
}>> {
  const [isMerchant, user] = await Promise.all([
    ownsMerchant(uid),
    db().ref(`users/${uid}`).get(),
  ]);
  const accounts: Array<{ accountId: string; providerKind: ProviderKind; providerId: string }> = [];
  if (isMerchant) accounts.push({ accountId: `wallet_merchant_${uid}`, providerKind: "MERCHANT", providerId: uid });
  if (user.child("driverService").exists()) {
    accounts.push({ accountId: `wallet_driver_${uid}`, providerKind: "DRIVER", providerId: uid });
  }
  if (user.child("deliveryServices").exists()) {
    accounts.push({ accountId: `wallet_delivery_${uid}`, providerKind: "DELIVERY", providerId: uid });
  }
  return accounts;
}
function amount(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) <= 0) {
    throw new HttpsError("invalid-argument", "Payout amount must be a positive PHP-centavo integer.");
  }
  return value as number;
}
function payoutReservation(args: {
  payoutId: string; uid: string; accountId: string; providerKind: ProviderKind;
  providerId: string; amountMinor: number; createdAt: number;
}): FinancialOperation {
  const partyType: LedgerPosting["partyType"] = args.providerKind === "DELIVERY" ? "COURIER" : args.providerKind;
  return createFinancialTransfer({
    operationId: `payout_${args.payoutId}_reserved`, eventType: "PAYOUT", referenceType: "PAYOUT",
    referenceId: args.payoutId, idempotencyKey: `payout_${args.payoutId}_reserved`,
    createdAt: args.createdAt, description: "Provider payout amount reserved for admin processing",
    amountMinor: args.amountMinor,
    source: { accountId: args.accountId, bucket: "AVAILABLE", deltaMinor: 0, partyType, partyId: args.providerId },
    destination: { accountId: args.accountId, bucket: "CLEARING", deltaMinor: 0, partyType, partyId: args.providerId },
  });
}
function payoutRelease(args: {
  payoutId: string; accountId: string; providerKind: ProviderKind; providerId: string;
  amountMinor: number; createdAt: number;
}): FinancialOperation {
  const partyType: LedgerPosting["partyType"] = args.providerKind === "DELIVERY" ? "COURIER" : args.providerKind;
  return createFinancialTransfer({
    operationId: `payout_${args.payoutId}_released`, eventType: "PAYOUT", referenceType: "PAYOUT",
    referenceId: args.payoutId, idempotencyKey: `payout_${args.payoutId}_released`,
    createdAt: args.createdAt, description: "Rejected payout returned to provider available balance",
    amountMinor: args.amountMinor,
    source: { accountId: args.accountId, bucket: "CLEARING", deltaMinor: 0, partyType, partyId: args.providerId },
    destination: { accountId: args.accountId, bucket: "AVAILABLE", deltaMinor: 0, partyType, partyId: args.providerId },
  });
}
function payoutPaid(args: {
  payoutId: string; accountId: string; providerKind: ProviderKind; providerId: string;
  amountMinor: number; createdAt: number;
}): FinancialOperation {
  const partyType: LedgerPosting["partyType"] = args.providerKind === "DELIVERY" ? "COURIER" : args.providerKind;
  return createFinancialTransfer({
    operationId: `payout_${args.payoutId}_paid`, eventType: "PAYOUT", referenceType: "PAYOUT",
    referenceId: args.payoutId, idempotencyKey: `payout_${args.payoutId}_paid`,
    createdAt: args.createdAt, description: "Admin confirmed manual provider payout",
    amountMinor: args.amountMinor,
    source: { accountId: args.accountId, bucket: "CLEARING", deltaMinor: 0, partyType, partyId: args.providerId },
    destination: { accountId: `external_payout_${args.payoutId}`, bucket: "CLEARING", deltaMinor: 0, partyType: "PAYMENT_PROVIDER", partyId: "manual_bank_transfer" },
  });
}

export const saveMyPayoutProfile = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = uidFrom(call);
  const profile = validatePayoutProfile(call.data as Record<string, unknown>);
  await db().ref(`payoutProfiles/${uid}`).set({
    ...profile, updatedAt: Date.now(), updatedBy: uid,
  });
  return { saved: true, method: profile.method, accountName: profile.accountName,
    maskedAccountNumber: profile.method === "GCASH" ? `••••••${profile.accountNumber.slice(-4)}` : `••••••••${profile.accountNumber.slice(-4)}` };
});

export const getMyPayoutProfile = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = uidFrom(call);
  const snapshot = await db().ref(`payoutProfiles/${uid}`).get();
  if (!snapshot.exists()) return { configured: false };
  const profile = snapshot.val() as { method: PayoutMethod; accountName: string; accountNumber: string; updatedAt: number };
  return { configured: true, method: profile.method, accountName: profile.accountName,
    maskedAccountNumber: `••••••${profile.accountNumber.slice(-4)}`, updatedAt: profile.updatedAt };
});

export const getMyEarnings = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = uidFrom(call);
  const accounts = await providerAccounts(uid);
  const [accountBalances, activity, cashAccounts] = await Promise.all([
    Promise.all(accounts.map((account) => getFinancialAccount(account.accountId))),
    listProviderOperations(uid, 100),
    listCashOwedAccounts(uid, true),
  ]);
  const accountIds = new Set(accounts.map((account) => account.accountId));
  const balances = accounts.map((account, index) => {
    const value = accountBalances[index];
    return { ...account, availableMinor: value?.availableMinor ?? 0, pendingMinor: value?.pendingMinor ?? 0,
      cashOwedMinor: value?.cashOwedMinor ?? 0, clearingMinor: value?.clearingMinor ?? 0 };
  });
  const history = activity
    .filter((operation) => operation.postings?.some((posting) => accountIds.has(posting.accountId)))
    .sort((a, b) => b.createdAt - a.createdAt)
    .slice(0, 100)
    .map((operation) => ({
      operationId: operation.operationId, eventType: operation.eventType,
      referenceType: operation.referenceType, referenceId: operation.referenceId,
      createdAt: operation.createdAt, description: operation.description,
      postings: operation.postings.filter((posting) => accountIds.has(posting.accountId)),
    }));
  const cashOwedMinor = cashAccounts.reduce((sum, account) => sum + account.cashOwedMinor, 0);
  return {
    currency: "PHP",
    totals: balances.reduce((sum, item) => ({
      availableMinor: sum.availableMinor + item.availableMinor,
      pendingMinor: sum.pendingMinor + item.pendingMinor,
      cashOwedMinor: sum.cashOwedMinor,
      clearingMinor: sum.clearingMinor + item.clearingMinor,
    }), { availableMinor: 0, pendingMinor: 0, cashOwedMinor, clearingMinor: 0 }),
    accounts: balances, transactions: history,
  };
});


type EarningsAnalyticsDay = { date: string; earnedMinor: number; payoutMinor: number };

export const getMyEarningsAnalytics = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = uidFrom(call);
  const period = (call.data as { period?: unknown } | undefined)?.period;
  if (period !== "WEEK" && period !== "MONTH") {
    throw new HttpsError("invalid-argument", "Choose WEEK or MONTH for the analytics period.");
  }
  const days = period === "WEEK" ? 7 : 30;
  const manilaOffsetMs = 8 * 60 * 60 * 1000;
  const manilaToday = new Date(Date.now() + manilaOffsetMs);
  const end = Date.UTC(manilaToday.getUTCFullYear(), manilaToday.getUTCMonth(), manilaToday.getUTCDate()) - manilaOffsetMs;
  const start = end - (days - 1) * 24 * 60 * 60 * 1000;
  const buckets = new Map<string, EarningsAnalyticsDay>();
  for (let index = 0; index < days; index += 1) {
    const date = new Date(start + index * 24 * 60 * 60 * 1000 + manilaOffsetMs).toISOString().slice(0, 10);
    buckets.set(date, { date, earnedMinor: 0, payoutMinor: 0 });
  }
  const [accounts, activity] = await Promise.all([
    providerAccounts(uid),
    listProviderOperations(uid, undefined, start),
  ]);
  const accountIds = new Set(accounts.map((account) => account.accountId));
  const earningEvents = new Set(["MERCHANT_EARNING", "DRIVER_EARNING", "COURIER_EARNING", "CUSTOMER_PAYMENT"]);
  activity.forEach((operation) => {
    if (!Number.isFinite(operation.createdAt)) return;
    const date = new Date(operation.createdAt + manilaOffsetMs).toISOString().slice(0, 10);
    const bucket = buckets.get(date);
    if (!bucket || !Array.isArray(operation.postings)) return;
    operation.postings.forEach((posting) => {
      if (!accountIds.has(posting.accountId) || !Number.isSafeInteger(posting.deltaMinor)) return;
      if (earningEvents.has(operation.eventType)
        && (posting.bucket === "AVAILABLE" || posting.bucket === "PENDING")
        && posting.deltaMinor > 0) {
        bucket.earnedMinor += posting.deltaMinor;
      }
      if (operation.eventType === "PAYOUT" && posting.bucket === "AVAILABLE" && posting.deltaMinor < 0) {
        bucket.payoutMinor += Math.abs(posting.deltaMinor);
      }
    });
  });
  return {
    currency: "PHP",
    period,
    startDate: new Date(start + manilaOffsetMs).toISOString().slice(0, 10),
    endDate: new Date(end + manilaOffsetMs).toISOString().slice(0, 10),
    days: Array.from(buckets.values()),
    totals: Array.from(buckets.values()).reduce((totals, day) => ({
      earnedMinor: totals.earnedMinor + day.earnedMinor,
      payoutMinor: totals.payoutMinor + day.payoutMinor,
    }), { earnedMinor: 0, payoutMinor: 0 }),
  };
});

export const requestMyPayout = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = uidFrom(call);
  const data = call.data as { amountMinor?: unknown; accountId?: unknown };
  const amountMinor = amount(data.amountMinor);
  const [profileSnapshot, accounts] = await Promise.all([
    db().ref(`payoutProfiles/${uid}`).get(), providerAccounts(uid),
  ]);
  if (!profileSnapshot.exists()) throw new HttpsError("failed-precondition", "Set up a GCash or BDO payout account first.");
  const profile = profileSnapshot.val() as { method: PayoutMethod; accountName: string; accountNumber: string };
  const eligible = await Promise.all(accounts.map(async (account) => {
    const snapshot = await getFinancialAccount(account.accountId);
    return { ...account, availableMinor: snapshot?.availableMinor ?? 0 };
  }));
  const requestedAccountId = typeof data.accountId === "string" ? data.accountId : "";
  const selected = eligible.find((account) => account.accountId === requestedAccountId);
  if (!selected) throw new HttpsError("permission-denied", "Choose one of your own provider wallets.");
  if (selected.availableMinor < amountMinor) {
    throw new HttpsError("failed-precondition", "This wallet does not have enough available earnings for the requested payout.");
  }
  const payoutId = randomUUID();
  const createdAt = Date.now();
  const record = {
    payoutId, uid, providerKind: selected.providerKind, providerId: selected.providerId,
    accountId: selected.accountId, amountMinor, currency: "PHP", method: profile.method,
    accountName: profile.accountName, accountNumber: profile.accountNumber,
    status: "RESERVING" as PayoutStatus, createdAt, updatedAt: createdAt,
  };
  const created = await db().ref(`payoutRequests/${payoutId}`).transaction((current) => current === null ? record : undefined);
  if (!created.committed) throw new HttpsError("aborted", "Could not create the payout request. Please try again.");
  try {
    await postFinancialOperation(payoutReservation({
      payoutId, uid, accountId: selected.accountId, providerKind: selected.providerKind,
      providerId: selected.providerId, amountMinor, createdAt,
    }));
  } catch (error) {
    // Firestore can commit even when the caller loses the response. Keep RESERVING
    // and let the idempotent recovery worker determine the committed outcome.
    if (error instanceof Error && /Payout exceeds the provider available balance/i.test(error.message)) {
      await db().ref(`payoutRequests/${payoutId}`).transaction((current) =>
        current?.status === "RESERVING" ? { ...current, status: "FAILED", failureReason: "INSUFFICIENT_AVAILABLE_BALANCE", updatedAt: Date.now() } : undefined);
    }
    throw error;
  }
  const requested = await updatePayoutStatus(payoutId, ["RESERVING"], "REQUESTED", {});
  return { payoutId, status: requested.status, amountMinor, currency: "PHP" };
});

/**
 * Repairs payout state transitions that were interrupted between RTDB and Firestore.
 * RESERVING is safe to replay because the journal operation has a stable idempotency key.
 * REJECTING is safe to replay for the same reason. PAYING is deliberately excluded:
 * only an administrator can verify whether the external transfer actually happened.
 */
export const recoverInterruptedPayouts = onSchedule("every 5 minutes", async () => {
  const [reserving, rejecting] = await Promise.all([
    db().ref("payoutRequests").orderByChild("status").equalTo("RESERVING").get(),
    db().ref("payoutRequests").orderByChild("status").equalTo("REJECTING").get(),
  ]);
  const records = [
    ...Object.values((reserving.val() ?? {}) as Record<string, Record<string, unknown>>),
    ...Object.values((rejecting.val() ?? {}) as Record<string, Record<string, unknown>>),
  ];
  for (const payout of records) {
    const payoutId = String(payout.payoutId ?? "");
    if (!safeId(payoutId) || !safeId(String(payout.accountId ?? "")) || !safeId(String(payout.providerId ?? ""))
      || !Number.isSafeInteger(payout.amountMinor) || Number(payout.amountMinor) <= 0
      || !Number.isSafeInteger(payout.createdAt)) {
      console.error("Skipping malformed interrupted payout record", payoutId);
      continue;
    }
    try {
      if (payout.status === "RESERVING") {
        await postFinancialOperation(payoutReservation({
          payoutId, uid: String(payout.uid), accountId: String(payout.accountId),
          providerKind: payout.providerKind as ProviderKind, providerId: String(payout.providerId),
          amountMinor: Number(payout.amountMinor), createdAt: Number(payout.createdAt),
        }));
        await updatePayoutStatus(payoutId, ["RESERVING"], "REQUESTED", {});
      } else if (payout.status === "REJECTING") {
        await postFinancialOperation(payoutRelease({
          payoutId, accountId: String(payout.accountId), providerKind: payout.providerKind as ProviderKind,
          providerId: String(payout.providerId), amountMinor: Number(payout.amountMinor),
          createdAt: Number(payout.createdAt),
        }));
        await updatePayoutStatus(payoutId, ["REJECTING"], "REJECTED", {
          rejectedAt: Date.now(), recoveryApplied: true,
        });
      }
    } catch (error) {
      console.error("Interrupted payout recovery will retry", payoutId, error);
    }
  }
});

export const getMyPayoutHistory = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = uidFrom(call);
  const snapshot = await db().ref("payoutRequests").orderByChild("uid").equalTo(uid).get();
  const payouts = snapshot.val() as Record<string, Record<string, unknown>> | null;
  return Object.values(payouts ?? {}).map((payout) => ({
    payoutId: payout.payoutId, amountMinor: payout.amountMinor, currency: payout.currency,
    method: payout.method, maskedAccountNumber: `••••••${String(payout.accountNumber ?? "").slice(-4)}`,
    status: payout.status, createdAt: payout.createdAt, updatedAt: payout.updatedAt,
    transferReference: payout.transferReference ?? null, adminNote: payout.adminNote ?? null,
  })).sort((a, b) => Number(b.createdAt) - Number(a.createdAt));
});

export const getPendingPayoutRequests = onCall({ enforceAppCheck: true }, async (call) => {
  requireAdmin(call.auth?.token as Record<string, unknown> | undefined);
  const [requested, approved, rejecting, paying] = await Promise.all([
    db().ref("payoutRequests").orderByChild("status").equalTo("REQUESTED").get(),
    db().ref("payoutRequests").orderByChild("status").equalTo("APPROVED").get(),
    db().ref("payoutRequests").orderByChild("status").equalTo("REJECTING").get(),
    db().ref("payoutRequests").orderByChild("status").equalTo("PAYING").get(),
  ]);
  const pending = {
    ...((requested.val() ?? {}) as Record<string, Record<string, unknown>>),
    ...((approved.val() ?? {}) as Record<string, Record<string, unknown>>),
    ...((rejecting.val() ?? {}) as Record<string, Record<string, unknown>>),
    ...((paying.val() ?? {}) as Record<string, Record<string, unknown>>),
  };
  return Object.values(pending)
    .map((payout) => ({
      payoutId: payout.payoutId, uid: payout.uid, providerKind: payout.providerKind,
      providerId: payout.providerId, amountMinor: payout.amountMinor, currency: payout.currency,
      method: payout.method, accountName: payout.accountName, accountNumber: payout.accountNumber,
      status: payout.status, transferReference: payout.transferReference ?? null, createdAt: payout.createdAt,
    })).sort((a, b) => Number(a.createdAt) - Number(b.createdAt));
});

async function updatePayoutStatus(
  payoutId: string,
  allowed: PayoutStatus[],
  next: PayoutStatus,
  patch: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  if (!safeId(payoutId)) throw new HttpsError("invalid-argument", "Payout ID is invalid.");
  const result = await db().ref(`payoutRequests/${payoutId}`).transaction((current) => {
    if (current === null) return null;
    if (!allowed.includes(current.status as PayoutStatus)) return;
    return { ...current, ...patch, status: next, updatedAt: Date.now() };
  });
  const saved = result.snapshot.val() as Record<string, unknown> | null;
  if (!result.committed || !saved || saved.status !== next) {
    throw new HttpsError("failed-precondition", "This payout has already been processed or no longer exists.");
  }
  return saved;
}

export const approvePayoutRequest = onCall({ enforceAppCheck: true }, async (call) => {
  requireAdmin(call.auth?.token as Record<string, unknown> | undefined);
  const payoutId = String((call.data as { payoutId?: unknown }).payoutId ?? "");
  const payout = await updatePayoutStatus(payoutId, ["REQUESTED"], "APPROVED", { approvedAt: Date.now(), approvedBy: call.auth!.uid });
  await recordAdminFinancialAudit({ uid: call.auth!.uid, action: "PAYOUT_APPROVED", targetType: "PAYOUT", targetId: payoutId, after: payout });
  return { payoutId, status: payout.status };
});

export const rejectPayoutRequest = onCall({ enforceAppCheck: true }, async (call) => {
  requireAdmin(call.auth?.token as Record<string, unknown> | undefined);
  const payoutId = String((call.data as { payoutId?: unknown }).payoutId ?? "");
  const snapshot = await db().ref(`payoutRequests/${payoutId}`).get();
  if (!snapshot.exists()) throw new HttpsError("not-found", "Payout request was not found.");
  const payout = snapshot.val() as Record<string, unknown>;
  if (payout.status !== "REQUESTED" && payout.status !== "APPROVED" && payout.status !== "REJECTING") {
    throw new HttpsError("failed-precondition", "This payout cannot be rejected.");
  }
  if (payout.status !== "REJECTING") {
    await updatePayoutStatus(payoutId, ["REQUESTED", "APPROVED"], "REJECTING", { rejectedBy: call.auth!.uid });
  }
  await postFinancialOperation(payoutRelease({
    payoutId, accountId: String(payout.accountId), providerKind: payout.providerKind as ProviderKind,
    providerId: String(payout.providerId), amountMinor: Number(payout.amountMinor), createdAt: Number(payout.createdAt),
  }));
  const rejected = await updatePayoutStatus(payoutId, ["REJECTING"], "REJECTED", {
    rejectedAt: Date.now(), rejectedBy: call.auth!.uid,
    adminNote: String((call.data as { note?: unknown }).note ?? "").slice(0, 500),
  });
  await recordAdminFinancialAudit({ uid: call.auth!.uid, action: "PAYOUT_REJECTED", targetType: "PAYOUT", targetId: payoutId, before: payout, after: rejected });
  return { payoutId, status: "REJECTED" };
});

export const markPayoutAsPaid = onCall({ enforceAppCheck: true }, async (call) => {
  requireAdmin(call.auth?.token as Record<string, unknown> | undefined);
  const data = call.data as { payoutId?: unknown; transferReference?: unknown; note?: unknown };
  const payoutId = String(data.payoutId ?? "");
  if (!safeId(payoutId)) throw new HttpsError("invalid-argument", "Payout ID is invalid.");
  const transferReference = typeof data.transferReference === "string" ? data.transferReference : "";
  const snapshot = await db().ref(`payoutRequests/${payoutId}`).get();
  if (!snapshot.exists()) throw new HttpsError("not-found", "Payout request was not found.");
  const payout = snapshot.val() as Record<string, unknown>;
  if (payout.status !== "APPROVED" && payout.status !== "PAYING") {
    throw new HttpsError("failed-precondition", "Approve the payout before recording a transfer.");
  }
  let confirmedReference: string;
  try {
    confirmedReference = resolvePayoutTransferReference(
      payout.status as "APPROVED" | "PAYING",
      payout.transferReference,
      transferReference,
    );
  } catch (error) {
    if (error instanceof PayoutTransferReferenceError) throw new HttpsError(error.code, error.message);
    throw error;
  }
  if (payout.status === "APPROVED") {
    await updatePayoutStatus(payoutId, ["APPROVED"], "PAYING", {
      paidBy: call.auth!.uid, transferReference: confirmedReference,
    });
  }
  await postFinancialOperation(payoutPaid({
    payoutId, accountId: String(payout.accountId), providerKind: payout.providerKind as ProviderKind,
    providerId: String(payout.providerId), amountMinor: Number(payout.amountMinor), createdAt: Number(payout.createdAt),
  }));
  const paid = await updatePayoutStatus(payoutId, ["PAYING"], "PAID", {
    paidAt: Date.now(), paidBy: call.auth!.uid, transferReference: confirmedReference,
    adminNote: String(data.note ?? "").slice(0, 500),
  });
  await recordAdminFinancialAudit({ uid: call.auth!.uid, action: "PAYOUT_MARKED_PAID", targetType: "PAYOUT", targetId: payoutId, before: payout, after: paid, note: confirmedReference });
  return { payoutId, status: "PAID", transferReference };
});
