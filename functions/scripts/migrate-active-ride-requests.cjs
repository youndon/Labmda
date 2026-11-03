#!/usr/bin/env node
"use strict";

// One-time backfill for trips that were already active before passenger-scoped
// driver presence was deployed. Default is dry-run; pass --apply to write.
//   npm run migrate:active-ride-requests -- --dry-run
//   npm run migrate:active-ride-requests -- --apply

const { initializeApp, applicationDefault } = require("firebase-admin/app");
const { getDatabase } = require("firebase-admin/database");

const projectId = process.env.GCLOUD_PROJECT || process.env.GOOGLE_CLOUD_PROJECT;
const databaseURL = process.env.FIREBASE_DATABASE_URL;
if (!projectId || !databaseURL) {
  throw new Error("Set GCLOUD_PROJECT and FIREBASE_DATABASE_URL before running the active-ride migration.");
}
const app = initializeApp({ credential: applicationDefault(), projectId, databaseURL });
const rtdb = getDatabase(app);
const apply = process.argv.includes("--apply");
const activeStatuses = new Set(["REQUESTED", "ACCEPTED", "IN_PROGRESS"]);

function safeKey(value, label) {
  if (typeof value !== "string" || !value || /[.#$\[\]/]/.test(value)) {
    throw new Error(`Invalid ${label} in trip data.`);
  }
  return value;
}

async function main() {
  const trips = await rtdb.ref("trips").get();
  const updates = {};
  let count = 0;
  trips.forEach((snapshot) => {
    const trip = snapshot.val() || {};
    if (!activeStatuses.has(String(trip.status || ""))) return;
    const tripId = safeKey(snapshot.key, "trip ID");
    const passengerId = safeKey(trip.passengerId, "passenger ID");
    updates[`activeRideRequests/${passengerId}/${tripId}`] = {
      status: trip.status,
      updatedAt: Number.isSafeInteger(trip.updatedAtTimestamp) ? trip.updatedAtTimestamp : Date.now(),
    };
    count += 1;
  });

  console.log(apply
    ? `Applying active ride access backfill for ${count} trip(s).`
    : `DRY RUN: would backfill ${count} active trip(s); no database values changed.`);
  if (apply && count) await rtdb.ref().update(updates);
  if (!apply) console.log("Run with --apply after deploying the new Functions and complete RTDB ruleset.");
}

main().catch((error) => {
  console.error("Active ride access migration failed:", error);
  process.exitCode = 1;
});
