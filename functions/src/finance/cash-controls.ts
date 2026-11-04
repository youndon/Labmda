import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { listCashOwedAccounts } from "./ledger.js";

const db = () => getDatabase();
const DEFAULT_ALERT_HOURS = 24;
const DEFAULT_OUTSTANDING_CAP_MINOR = 50_000; // PHP 500.00
type CashControls = { alertAfterHours: number; maxCollectorOutstandingMinor: number };

function requireUid(uid?: string): string {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to continue.");
  return uid;
}
function requireAdmin(claims: Record<string, unknown> | undefined): void {
  if (claims?.admin !== true) throw new HttpsError("permission-denied", "Administrator access is required.");
}
function readControls(raw: unknown): CashControls {
  const value = raw && typeof raw === "object" ? raw as Record<string, unknown> : {};
  return {
    alertAfterHours: Number.isSafeInteger(value.alertAfterHours) && Number(value.alertAfterHours) >= 1 && Number(value.alertAfterHours) <= 168
      ? Number(value.alertAfterHours) : DEFAULT_ALERT_HOURS,
    maxCollectorOutstandingMinor: Number.isSafeInteger(value.maxCollectorOutstandingMinor)
      && Number(value.maxCollectorOutstandingMinor) >= 0 && Number(value.maxCollectorOutstandingMinor) <= 10_000_000
      ? Number(value.maxCollectorOutstandingMinor) : DEFAULT_OUTSTANDING_CAP_MINOR,
  };
}
async function controls(): Promise<CashControls> {
  const snapshot = await db().ref("cashControls/config").get();
  return readControls(snapshot.val());
}
export async function collectorCashOutstandingMinor(uid: string): Promise<number> {
  const accounts = await listCashOwedAccounts(uid);
  const total = accounts.reduce((sum, account) => sum + Number(account.cashOwedMinor ?? 0), 0);
  if (!Number.isSafeInteger(total) || total < 0) throw new HttpsError("failed-precondition", "Collector cash balance is invalid.");
  return total;
}
export async function assertCollectorCashLimit(uid: string): Promise<{ outstandingMinor: number; limitMinor: number }> {
  const [config, outstandingMinor] = await Promise.all([controls(), collectorCashOutstandingMinor(uid)]);
  if (config.maxCollectorOutstandingMinor > 0 && outstandingMinor >= config.maxCollectorOutstandingMinor) {
    throw new HttpsError("failed-precondition",
      `Your outstanding cash balance is ₱${(outstandingMinor / 100).toFixed(2)}. Settle it before going online or accepting another job.`);
  }
  return { outstandingMinor, limitMinor: config.maxCollectorOutstandingMinor };
}

/** Server-owned online switch. Direct client writes are not a valid online transition. */
export const setCollectorOnline = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const online = call.data?.online;
  const service = call.data?.service;
  if (typeof online !== "boolean" || (service !== "DRIVER" && service !== "DELIVERY")) {
    throw new HttpsError("invalid-argument", "Choose DRIVER or DELIVERY and provide an online state.");
  }
  const serviceNode = service === "DRIVER" ? "driverService" : "deliveryServices";
  const profileRef = db().ref(`users/${uid}`);
  const profile = await profileRef.get();
  if (!profile.exists() || profile.child(`${serviceNode}/status`).val() !== "APPROVED") {
    throw new HttpsError("permission-denied", "An approved provider account is required.");
  }
  const balance = online ? await assertCollectorCashLimit(uid) : null;
  const result = await profileRef.child(`${serviceNode}/isOnline`).transaction((current: unknown) => {
    if (online && profile.child(`${serviceNode}/status`).val() !== "APPROVED") return;
    return online;
  }, undefined, false);
  if (!result.committed && result.snapshot.val() !== online) {
    throw new HttpsError("aborted", "Your online status changed. Refresh and try again.");
  }
  if (!online) await db().ref(`driverLocations/${uid}`).remove();
  return { service, online, ...(balance ?? {}) };
});

/** Collector's cash dashboard: platform commission and merchant handovers stay separate. */
export const getMyCashOperations = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const [accounts, collectionsSnapshot, handoversSnapshot] = await Promise.all([
    listCashOwedAccounts(uid),
    db().ref("cashCollections").get(),
    db().ref("cashHandovers").get(),
  ]);
  const handovers = (handoversSnapshot.val() ?? {}) as Record<string, { status?: string }>;
  const collections = Object.values((collectionsSnapshot.val() ?? {}) as Record<string, {
    id: string; paymentId: string; collectorId: string; amountMinor: number; status: string; collectedAt: number;
  }>).filter((item) => item.collectorId === uid && item.status === "COLLECTED");
  const merchantSettlements: Array<{ paymentId: string; merchantId: string; amountMinor: number; collectedAt: number }> = [];
  for (const collection of collections.slice(-300)) {
    const allocations = await db().ref(`paymentAllocations/${collection.paymentId}`).get();
    const rows = Object.values((allocations.val() ?? {}) as Record<string, {
      beneficiaryType?: string; beneficiaryId?: string; amountMinor?: number;
    }>);
    for (const row of rows) {
      if (row.beneficiaryType !== "MERCHANT" || !row.beneficiaryId) continue;
      const handoverId = `merchant_${collection.paymentId}_${row.beneficiaryId}`;
      if (handovers[handoverId]?.status === "CONFIRMED") continue;
      merchantSettlements.push({
        paymentId: collection.paymentId, merchantId: row.beneficiaryId,
        amountMinor: Number(row.amountMinor ?? 0), collectedAt: collection.collectedAt,
      });
    }
  }
  const platformOutstandingMinor = accounts.filter((a) => a.accountId === `cash_owed_${uid}`)
    .reduce((sum, a) => sum + Number(a.cashOwedMinor ?? 0), 0);
  const merchantOutstandingMinor = accounts.filter((a) => a.accountId.startsWith(`cash_owed_${uid}_merchant_`))
    .reduce((sum, a) => sum + Number(a.cashOwedMinor ?? 0), 0);
  return {
    collectorId: uid, currency: "PHP", platformOutstandingMinor, merchantOutstandingMinor,
    totalOutstandingMinor: platformOutstandingMinor + merchantOutstandingMinor,
    merchantSettlements,
  };
});

/** Merchant-only list of cash allocations that have not yet been physically handed over. */
export const getMyCashSettlementItems = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const [collectionsSnapshot, handoversSnapshot] = await Promise.all([
    db().ref("cashCollections").get(), db().ref("cashHandovers").get(),
  ]);
  const handovers = (handoversSnapshot.val() ?? {}) as Record<string, { status?: string }>;
  const collections = Object.values((collectionsSnapshot.val() ?? {}) as Record<string, {
    paymentId: string; status: string; amountMinor: number; collectedAt: number;
  }>).filter((item) => item.status === "COLLECTED").slice(-500);
  const items: Array<{ paymentId: string; amountMinor: number; collectedAt: number }> = [];
  for (const collection of collections) {
    const [payment, allocationSnapshot] = await Promise.all([
      db().ref(`payments/${collection.paymentId}`).get(),
      db().ref(`paymentAllocations/${collection.paymentId}`).get(),
    ]);
    if (payment.child("method").val() !== "CASH") continue;
    const allocation = Object.values((allocationSnapshot.val() ?? {}) as Record<string, {
      beneficiaryType?: string; beneficiaryId?: string; amountMinor?: number;
    }>).find((row) => row.beneficiaryType === "MERCHANT" && row.beneficiaryId === uid);
    if (!allocation) continue;
    const handoverId = `merchant_${collection.paymentId}_${uid}`;
    if (handovers[handoverId]?.status === "CONFIRMED") continue;
    items.push({ paymentId: collection.paymentId, amountMinor: Number(allocation.amountMinor ?? 0), collectedAt: collection.collectedAt });
  }
  return { currency: "PHP", items };
});

/** Admin-only cash controls, aged collection alerts and collector balances. */
export const getAdminCashControlOverview = onCall({ enforceAppCheck: true }, async (call) => {
  requireUid(call.auth?.uid);
  requireAdmin(call.auth?.token as Record<string, unknown> | undefined);
  const [configSnapshot, alertsSnapshot, accounts] = await Promise.all([
    db().ref("cashControls/config").get(), db().ref("adminCashAlerts").get(), listCashOwedAccounts(),
  ]);
  const alerts = Object.values((alertsSnapshot.val() ?? {}) as Record<string, Record<string, unknown>>)
    .filter((item) => item.status === "OPEN")
    .sort((a, b) => Number(b.ageHours ?? 0) - Number(a.ageHours ?? 0));
  const collectors = new Map<string, { collectorId: string; outstandingMinor: number }>();
  for (const account of accounts) {
    const collectorId = account.accountId.slice("cash_owed_".length).split("_merchant_")[0]!;
    const current = collectors.get(collectorId) ?? { collectorId, outstandingMinor: 0 };
    current.outstandingMinor += Number(account.cashOwedMinor ?? 0);
    collectors.set(collectorId, current);
  }
  return { config: readControls(configSnapshot.val()), alerts: alerts.slice(0, 200), collectors: [...collectors.values()] };
});

export const updateAdminCashControls = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  requireAdmin(call.auth?.token as Record<string, unknown> | undefined);
  const config = readControls(call.data);
  if (call.data?.alertAfterHours !== config.alertAfterHours
    || call.data?.maxCollectorOutstandingMinor !== config.maxCollectorOutstandingMinor) {
    throw new HttpsError("invalid-argument", "Alert age must be 1–168 hours and the cap must be 0–10,000,000 PHP centavos.");
  }
  const record = { ...config, updatedAt: Date.now(), updatedBy: uid };
  await db().ref("cashControls/config").set(record);
  return record;
});

/**
 * Hourly durable admin alert. Alerts are deduplicated per payment and repeated
 * at most every 12 hours until collection is confirmed or the payment changes.
 */
export const alertOverdueCashCollections = onSchedule("every 60 minutes", async () => {
  const now = Date.now();
  const config = await controls();
  const threshold = now - config.alertAfterHours * 60 * 60 * 1000;
  const [paymentsSnapshot, alertsSnapshot] = await Promise.all([
    db().ref("payments").get(), db().ref("adminCashAlerts").get(),
  ]);
  const payments = (paymentsSnapshot.val() ?? {}) as Record<string, Record<string, any>>;
  const alerts = (alertsSnapshot.val() ?? {}) as Record<string, Record<string, any>>;
  const updates: Record<string, unknown> = {};
  for (const [paymentId, payment] of Object.entries(payments)) {
    if (payment.method !== "CASH" || payment.status !== "PENDING" || !Number.isSafeInteger(payment.createdAt)
      || Number(payment.createdAt) > threshold) continue;
    const prior = alerts[paymentId];
    if (prior?.status === "OPEN" && now - Number(prior.lastAlertAt ?? 0) < 12 * 60 * 60 * 1000) continue;
    let collectorId = typeof payment.deliveryId === "string" ? payment.deliveryId : "";
    if (!collectorId && payment.transactionType === "RIDE" && typeof payment.tripId === "string") {
      const trip = await db().ref(`trips/${payment.tripId}`).get();
      collectorId = typeof trip.child("driverId").val() === "string" ? String(trip.child("driverId").val()) : "";
    }
    updates[paymentId] = {
      id: paymentId, paymentId, collectorId, transactionType: payment.transactionType ?? "",
      orderId: payment.orderId ?? "", tripId: payment.tripId ?? "",
      amountMinor: Number(payment.amountMinor ?? 0), currency: "PHP",
      paymentCreatedAt: Number(payment.createdAt), ageHours: Math.floor((now - Number(payment.createdAt)) / 3_600_000),
      status: "OPEN", firstAlertAt: prior?.firstAlertAt ?? now, lastAlertAt: now,
      alertAfterHours: config.alertAfterHours,
    };
  }
  for (const [paymentId, alert] of Object.entries(alerts)) {
    if (alert.status !== "OPEN") continue;
    const payment = payments[paymentId];
    if (!payment || payment.method !== "CASH" || payment.status !== "PENDING") {
      updates[paymentId] = { ...alert, status: "RESOLVED", resolvedAt: now };
    }
  }
  if (Object.keys(updates).length) await db().ref("adminCashAlerts").update(updates);
});
