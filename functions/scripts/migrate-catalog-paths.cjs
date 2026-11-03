#!/usr/bin/env node
"use strict";

// One-time migration from nested merchant catalogs to isolated top-level collections.
// Run with catalog writers paused and the updated app/backend deployed:
//   npm run migrate:catalog-paths -- --dry-run
//   npm run migrate:catalog-paths -- --apply
// The default is a non-destructive dry run. --apply copies/verifies every item before
// removing its legacy nested collection. Re-running is safe after an interrupted run.

const { initializeApp, applicationDefault } = require("firebase-admin/app");
const { getDatabase } = require("firebase-admin/database");

const projectId = process.env.GCLOUD_PROJECT || process.env.GOOGLE_CLOUD_PROJECT;
const databaseURL = process.env.FIREBASE_DATABASE_URL;
if (!projectId || !databaseURL) {
  throw new Error("Set GCLOUD_PROJECT and FIREBASE_DATABASE_URL before running the catalog migration.");
}
const app = initializeApp({ credential: applicationDefault(), projectId, databaseURL });
const rtdb = getDatabase(app);
const apply = process.argv.includes("--apply");

function safeKey(value, label) {
  if (typeof value !== "string" || !value || /[.#$\[\]/]/.test(value)) {
    throw new Error(`Invalid ${label} in legacy catalog data.`);
  }
  return value;
}
function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]));
  }
  return value;
}
function sameValue(left, right) {
  return JSON.stringify(stable(left)) === JSON.stringify(stable(right));
}

async function migrateMerchantCatalog({ merchantRoot, legacyChild, targetRoot }) {
  const merchants = await rtdb.ref(merchantRoot).get();
  let migrated = 0;
  for (const merchant of merchants.children) {
    const merchantId = safeKey(merchant.key, `${merchantRoot} merchant ID`);
    const legacyRef = rtdb.ref(`${merchantRoot}/${merchantId}/${legacyChild}`);
    const legacy = await legacyRef.get();
    if (!legacy.exists()) continue;

    const targetRef = rtdb.ref(`${targetRoot}/${merchantId}`);
    for (const item of legacy.children) {
      const itemId = safeKey(item.key, `${legacyChild} item ID`);
      const destination = targetRef.child(itemId);
      const current = await destination.get();
      if (current.exists()) {
        if (!sameValue(current.val(), item.val())) {
          throw new Error(`Conflict at ${targetRoot}/${merchantId}/${itemId}; refusing to overwrite or delete legacy data.`);
        }
      } else if (apply) {
        await destination.set(item.val());
      }
      migrated += 1;
    }

    // Only remove the legacy subtree after every item has been copied or verified.
    if (apply) await legacyRef.remove();
    console.log(`${apply ? "Migrated" : "Would migrate"} ${legacy.numChildren()} items: ${merchantRoot}/${merchantId}/${legacyChild} -> ${targetRoot}/${merchantId}`);
  }
  return migrated;
}

async function main() {
  console.log(apply
    ? "APPLY mode: legacy nested catalog nodes will be removed after verification."
    : "DRY RUN: no database values will be changed. Pass --apply to migrate.");
  const meals = await migrateMerchantCatalog({
    merchantRoot: "restaurants", legacyChild: "meals", targetRoot: "restaurantMeals",
  });
  const products = await migrateMerchantCatalog({
    merchantRoot: "shops", legacyChild: "products", targetRoot: "shopProducts",
  });
  console.log(`${apply ? "Migration complete" : "Dry run complete"}: ${meals} meals and ${products} products processed.`);
  if (!apply) console.log("Before applying, pause catalog writes and deploy the app/backend version that uses the new paths.");
}

main().catch((error) => {
  console.error("Catalog migration failed:", error);
  process.exitCode = 1;
});
