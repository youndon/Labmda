import { randomUUID } from "node:crypto";
import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import {
  createFinancialTransfer,
  postFinancialOperation,
  type FinancialOperation,
  type LedgerPosting,
} from "./ledger.js";

const db = () => getDatabase();
type PayoutMethod = "GCASH" | "BDO";
type ProviderKind = "MERCHANT" | "DRIVER" | "DELIVERY";
type PayoutStatus = "REQUESTED" | "APPROVED" | "REJECTING" | "PAYING" | "REJECTED" | "PAID";

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
  const snapshots = await Promise.all(roots.map((root) => db().ref(root).get()));
  return snapshots.some((snapshot) => {
    let owned = false;
    snapshot.forEach((child) => {
      if (child.child("ownerUid").val() === uid) owned = true;
    });
    return owned;
  });
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
  if (!Number.isSafeInteger(value) || (value as number) < 10_000) {
    throw new HttpsError("invalid-argument", "The minimum payout request is PHP 100.00.");
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
  const [finance, operations] = await Promise.all([
    db().ref("finance/accounts").get(),
    db().ref("finance/operations").get(),
  ]);
  const accountIds = new Set(accounts.map((account) => account.accountId));
  const balances = accounts.map((account) => {
    const value = finance.child(account.accountId);
    return {
      ...account,
      availableMinor: Number(value.child("availableMinor").val() ?? 0),
      pendingMinor: Number(value.child("pendingMinor").val() ?? 0),
      cashOwedMinor: Number(value.child("cashOwedMinor").val() ?? 0),
      clearingMinor: Number(value.child("clearingMinor").val() ?? 0),
    };
  });
  const history = Object.values((operations.val() ?? {}) as Record<string, FinancialOperation>)
    .filter((operation) => operation.postings?.some((posting) => accountIds.has(posting.accountId)))
    .sort((a, b) => b.createdAt - a.createdAt)
    .slice(0, 100)
    .map((operation) => ({
      operationId: operation.operationId, eventType: operation.eventType,
      referenceType: operation.referenceType, referenceId: operation.referenceId,
      createdAt: operation.createdAt, description: operation.description,
      postings: operation.postings.filter((posting) => accountIds.has(posting.accountId)),
    }));
  return {
    currency: "PHP",
    totals: balances.reduce((sum, item) => ({
      availableMinor: sum.availableMinor + item.availableMinor,
      pendingMinor: sum.pendingMinor + item.pendingMinor,
      cashOwedMinor: sum.cashOwedMinor + item.cashOwedMinor,
      clearingMinor: sum.clearingMinor + item.clearingMinor,
    }), { availableMinor: 0, pendingMinor: 0, cashOwedMinor: 0, clearingMinor: 0 }),
    accounts: balances, transactions: history,
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
    const snapshot = await db().ref(`finance/accounts/${account.accountId}`).get();
    return { ...account, availableMinor: Number(snapshot.child("availableMinor").val() ?? 0) };
  }));
  const requestedAccountId = typeof data.accountId === "string" ? data.accountId : "";
  const selected = eligible.find((account) => account.accountId === requestedAccountId);
  if (!selected) throw new HttpsError("permission-denied", "Choose one of your own provider wallets.");
  if (selected.availableMinor < amountMinor) {
    throw new HttpsError("failed-precondition", "This wallet does not have enough available earnings for the requested payout.");
  }
  const payoutId = randomUUID();
  const createdAt = Date.now();
  await postFinancialOperation(db(), payoutReservation({
    payoutId, uid, accountId: selected.accountId, providerKind: selected.providerKind,
    providerId: selected.providerId, amountMinor, createdAt,
  }));
  const record = {
    payoutId, uid, providerKind: selected.providerKind, providerId: selected.providerId,
    accountId: selected.accountId, amountMinor, currency: "PHP", method: profile.method,
    accountName: profile.accountName, accountNumber: profile.accountNumber,
    status: "REQUESTED" as PayoutStatus, createdAt, updatedAt: createdAt,
  };
  try {
    await db().ref(`payoutRequests/${payoutId}`).set(record);
  } catch (error) {
    await postFinancialOperation(db(), payoutRelease({
      payoutId, accountId: selected.accountId, providerKind: selected.providerKind,
      providerId: selected.providerId, amountMinor, createdAt: Date.now(),
    }));
    throw error;
  }
  return { payoutId, status: record.status, amountMinor, currency: "PHP" };
});

export const getMyPayoutHistory = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = uidFrom(call);
  const snapshot = await db().ref("payoutRequests").orderByChild("uid").equalTo(uid).get();
  const payouts = snapshot.val() as Record<string, Record<string, unknown>> | null;
  return Object.values(payouts ?? {}).map((payout) => ({
    payoutId: payout.payoutId, amountMinor: payout.amountMinor, currency: payout.currency,
    method: payout.method, maskedAccountNumber: `••••••${String(payout.accountNumber ?? "").slice(-4)}`,
    status: payout.status, transferReference: payout.transferReference ?? null, createdAt: payout.createdAt, updatedAt: payout.updatedAt,
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
      status: payout.status, createdAt: payout.createdAt,
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
    if (!current || !allowed.includes(current.status as PayoutStatus)) return;
    return { ...current, ...patch, status: next, updatedAt: Date.now() };
  });
  if (!result.committed) throw new HttpsError("failed-precondition", "This payout has already been processed or no longer exists.");
  return result.snapshot.val() as Record<string, unknown>;
}

export const approvePayoutRequest = onCall({ enforceAppCheck: true }, async (call) => {
  requireAdmin(call.auth?.token as Record<string, unknown> | undefined);
  const payoutId = String((call.data as { payoutId?: unknown }).payoutId ?? "");
  const payout = await updatePayoutStatus(payoutId, ["REQUESTED"], "APPROVED", { approvedAt: Date.now(), approvedBy: call.auth!.uid });
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
  await postFinancialOperation(db(), payoutRelease({
    payoutId, accountId: String(payout.accountId), providerKind: payout.providerKind as ProviderKind,
    providerId: String(payout.providerId), amountMinor: Number(payout.amountMinor), createdAt: Number(payout.createdAt),
  }));
  await updatePayoutStatus(payoutId, ["REJECTING"], "REJECTED", {
    rejectedAt: Date.now(), rejectedBy: call.auth!.uid,
    adminNote: String((call.data as { note?: unknown }).note ?? "").slice(0, 500),
  });
  return { payoutId, status: "REJECTED" };
});

export const markPayoutAsPaid = onCall({ enforceAppCheck: true }, async (call) => {
  requireAdmin(call.auth?.token as Record<string, unknown> | undefined);
  const data = call.data as { payoutId?: unknown; transferReference?: unknown; note?: unknown };
  const payoutId = String(data.payoutId ?? "");
  const transferReference = typeof data.transferReference === "string" ? data.transferReference.trim() : "";
  if (!transferReference || transferReference.length > 120) throw new HttpsError("invalid-argument", "Enter the GCash or BDO transfer reference.");
  const snapshot = await db().ref(`payoutRequests/${payoutId}`).get();
  if (!snapshot.exists()) throw new HttpsError("not-found", "Payout request was not found.");
  const payout = snapshot.val() as Record<string, unknown>;
  if (payout.status !== "APPROVED" && payout.status !== "PAYING") {
    throw new HttpsError("failed-precondition", "Approve the payout before recording a transfer.");
  }
  const confirmedReference = transferReference || String(payout.transferReference ?? "");
  if (!confirmedReference) throw new HttpsError("invalid-argument", "Enter the GCash or BDO transfer reference.");
  if (payout.status === "APPROVED") {
    await updatePayoutStatus(payoutId, ["APPROVED"], "PAYING", {
      paidBy: call.auth!.uid, transferReference: confirmedReference,
    });
  }
  await postFinancialOperation(db(), payoutPaid({
    payoutId, accountId: String(payout.accountId), providerKind: payout.providerKind as ProviderKind,
    providerId: String(payout.providerId), amountMinor: Number(payout.amountMinor), createdAt: Number(payout.createdAt),
  }));
  await updatePayoutStatus(payoutId, ["PAYING"], "PAID", {
    paidAt: Date.now(), paidBy: call.auth!.uid, transferReference: confirmedReference,
    adminNote: String(data.note ?? "").slice(0, 500),
  });
  return { payoutId, status: "PAID", transferReference };
});
