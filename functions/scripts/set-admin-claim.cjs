#!/usr/bin/env node
"use strict";

const { initializeApp, applicationDefault, getApps } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");

async function main() {
  const uid = process.argv[2];
  if (!uid || !/^[A-Za-z0-9_-]{1,128}$/.test(uid)) {
    throw new Error("Usage: npm run set-admin-claim -- <firebase-auth-uid>");
  }

  const app = getApps()[0] ?? initializeApp({ credential: applicationDefault() });
  const auth = getAuth(app);
  const user = await auth.getUser(uid);
  const existingClaims = user.customClaims ?? {};

  if (existingClaims.admin === true) {
    process.stdout.write(`Admin claim is already enabled for UID ${uid}.\\n`);
    return;
  }

  await auth.setCustomUserClaims(uid, { ...existingClaims, admin: true });
  process.stdout.write(
    `Enabled the trusted admin custom claim for UID ${uid}. The user must refresh their ID token before admin callables recognize it.\\n`,
  );
}

main().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\\n`);
  process.exitCode = 1;
});
