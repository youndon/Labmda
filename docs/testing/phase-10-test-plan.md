# Phase 10 — Testing, reconciliation, and production release

## Automated checks in CI

The Functions package exposes `npm run test:rules`. It starts the Authentication, Realtime Database, and Functions emulators with the repository's emulator configuration, then runs the Realtime Database rules tests.

The automated rules suite covers unauthenticated access, owner-vs-other-user profile reads, participant-scoped order/trip queries, direct client writes to order/payment state, wallet balance writes, and administrator custom-claim access.

## Required payment and marketplace test matrix

The following scenarios remain required before staging or production approval. They are not represented as passing merely because CI compiles the Android app or Functions.

| Area | Required scenarios |
|---|---|
| Checkout | GCash success, provider decline, duplicate request/idempotency key, expired checkout, pricing change after quote |
| Webhooks | valid signature/token, invalid token, duplicate event, delayed event, out-of-order event, unknown reference, replay |
| Orders | GCash food/goods delivery, cash food/goods courier collection, cancellation racing with acceptance/completion |
| Rides | GCash trip, cash trip, passenger cancellation, driver acceptance and completion |
| Refunds | full/partial refund where supported, refund after payment, duplicate refund request, provider failure |
| Cash | collection confirmation once and twice, courier cash remittance, reconciliation mismatch |
| Payouts | merchant/courier/driver destination validation, payout success, failure, retry, duplicate payout prevention |
| Ledger | gross amount, platform commission, merchant share, courier/driver share, refund reversal, cash liability |
| Pricing | admin-only changes, quote snapshot, checkout using the original quote or an explicit re-quote policy |
| Recovery | delayed provider callbacks, retryable function failures, reconciliation of pending/unknown provider states |

## Sandbox and production gates

1. Use Xendit test credentials and provider sandbox destinations only. Never put credentials in the Android app, repository, or Realtime Database.
2. Confirm each provider event is authenticated, deduplicated, and applied only through valid server-side state transitions.
3. Compare the order/trip, payment, ledger, commission, refund, payout, and cash-remittance records after every success and failure case.
4. Validate the complete flow in a staging Firebase project using staging secrets and test accounts.
5. Production credentials, webhook URLs, payout destinations, and rules deployment require a separate explicit release approval.
6. Roll out to a small controlled cohort, monitor webhook/function errors and reconciliation queues, and retain a rollback plan.

## Current limitations

The automated suite in this PR is a Realtime Database rules test suite. It does not call live Xendit APIs, execute provider sandbox payments, prove payout settlement, or replace end-to-end tests for the Kotlin UI and Cloud Functions payment lifecycle. Those checks require configured sandbox credentials, test accounts, and staging infrastructure.
