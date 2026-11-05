# Phase 11 — Final security hardening

This phase closes the Android App Check initialization gap and adds a regression
test for callable App Check/admin boundaries. It does **not** claim that the
Realtime Database rules are production-ready; the repository still lacks a
complete application ruleset.

## Android App Check

- LambdaApplication initializes the GitLive Firebase app, then installs App
  Check before KMPAuth or application Firebase services are initialized.
- Release variants install the Play Integrity provider.
- Debug variants install the Firebase App Check debug provider. The debug SDK
  is a debugImplementation dependency and is not packaged in release variants.
- No debug token is stored in source control. Obtain a token from Logcat on a
  debug device and register it in Firebase Console > App Check > Apps >
  Manage debug tokens. Treat the token as a secret and revoke it if exposed.

Before production enforcement:
1. Link the Android app to the correct Google Play Console cloud project.
2. Register the Android app in Firebase App Check using the SHA-256 signing
   certificate fingerprint(s) used for distribution.
3. Confirm Play Integrity configuration matches the intended distribution
   channel (Google Play, outside Play, or both).
4. Install and test a release-signed build; confirm valid App Check requests
   reach the callable functions.
5. Review App Check metrics before enabling enforcement for each Firebase
   product. Callable functions already declare enforceAppCheck: true; the
   Android release provider must be configured before legitimate clients can
   call them successfully.

## Callable authorization

functions/src/security/callable-security.test.ts scans TypeScript source
for callable declarations and fails if a callable does not explicitly enable
App Check. It also protects the admin custom-claim guard from accidental
removal in the privileged payment and finance modules.

Admin authorization is based on the trusted Firebase Authentication custom
claim admin === true. Never infer admin rights from a client-writable
Realtime Database profile field.

Provider onboarding and approval callables are exported from
`functions/src/operations/provider-approvals.ts`. Restaurant and shop
applications are created server-side in PENDING state; provider status
changes require the admin custom claim. Food/shop manager modes are only
granted by a callable after the corresponding approval status is APPROVED.
The Android Panel tab is hidden unless the refreshed ID token contains
`admin === true`; this is a presentation gate, not a substitute for backend
authorization.

The order/ride rules fragment now additionally marks `foodStatus`,
`foodMode`, `shopStatus`, `shopMode`, and `freightStatus` as server-owned,
and protects status fields under `shops` and `restaurants`. These rules are
still only a fragment and are not deployable until merged into the complete
application ruleset. Provision or revoke the claim only from a
trusted administrative environment using the existing
functions/scripts/set-admin-claim.cjs workflow and appropriate
Firebase Admin credentials.

## Server-owned financial data

Clients must not write payment records, payment allocations, provider evidence,
wallet/account balances, ledger entries, payout states, refund states, cash
collection/remittance confirmations, pricing configuration, payment
configuration, or admin audit/reconciliation records. Mutations must pass
through authenticated, App Check-protected Cloud Functions with participant or
admin authorization and server-side validation.

GCash remains a manual BDO Checkout link plus administrator verification until
BDO supplies and approves a merchant API/webhook contract. Cash confirmation,
manual payouts, refunds and bank reconciliation record evidence; they do not
send money or independently prove an external transfer. Simulated GCash
sessions/refunds remain emulator-only.

## Realtime Database deployment blocker — not resolved by this phase

The repository currently has:
- database.order-ride.rules.fragment.json — an order/ride rules fragment.
- database.financial.rules.fragment.json — a financial rules fragment.
- No database.rules.json complete application ruleset.
- firebase.json intentionally has no RTDB rules deployment mapping. The deny-all emulator rules are isolated in firebase.emulator.json and must never be deployed to a live database.

Do not attach either fragment to firebase.json or deploy it as a complete
ruleset. Doing so would deny access to application paths not covered by the
fragments; adding broad parent grants to compensate could reopen protected
financial/order paths because RTDB grants cascade.

A separate rules-integration task must inventory every existing app read/query
and write path, including login profile creation/update and the complete
deliveryServices registration payload. The current fragment only grants a
small subset of child fields, so broad profile updateChildren/setValue calls
may be denied. Merge the fragments into a complete least-privilege ruleset,
remove ancestor grants that override protected children, and run Realtime
Database Rules Emulator tests for both allowed and denied operations before
configuring RTDB rules deployment. Until that is complete, **production RTDB
rules deployment and production launch remain blocked**.

## Verification

CI runs:
- Android assembleDevelopmentDebug and testDevelopmentDebugUnitTest (the production flavor requires its own Firebase config).
- Firebase Functions TypeScript build and Vitest suite, including the callable
  security regression test.

Firebase Console configuration (Play Integrity app registration, signing
fingerprints, debug-token allowlist and enforcement toggles) remains an
operator action and cannot be completed by a repository commit.
