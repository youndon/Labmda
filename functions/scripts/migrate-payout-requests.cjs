#!/usr/bin/env node
"use strict";

const ACTIVE = new Set(["PENDING_ADMIN_APPROVAL", "APPROVING"]);
const TERMINAL = new Set(["PAID", "FAILED", "REVERSED", "REJECTED"]);

function text(value) { return typeof value === "string" ? value.trim() : ""; }
function positiveCentavos(value, label) {
  const amount = Number(value);
  if (!Number.isSafeInteger(amount) || amount <= 0) throw new Error(label + " must be a positive integer number of centavos.");
  return amount;
}

function mapLegacyRequest(requestId, legacy, existing, now = Date.now()) {
  const userId = text(legacy.userId);
  if (!userId) throw new Error("Payout request " + requestId + " has no userId.");
  const amountCentavos = positiveCentavos(legacy.amountCentavos, "Payout request " + requestId + " amount");
  const requestStatus = text(legacy.status).toUpperCase();
  if (!requestStatus) throw new Error("Payout request " + requestId + " has no status.");
  const current = existing && typeof existing === "object" ? existing : {};
  if (text(current.userId) && text(current.userId) !== userId) throw new Error("Payout " + requestId + " belongs to a different user; refusing to merge.");
  if (Number.isSafeInteger(Number(current.amountCentavos)) && Number(current.amountCentavos) > 0 &&
      Number(current.amountCentavos) !== amountCentavos) throw new Error("Payout " + requestId + " amount conflicts with its request; refusing to merge.");

  const hasProviderPayout = Boolean(text(current.providerPayoutId));
  if (TERMINAL.has(requestStatus) && requestStatus !== "REJECTED" && !hasProviderPayout) {
    throw new Error("Payout request " + requestId + " is " + requestStatus + " but has no provider payout record; reconcile it manually before migration.");
  }
  if (requestStatus === "PROCESSING" && !Object.keys(current).length) {
    throw new Error("Processing payout request " + requestId + " has no payouts record; reconcile it manually before migration.");
  }

  const request = {
    id: requestId,
    requestKey: text(legacy.requestKey),
    status: requestStatus,
    requestedAtTimestamp: Number(legacy.createdAtTimestamp || now),
    updatedAtTimestamp: Number(legacy.updatedAtTimestamp || now),
    maskedAccountNumber: text(legacy.maskedAccountNumber),
    approvedBy: text(legacy.approvedBy) || null,
    approvedAtTimestamp: Number(legacy.approvedAtTimestamp || 0) || null,
    rejectedBy: text(legacy.rejectedBy) || null,
    rejectedAtTimestamp: Number(legacy.rejectedAtTimestamp || 0) || null,
    legacyPath: "payoutRequests/" + requestId
  };
  const canonical = {
    ...current,
    id: text(current.id) || requestId,
    userId,
    amountCentavos,
    amount: Number.isFinite(Number(current.amount)) ? Number(current.amount) : amountCentavos / 100,
    currency: text(current.currency) || "PHP",
    provider: text(current.provider) || "XENDIT",
    payoutRequestId: requestId,
    requestStatus,
    request,
    createdAtTimestamp: Number(current.createdAtTimestamp || legacy.createdAtTimestamp || now),
    updatedAtTimestamp: Math.max(Number(current.updatedAtTimestamp || 0), Number(legacy.updatedAtTimestamp || 0), now)
  };
  if (!text(current.status)) canonical.status = requestStatus;
  return canonical;
}

async function run() {
  const { applicationDefault, getApps, initializeApp } = require("firebase-admin/app");
  const { getDatabase } = require("firebase-admin/database");
  const args = new Set(process.argv.slice(2));
  const apply = args.has("--apply");
  if (!process.env.FIREBASE_DATABASE_URL) throw new Error("Set FIREBASE_DATABASE_URL to the intended Realtime Database URL.");
  if (apply && !args.has("--ack-runtime-cutover")) {
    throw new Error("Refusing writes. Deploy and verify the payout runtime cutover first, then pass --ack-runtime-cutover.");
  }
  const app = getApps()[0] || initializeApp({ credential: applicationDefault(), databaseURL: process.env.FIREBASE_DATABASE_URL });
  const db = getDatabase(app);
  const snapshot = await db.ref("payoutRequests").get();
  const rows = [];
  snapshot.forEach(child => { if (child.key && child.exists()) rows.push({ id: child.key, value: child.val() }); return false; });
  let migrated = 0, unchanged = 0;
  const errors = [];
  for (const row of rows) {
    try {
      const ref = db.ref("payouts/" + row.id);
      const existingSnapshot = await ref.get();
      const before = existingSnapshot.val() || {};
      const after = mapLegacyRequest(row.id, row.value || {}, before);
      if (text(before.payoutRequestId) === row.id &&
          text(before.requestStatus) === text(row.value && row.value.status).toUpperCase() && before.request) {
        unchanged++;
        continue;
      }
      if (apply) {
        const tx = await ref.transaction(current => mapLegacyRequest(row.id, row.value || {}, current || {}));
        if (!tx.committed && !tx.snapshot.exists()) throw new Error("Realtime Database transaction did not commit.");
      }
      migrated++;
    } catch (error) {
      errors.push({ id: row.id, message: error instanceof Error ? error.message : String(error) });
    }
  }
  process.stdout.write(JSON.stringify({
    mode: apply ? "APPLY" : "DRY_RUN", source: "payoutRequests", destination: "payouts",
    scanned: rows.length, wouldMigrateOrMigrated: migrated, unchanged, errors, legacyRecordsDeleted: 0
  }, null, 2) + "\n");
  if (errors.length) process.exitCode = 2;
}
if (require.main === module) run().catch(error => {
  process.stderr.write((error instanceof Error ? error.message : String(error)) + "\n");
  process.exitCode = 1;
});
module.exports = { mapLegacyRequest };
