#!/usr/bin/env node
"use strict";

const { initializeApp, applicationDefault } = require("firebase-admin/app");
const { getDatabase } = require("firebase-admin/database");
const { getFirestore } = require("firebase-admin/firestore");

const projectId = process.env.GCLOUD_PROJECT || process.env.GOOGLE_CLOUD_PROJECT;
const databaseURL = process.env.FIREBASE_DATABASE_URL;
if (!projectId || !databaseURL) {
  throw new Error("Set GCLOUD_PROJECT and FIREBASE_DATABASE_URL before running the financial ledger migration.");
}
const app = initializeApp({ credential: applicationDefault(), projectId, databaseURL });
const rtdb = getDatabase(app);
const firestore = getFirestore(app);

function chunks(values, size) {
  const result = [];
  for (let index = 0; index < values.length; index += size) result.push(values.slice(index, index + size));
  return result;
}
function safeId(value, label) {
  if (typeof value !== "string" || !value || value.includes("/")) throw new Error(`Invalid ${label} in legacy financial data.`);
  return value;
}
async function assertEmpty(collection) {
  const snapshot = await firestore.collection(collection).limit(1).get();
  if (!snapshot.empty) throw new Error(`Firestore collection ${collection} is not empty. Refusing to overwrite existing financial data.`);
}
async function writeDocuments(collection, values) {
  for (const group of chunks(values, 400)) {
    const batch = firestore.batch();
    for (const [id, value] of group) batch.create(firestore.collection(collection).doc(id), value);
    await batch.commit();
  }
}
async function main() {
  // Freeze financial writers and payment/payout/refund processing before running.
  // This is a one-time snapshot import, not a live dual-write migration.
  const [financeSnapshot] = await Promise.all([rtdb.ref("finance").get()]);
  if (!financeSnapshot.exists()) throw new Error("RTDB finance root is empty; nothing to migrate.");
  const finance = financeSnapshot.val() || {};
  const operations = Object.entries(finance.operations || {}).map(([id, value]) => {
    safeId(id, "operation ID");
    return [id, value];
  });
  const entries = Object.entries(finance.entries || {}).map(([id, value]) => {
    safeId(id, "entry ID");
    return [id, value];
  });
  const accounts = Object.entries(finance.accounts || {}).map(([id, value]) => {
    safeId(id, "account ID");
    const marker = id.startsWith("cash_owed_") ? id.lastIndexOf("_merchant_") : -1;
    const collectorId = id.startsWith("cash_owed_") ? id.slice("cash_owed_".length, marker > 0 ? marker : undefined) : undefined;
    const merchantBeneficiaryId = marker > 0 ? id.slice(marker + "_merchant_".length) : undefined;
    return [id, { accountId: id, ...value, ...(collectorId ? { collectorId } : {}), ...(merchantBeneficiaryId ? { merchantBeneficiaryId } : {}) }];
  });
  const balanceFields = { AVAILABLE: "availableMinor", PENDING: "pendingMinor", CASH_OWED: "cashOwedMinor", CLEARING: "clearingMinor" };
  const rebuilt = new Map();
  for (const [entryId, entry] of entries) {
    if (!entry || !balanceFields[entry.bucket] || !Number.isSafeInteger(entry.deltaMinor) || typeof entry.accountId !== "string") {
      throw new Error(`Invalid legacy journal entry ${entryId}; migration stopped before writing.`);
    }
    const current = rebuilt.get(entry.accountId) || { availableMinor: 0, pendingMinor: 0, cashOwedMinor: 0, clearingMinor: 0 };
    const key = balanceFields[entry.bucket];
    current[key] += entry.deltaMinor;
    if (!Number.isSafeInteger(current[key])) throw new Error(`Legacy journal overflow for account ${entry.accountId}.`);
    rebuilt.set(entry.accountId, current);
  }
  const accountMap = new Map(accounts.map(([id, value]) => [id, value]));
  for (const [id, value] of accountMap) {
    const computed = rebuilt.get(id) || { availableMinor: 0, pendingMinor: 0, cashOwedMinor: 0, clearingMinor: 0 };
    for (const key of Object.values(balanceFields)) {
      if (!Number.isSafeInteger(value[key] || 0) || (value[key] || 0) !== computed[key]) {
        throw new Error(`Legacy account projection mismatch for ${id}.`);
      }
    }
  }
  for (const [id, computed] of rebuilt) {
    if (!accountMap.has(id)) {
      if (Object.values(computed).some((amount) => amount !== 0)) throw new Error(`Journal account ${id} has no cached account projection.`);
    }
  }

  const idempotency = Object.entries(finance.idempotency || {}).map(([id, operationId]) => {
    safeId(id, "idempotency key");
    return [id, { operationId, migratedAt: Date.now() }];
  });
  const activity = [];
  for (const [uid, records] of Object.entries(finance.providerActivity || {})) {
    safeId(uid, "provider UID");
    for (const [operationId, operation] of Object.entries(records || {})) {
      safeId(operationId, "provider activity operation ID");
      activity.push([uid, operationId, operation]);
    }
  }

  for (const collection of ["financialOperations", "financialEntries", "financialAccounts", "financialIdempotency", "financialProviderActivity"]) {
    await assertEmpty(collection);
  }
  const providerUids = [...new Set(activity.map(([uid]) => uid))];
  for (const uid of providerUids) {
    const existing = await firestore.collection("financialProviderActivity").doc(uid).collection("operations").limit(1).get();
    if (!existing.empty) throw new Error(`Provider activity for ${uid} already exists. Refusing to overwrite Firestore data.`);
  }
  await writeDocuments("financialOperations", operations);
  await writeDocuments("financialEntries", entries);
  await writeDocuments("financialAccounts", accounts);
  await writeDocuments("financialIdempotency", idempotency);
  for (const group of chunks(activity, 400)) {
    const batch = firestore.batch();
    for (const [uid, operationId, operation] of group) {
      batch.create(firestore.collection("financialProviderActivity").doc(uid).collection("operations").doc(operationId), operation);
    }
    await batch.commit();
  }

  const [operationCount, entryCount, accountCount, keyCount, activityCounts] = await Promise.all([
    firestore.collection("financialOperations").count().get(),
    firestore.collection("financialEntries").count().get(),
    firestore.collection("financialAccounts").count().get(),
    firestore.collection("financialIdempotency").count().get(),
    Promise.all(providerUids.map((uid) => firestore.collection("financialProviderActivity").doc(uid).collection("operations").count().get())),
  ]);
  const activityCount = activityCounts.reduce((sum, snapshot) => sum + snapshot.data().count, 0);
  const counts = {
    operations: operationCount.data().count,
    entries: entryCount.data().count,
    accounts: accountCount.data().count,
    idempotencyKeys: keyCount.data().count,
    providerActivityRecords: activityCount,
  };
  if (counts.operations !== operations.length || counts.entries !== entries.length || counts.accounts !== accounts.length
    || counts.idempotencyKeys !== idempotency.length || counts.providerActivityRecords !== activity.length) {
    throw new Error("Firestore migration count verification failed. Keep financial writes paused and investigate.");
  }
  console.log(JSON.stringify({ status: "MIGRATED", projectId, counts }, null, 2));
  console.log("Keep RTDB finance data unchanged as a rollback archive. Deploy the Firestore-ledger Functions build before resuming financial writes.");
}
main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
}).finally(async () => {
  await app.delete();
});
