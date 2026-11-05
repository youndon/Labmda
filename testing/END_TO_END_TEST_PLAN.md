# Phase 07 — End-to-end testing plan

This phase validates Labmda as a complete marketplace and transport system, not just isolated screens or callable functions.

## Safety boundary

- Use a dedicated **development Firebase project** and test accounts. Never use production customer data or production payout destinations.
- Use test merchant, courier, driver, and passenger accounts with controlled PHP-centavo balances and known test locations.
- Cash scenarios use small, explicitly agreed test amounts and must be reconciled as test collections.
- Online GCash scenarios may be called a provider integration test only when BDO has supplied an approved sandbox/test merchant account and test instructions. Do not use a real customer's GCash wallet for routine CI.
- The repository currently uses manually generated BDO checkout links and administrator verification. Unit/emulator tests can validate Labmda's payment confirmation rules, but cannot prove BDO actually collected, settled, or refunded funds.
- Never mark a real payment successful in production just to make a test pass. Never issue a live refund or payout as part of automated CI.

## 1. Automated test layers

### Pull request CI

- Android: `./gradlew assembleDevelopmentDebug`
- Android unit tests: `./gradlew testDevelopmentDebugUnitTest`
- Functions unit tests: `npm --prefix functions test`
- Functions build: `npm --prefix functions run build`
- Realtime Database and Firestore emulator suite: `firebase --config firebase.emulator.json emulators:exec --only database,firestore --project demo-labmda "npm --prefix functions run test:emulator"`

The emulator suite covers quote claiming, cash collection, simulated refund transaction safety, financial ledger concurrency/reconciliation, and the payment-to-order/ride state projections. The new payment projection tests assert FOOD, RETAIL/SHOP, standalone DELIVERY and RIDE behavior. The RTDB emulator configuration is `firebase.emulator.json`, which explicitly points to `testing/database.emulator.rules.json`. Those deny-all rules deny client reads and writes while Admin SDK test code exercises trusted server paths; do not deploy this test-only rules file as the application's production RTDB rules.

These tests exercise backend state transitions. They do not replace UI automation or physical-device tests.

## 2. Test identities and fixtures

Create these accounts only in the development Firebase project:

| Role | Test account | Required setup |
|---|---|---|
| Customer | `e2e_customer` | Verified auth, profile, delivery address, CASH and GCash options as enabled |
| Restaurant owner | `e2e_restaurant_owner` | Owns one active restaurant and test menu |
| Shop owner | `e2e_shop_owner` | Owns one active shop and test products |
| Courier | `e2e_courier` | Approved delivery profile, availability enabled, verified payout destination |
| Driver | `e2e_driver` | Approved driver profile, vehicle, availability enabled, verified payout destination |
| Administrator | `e2e_admin` | Admin custom claim, App Check-valid device, financial audit access |

Use unique order/trip IDs per test run. Keep pricing in PHP and amounts in integer centavos in backend fixtures. Seed admin-controlled delivery and ride pricing, provider fee settings, commissions, merchant-funded and platform-funded discount examples, and realistic test pickup/drop-off points.

Do not use the same account as both sides of a role-based permission test. Record the Firebase project ID, app build variant, app version, device model, Android version, test account role, and test run timestamp with every result.

## 3. Food order journey

1. Customer signs in and opens an active restaurant.
2. Load menu, select multiple items, adjust quantities, and apply a valid/invalid discount code.
3. Request a server-generated quote and confirm that the total, delivery fee, discount funding, provider fee and PHP currency match the server quote.
4. Place a CASH order; verify the order appears once in customer and restaurant views.
5. Restaurant accepts, prepares, and marks the order ready for pickup using allowed state transitions.
6. Courier accepts/receives the delivery and completes handoff.
7. Customer confirms receipt if the flow requires it.
8. Verify cash collection, courier cash-owed/retained accounting, merchant earnings, platform commission, and balanced ledger.
9. Repeat using GCash only with an approved BDO sandbox/test checkout. Verify pending checkout, success confirmation, duplicate callback/confirmation idempotency, allocation release, and settlement evidence.
10. Test customer cancellation before preparation, cancellation after preparation (must be rejected where the workflow disallows it), payment expiry, and refund request/approval/rejection.

## 4. Shop order journey

1. Customer opens the shop, loads products, and adds multiple products to cart.
2. Verify inventory/availability, quantity limits, quote totals, discounts and delivery fee.
3. Place a CASH shop order and verify customer/shop order views.
4. Shop accepts and prepares the order; courier receives and completes the delivery.
5. Verify customer receipt, collection confirmation, merchant and courier earnings, cash settlement, and ledger reconciliation.
6. Repeat the online payment path only with BDO-issued test credentials; test amount mismatch, duplicate reference, failed/expired checkout, and a refund.
7. Verify a shop owner cannot access another shop's orders or financial records.

## 5. Standalone delivery journey

1. Customer enters valid pickup and drop-off locations.
2. Confirm server-generated road distance/ETA and admin-configured PHP/km pricing; edit client-side totals and verify the server ignores tampering.
3. Request a delivery quote and confirm the courier/platform allocation.
4. Place a CASH delivery request and verify it appears in courier dispatch.
5. Courier accepts, travels to pickup, marks pickup, travels to drop-off, and completes delivery.
6. Verify the customer sees each status update and the courier cannot complete another courier's job.
7. Confirm cash collection and courier cash settlement; reconcile the ledger.
8. Test cancellation, unavailable courier, stale quote, duplicate completion, and offline/reconnect behavior.

## 6. Passenger ride journey

1. Passenger requests a server-generated ride quote using pickup/drop-off coordinates and selected ride type.
2. Confirm road route distance, ETA, PHP fare, fees, and quote expiry.
3. Passenger confirms CASH or an approved BDO sandbox GCash checkout.
4. Verify a trip is not dispatched while an online payment is pending.
5. After successful payment, verify exactly one active ride request becomes visible to eligible drivers.
6. Driver accepts, starts the ride, and completes it through allowed server-side transitions.
7. Verify passenger and driver see consistent state, fare, and payment state.
8. Verify driver earnings, platform commission, and cash-owed/retained accounting where applicable.
9. Test declined/expired payment, passenger cancellation before/after acceptance, driver cancellation/reassignment, duplicate status requests, and reconnect recovery.

## 7. Cross-journey negative and resilience tests

- Unauthenticated requests and users with the wrong role cannot read or mutate another user's transaction.
- Client-supplied amount, commission, beneficiary, provider reference, payment status, trip status, and payout status are ignored or rejected when not server-authoritative.
- App Check-invalid callable requests are rejected.
- Duplicate taps, retries, and repeated webhook/admin confirmation evidence do not create duplicate orders, trips, collections, ledger postings, refunds, or payouts.
- Amount/currency mismatches fail closed.
- A pending BDO checkout is not treated as paid; customer cancellation is blocked until the checkout is verified expired because no revoke API is available.
- Network loss during submission does not create duplicate transactions after retry.
- Reinstall/sign-out/sign-in does not expose another user's cached order, trip, or payment data.
- Notifications and deep links open only the matching authorized transaction.
- Validate accessibility basics: readable text, touch targets, loading/empty/error states, and TalkBack labels on core actions.

## 8. Real-device execution matrix

Run the checklist on at least:

- One current supported Android device on a stable network.
- One lower-memory/older supported Android device.
- One device on mobile data with intermittent connectivity.
- Separate customer, merchant, courier, driver and administrator sessions. Do not switch roles within one shared app session during permission tests.

For each journey, capture:
- Screen recording or screenshots with personal data redacted.
- Relevant Cloud Functions logs and correlation IDs.
- Before/after order, trip, payment, allocation and ledger state from the development project.
- BDO sandbox transaction/reference evidence when the provider test environment is available.
- Expected result, actual result, pass/fail, defect ID, and tester.

Real-device tests are manual until an Android UI automation harness and stable test-account provisioning are added. Do not describe the manual matrix as completed just because CI is green.

## 9. Release acceptance gates

A journey passes only when both the customer/provider UI and server records agree.

- [ ] Food CASH journey passes end-to-end.
- [ ] Food GCash sandbox journey passes, including verified success and expiry/failure.
- [ ] Shop CASH journey passes end-to-end.
- [ ] Shop GCash sandbox journey passes, including verified success and refund evidence.
- [ ] Standalone delivery CASH journey passes end-to-end.
- [ ] Ride CASH journey passes end-to-end.
- [ ] Ride GCash sandbox journey passes, including pending-before-dispatch and success-before-dispatch.
- [ ] Role and App Check negative tests pass.
- [ ] Duplicate/retry, offline/reconnect, cancellation, expiry, refund and reconciliation tests pass.
- [ ] Financial ledger reconciliation is balanced with no unexplained discrepancies.
- [ ] No production payment, refund or payout was used to satisfy a test.
- [ ] Test evidence is attached to the release record and unresolved P0/P1 defects are closed.

## Current limitation

This PR adds the automated Firebase emulator test lane and a repeatable real-device test plan. It does **not** claim that physical devices have been tested or that BDO sandbox transactions have been executed. Those require your devices, test accounts, and BDO's approved test credentials.
