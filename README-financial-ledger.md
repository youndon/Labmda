# Phase 03 — Central financial ledger

## Accounting contract

- The immutable journal in Firestore `financialEntries` is the source of truth. Account balances in `financialAccounts` are transactional projections only.
- All values are integer PHP centavos. Never use floating-point values for ledger arithmetic.
- Every operation must balance to zero across all postings. Negative deltas debit the source; positive deltas credit the destination.
- Every posting names an account, balance bucket, party type, and party ID. Every operation names its event type, business reference, and stable idempotency key.
- Operation, entries, idempotency index, and account projections are committed in one Realtime Database transaction.
- Reusing an idempotency key with the same operation is safe; conflicting reuse is rejected.
- Ledger entries are append-only by API contract. Corrections must be new compensating operations, never edits/deletes.
- Reconciliation rebuilds each account bucket from journal entries and compares it with the cached projection.

## Balance buckets

| Bucket | Meaning |
| --- | --- |
| `PENDING` | Earned/received funds not yet eligible for withdrawal or settlement |
| `AVAILABLE` | Funds eligible for payout |
| `CASH_OWED` | Platform amount collected in cash by a provider and still owed to the platform |
| `CLEARING` | External payment collections, cash-in-transit, or unallocated payment amounts |

## Financial events

- `CUSTOMER_PAYMENT`: record a succeeded customer payment and allocate its amount to beneficiary balances or clearing.
- `MERCHANT_EARNING`, `DRIVER_EARNING`, and `COURIER_EARNING`: record beneficiary earnings.
- `PLATFORM_REVENUE`: record platform revenue after commissions, discount funding, and provider fees are reconciled.
- `CASH_COLLECTION`: record a confirmed remittance from a collector's cash-owed balance to platform revenue.
- `PAYOUT` and `REFUND`: record actual settlement/refund movements, not merely requested states.
- `ADJUSTMENT`: record an authorized compensating adjustment; never edit or delete old entries.

## Payment receipt adapter

`functions/src/finance/payment-ledger.ts` maps a succeeded, server-loaded `Payment` and its persisted `PaymentAllocation` records into one balanced receipt operation. It validates transaction references, PHP currency, allocation identities and statuses, and ensures allocations do not exceed the payment amount. Pending allocations credit pending balances; ready allocations credit available balances. Successful GCash allocations are moved from pending to available only after the order is delivered or the ride is completed, using a stable per-allocation idempotency key. Cash payments use the separate cash collection/handover workflow and are not released into provider wallets a second time. Any unallocated remainder is held in a payment-specific clearing account until it is allocated or reconciled.

For cash payments, the caller must also supply the persisted `CashCollection` record in `COLLECTED` status. The adapter checks that it matches the payment and records the platform allocation in the collector's `CASH_OWED` balance. A confirmed remittance can then move the owed amount into platform revenue. The ledger rejects a remittance that would make the collector's cash-owed balance negative.

Non-cash collections use a generic payment-provider clearing account. No particular bank or payment gateway is assumed.

### Phase 07 — earnings-ledger relationship hardening

The receipt adapter additionally enforces beneficiary-to-transaction relationships before creating journal postings: merchant earnings are order-only, driver earnings are ride-only, and courier earnings must match the order's assigned `deliveryId`. For cash orders, the persisted collector must be that assigned courier; for cash rides, driver earnings must belong to the collector. This prevents a structurally balanced but misdirected allocation from crediting an unrelated provider wallet. These checks do not alter the existing account model, payout lifecycle, provider integration, or manual settlement process.

## Integration boundary

The backend-only `postPaymentReceiptFromDatabase` service in `functions/src/finance/payment-repository.ts` loads the authoritative records from these Realtime Database paths:

- `payments/{paymentId}`: the `Payment` record.
- `paymentAllocations/{paymentId}/{allocationId}`: persisted allocation records keyed by allocation ID.
- `cashCollections/{paymentId}`: the matching `CashCollection` record for cash payments.

It verifies record IDs against their database paths, then passes the records to the payment adapter and idempotent ledger writer. Do not expose this service as a callable that accepts client-supplied payment objects.

`postSucceededPaymentToLedger` is now a Realtime Database `onValueWritten` trigger on `/payments/{paymentId}/status`. It posts only on a transition into `SUCCEEDED`, reloads payment and allocation records from the database, and uses the journal idempotency key so retries and the cash callable can safely race. For cash, the trigger requires the matching `COLLECTED` evidence. `postPaymentReceiptFromDatabase` remains backend-only and is also called directly after cash collection for immediate feedback.

`functions/src/finance/payment-allocations.ts` now provides tested, quote-backed allocation builders for orders and rides. It accounts for merchant-funded discounts by reducing merchant earnings, platform-funded discounts by reducing platform proceeds, customer-paid provider fees by allocating that fee to the platform, and driver/courier earnings. These builders are deliberately pure and backend-only: they must be called by the trusted order/trip payment creator after it has verified the actual merchant owner, assigned courier/driver, and transaction IDs. The trusted `createOrderFromQuote`, `createRideFromQuote`, and `createDeliveryFromQuote` callables now persist a payment record alongside each quote-created resource. Courier/driver assignment verifies the actual beneficiary and then persists the matching allocation set. The ledger trigger posts only after the payment reaches `SUCCEEDED`; if allocations are not yet available, trigger retries continue, and the assignment operation retries the idempotent posting once the allocations exist. Refund processing, payout execution, and admin-authorized reconciliation remain separate integration work.

The authoritative journal, account balances, idempotency records and provider activity are stored in Firestore. A single Firestore transaction updates the operation, all postings, affected accounts and provider-scoped activity together. RTDB remains the operational store for orders, trips, payment state and cash evidence.


## Financial Realtime Database security policy

The repository includes `database.financial.rules.fragment.json` and `database.order-ride.rules.fragment.json` as **rules fragments**, not as a complete application ruleset. Together they deny client writes to authoritative orders, trips, payments, allocations, settlements, wallets, cash collections, payouts, refunds, journal entries, provider events, and reconciliation records. Order/trip reads require participant-scoped queries; courier/driver dispatch queries require an approved online service profile. The driver map reads only the public `driverPresence` projection, not all user profiles.

Important deployment constraint:

- The current `firebase.json` configures Cloud Functions only; it does not deploy Realtime Database rules.
- Do not rename either fragment to `database.rules.json`, attach them to `firebase.json`, or deploy them by themselves. Realtime Database denies paths without an applicable grant, so doing so would also block the application's existing non-financial paths.
- When merging the order/ride fragment, remove any ancestor-level `.write` grant on `orders`, `trips`, or `users`; RTDB grants cascade and a broad parent write would override the protected child rules. Keep the user service status fields server-controlled.
- Before deployment, merge these financial rules into the complete application ruleset, review all existing read/query patterns, add rules for the rest of the app's paths, and run Realtime Database Rules Emulator tests.
- Firebase Admin SDK writes from trusted Cloud Functions bypass these client rules. That is the intended server-side write boundary; Cloud Functions must still authenticate and authorize every callable and verify provider evidence.
- No client-visible rule grants access to the private `finance` journal. If the app needs wallet screens, expose a separately designed, user-scoped read model rather than opening the central journal.

This fragment intentionally does not grant an admin bypass: the repository does not yet establish a trusted admin custom-claims contract. Admin review and reconciliation endpoints must be added only after that authorization contract is defined.


## Phase 06 — provider earnings and manual payouts

Provider balances come from Firestore `financialAccounts`; the ledger maintains private `financialProviderActivity/{uid}/operations` documents so provider earnings and analytics do not scan the global journal. All payout amounts are integer PHP centavos.

### Provider identity and wallet accounts

- Merchant wallets use `wallet_merchant_{uid}`. This matches the payment allocation contract: the merchant beneficiary ID is the authenticated merchant owner UID, confirmed by matching `ownerUid` on `shops` and `restaurants`.
- Driver wallets use `wallet_driver_{uid}`.
- Delivery courier wallets use `wallet_delivery_{uid}`.
- The earnings callable aggregates only wallet accounts belonging to the authenticated user. It does not expose the private journal or another provider's balances.

### Payout destination

Each provider can save one payout destination:
- `GCASH`: Philippine mobile number and account-holder name.
- `BDO`: BDO account number and account-holder name.

Sensitive destination data is stored under `payoutProfiles/{uid}` and copied into a payout request so a later profile edit does not change the destination for an already requested payout. Both `payoutProfiles` and `payoutRequests` are server-only in the financial rules fragment. Provider screens should use callable functions rather than direct RTDB reads.

### Payout lifecycle

1. `saveMyPayoutProfile` validates and stores the provider's GCash or BDO destination.
2. `getMyEarnings` returns the provider's available, pending, cash-owed and clearing balances plus the latest 100 matching ledger operations.
3. `requestMyPayout` requires a configured destination, a selected wallet owned by the authenticated user, and a positive PHP-centavo amount within the selected wallet’s available balance. It reserves funds by moving the amount from that wallet's `AVAILABLE` bucket to its `CLEARING` bucket.
4. `getPendingPayoutRequests` is administrator-only and includes requests awaiting approval plus approved or interrupted processing states. `approvePayoutRequest` moves a request to approved.
5. The administrator sends the money outside Labmda using GCash or BDO, then calls `markPayoutAsPaid` with the actual transfer reference. This moves the reserved amount out of the provider wallet's clearing balance and records an external payout-clearing posting.
6. `rejectPayoutRequest` releases the reserved amount back to `AVAILABLE`.
7. `getMyPayoutHistory` returns the provider's own payout records with account numbers masked.

The payout state is locked while rejection or paid confirmation is being posted to the ledger, preventing two administrators from completing competing state transitions. The ledger rejects a payout reservation that would make the provider's available balance negative. Ledger operations use stable payout-specific idempotency keys. Once a payout enters `PAYING`, its saved GCash/BDO transfer reference is immutable; retries may reuse the saved reference but cannot replace it. A `PAYING` record missing its reference fails closed and requires manual review.

### Callable exports

- `saveMyPayoutProfile`
- `getMyPayoutProfile`
- `getMyEarnings`
- `requestMyPayout`
- `getMyPayoutHistory`
- `getPendingPayoutRequests`
- `approvePayoutRequest`
- `rejectPayoutRequest`
- `markPayoutAsPaid`

### Important operational boundary

This phase does not connect to a BDO API, GCash disbursement API, or customer payment gateway. Admins manually send provider payouts through the selected GCash or BDO channel and record the actual transfer reference in Labmda. The app must not mark a payout as paid before the real transfer has been completed. The admin custom claim can be provisioned from a trusted environment with `cd functions && npm run set-admin-claim -- <firebase-auth-uid>` using Application Default Credentials; never expose this operation as a client callable. Before production, merge the financial rules fragment into the complete deployed RTDB ruleset, add rules for every existing app path, run RTDB Rules Emulator tests, configure Android App Check, and verify the callable functions with App Check enforcement and the expected `admin` custom claim.


## Phase 10 — BDO company settlement and bank reconciliation

Provider payouts remain manual until an approved transfer API is integrated. An administrator approves or rejects each provider request; an approved payout is manually sent through GCash or BDO, then recorded with the actual transfer reference through `markPayoutAsPaid`. That callable moves the reserved amount out of the provider clearing balance only after the admin supplies the transfer reference. The BDO statement workflow below is a second check against the actual bank record; it does not initiate transfers.

### BDO settlement workflow

1. When a BDO Checkout/manual settlement is expected to reach the company's BDO account, an administrator calls `createExpectedBdoSettlement` with a stable settlement ID, transfer reference, amount in PHP centavos and source (`BDO` or `MANUAL`).
2. After the credit appears on the actual BDO statement, an administrator calls `recordBdoBankTransfer` with the exact reference, amount, direction `INBOUND`, and bank transaction timestamp.
3. The function matches the statement line to the expected settlement only when both reference and amount match. An unmatched line remains visible for investigation; it is never silently forced to match.
4. For an outbound provider payout, record the actual BDO debit as `OUTBOUND`. It matches only a payout already marked `PAID` with the same transfer reference and amount.
5. `reconcileBdoSettlements` saves an auditable reconciliation snapshot listing unmatched bank transfers, expected settlements not yet received, and paid payouts without a matching BDO debit.

The BDO workflow does not connect to BDO APIs or parse statement files automatically; an administrator records each actual statement line. Customer GCash collection uses BDO Checkout as the processor; the payment method stored in Labmda remains `GCASH`, not `BDO`. The current interim flow uses BDO Checkout Payment Requests: an administrator creates a one-time payment request in the BDO portal, attaches its `checkout.bdo.com.ph` link through `attachBdoCheckoutLink`, and the customer opens it from order/ride tracking. The link is not treated as proof of payment. An administrator must verify the exact successful transaction in BDO and call `confirmBdoGcashPayment` with the reference and exact PHP-centavo amount before the payment is marked `SUCCEEDED`. It is not an automated webhook. BDO's public page confirms GCash acceptance and one-time payment links, but also describes multiple available payment options; confirm with the assigned BDO account officer whether other options can be disabled so the BDO page exposes only GCash. BDO states that Checkout sales are credited to the nominated BDO account in four banking days, so payment success and bank settlement remain separate reconciliation events. The fully automated Website Plug-in API/webhook integration still requires the merchant-specific technical contract from BDO. Its `balanced` flag is true only when transfer references match expected settlements/paid payouts, no expected inbound settlement or paid payout remains unmatched, and the BDO statement closing balance equals the opening balance plus the period movements posted to the `bdo_bank` account in the immutable Firestore journal. The statement period must cover all bank lines being reconciled, and every line must be recorded once. The current workflow does not yet automatically ingest BDO files or fetch balances from the bank.

### BDO callable exports

- `createExpectedBdoSettlement`
- `recordBdoBankTransfer`
- `reconcileBdoSettlements`

All callables require the authenticated admin custom claim and App Check. Statement records, expected settlements and reconciliation snapshots are server-written only. Manual reconciliation remains available while no approved BDO/BDO automated settlement feed is configured.


## Phase 11 — complete financial administration

All monetary values remain integer PHP centavos. The following callable functions are administrator-only and enforce App Check:

- `getAdminPaymentMonitor` returns a bounded newest-first payment sample, status, transaction references, charged amount and remaining refundable amount. It does not return provider credentials or payout account numbers.
- `getAdminFinancialBalances` returns itemized available, pending, cash-owed and clearing balances for merchant, driver, courier, platform and other ledger accounts.
- `getAdminFinancialOverview` remains the compact dashboard summary; use the itemized balance and payment-monitor callables for drill-down screens.
- `getAdminPricingConfig` and `updateAdminPricingConfig` manage ride, delivery, food/shop commission and GCash provider-fee configuration. Updates are version-checked and stored in configuration history.
- `createAdminRefundCase`, `reviewAdminRefundCase`, `getAdminRefundCases` and `completeAdminRefundCase` provide the refund review queue. A refund is completed only after an administrator has actually executed and verified the refund in the provider/bank channel and supplies its transfer reference. Completion atomically updates the payment's refunded amount/status and posts an idempotent proportional reversal of the original payment journal. The reversal uses largest-remainder centavo allocation so partial refunds remain balanced.
- `runAdminFinancialReconciliation` rebuilds account projections from immutable journal entries, saves a durable report and lists account/bucket discrepancies.
- `resolveAdminFinancialDiscrepancy` posts a balanced two-sided adjustment between specified ledger accounts/buckets. It requires a unique reference ID and an explanatory note; it never edits or deletes historical journal entries.
- `getAdminAuditTrail` returns the latest 200 administrative audit events.

Pricing changes, payout approval/rejection/paid confirmation, cash remittance/reconciliation, BDO settlement/reconciliation, refund decisions/completions, discrepancy adjustments and financial reconciliation runs write append-only records to `adminAuditLogs`. The audit trail stores actor UID, action, target, timestamp, and relevant before/after details. Financial admin records and audit logs must remain server-only in the complete deployed RTDB ruleset.

### Refund and provider boundary

Labmda currently has no production BDO Checkout refund API integration. The refund workflow is therefore a controlled manual-provider workflow: verify the actual refund in the provider/bank dashboard first, then record its reference in Labmda. The ledger reversal is idempotent and records the liability reversal against the original payment allocations. It does not initiate the external refund. Do not expose the development-only simulated GCash checkout/refund callables as production payment processing.

### Reconciliation boundary

The ledger reconciliation compares immutable journal entries with cached account-balance projections. Cash and BDO statement reconciliation remain separate reports because they also need physical collection/remittance or bank-statement evidence. A balanced ledger projection alone does not prove that the external provider or bank settled funds. Resolve every reported discrepancy with source evidence and a documented balanced adjustment; never directly edit finance account balances.


## Phase 10 — refunds and financial edge cases

Refund cases are administrator-controlled and use integer PHP centavos:

1. `createAdminRefundCase` reserves the requested amount against a succeeded or partially refunded payment. The reservation prevents multiple open cases from exceeding the remaining refundable amount.
2. `reviewAdminRefundCase` approves or rejects the case. Rejection releases its reservation.
3. The administrator sends the refund externally through the applicable bank/provider channel, then `completeAdminRefundCase` records the actual provider/bank reference and posts an immutable proportional reversal of the original payment journal.
4. The payment becomes `PARTIALLY_REFUNDED` or `REFUNDED` only after the reversal is posted and the payment/case transaction commits.

Completion is serialized per payment so two partial refunds cannot complete concurrently against the same payment snapshot. The external refund reference is indexed by a normalized SHA-256 digest and cannot be reused for a different refund case. A retry of the same processing case must use the same reference; its ledger operation is idempotent. If posting fails after the case enters `PROCESSING`, retry that same case and reference after resolving the underlying ledger issue—do not create another case or invent a second reference.

Refund recording does not call BDO or GCash and does not prove that money was sent. The admin must verify the external refund before recording completion. Development-only simulated refunds remain isolated from the production financial journal.

Refunds that would violate a ledger invariant (for example, reversing a cash-owed balance that has already been remitted) must fail closed and be reviewed as a manual accounting exception. Do not force a negative cash-owed balance or edit/delete existing journal entries; resolve such cases through a documented, balanced compensating operation after confirming the real cash movement.


## Firestore financial-ledger deployment and migration

The financial journal has moved from the RTDB `finance` root to server-only Firestore collections:

- `financialOperations/{operationId}`: immutable balanced operation and its postings.
- `financialEntries/{entryId}`: immutable per-account posting rows.
- `financialAccounts/{accountId}`: transactionally maintained balance projections.
- `financialIdempotency/{idempotencyKey}`: unique operation key.
- `financialProviderActivity/{uid}/operations/{operationId}`: private, user-scoped provider history.

All amounts remain integer PHP centavos. Firestore transactions read the idempotency and affected account documents before writing; concurrent debits are retried against the latest account state. Each operation is limited to 100 postings so its transaction remains below Firestore's document-write ceiling. Reconciliation reads immutable entries in 500-document pages rather than downloading the full RTDB tree. Firestore client rules deny all direct reads and writes; trusted Cloud Functions use the Admin SDK and continue to enforce callable authorization.

### Required cutover

1. Create/enable the Firestore database in the Firebase project and deploy the Firestore rules.
2. Schedule a maintenance window. Pause financial writes and payment, cash-collection, payout, refund and settlement processing while the snapshot is taken.
3. Set `GCLOUD_PROJECT`, `FIREBASE_DATABASE_URL`, and ADC credentials, then run `cd functions && npm run migrate:financial-ledger`. The script refuses to overwrite non-empty target collections and verifies migrated document counts.
4. Confirm the migration counts against the legacy RTDB finance data and run `npm run test:emulator` plus `npm run build`.
5. Deploy the Functions build from this branch, run the admin financial reconciliation and provider/cash balance checks, then resume financial writes.

Do not deploy the new Functions before importing existing ledger data: the new writer intentionally does not fall back to RTDB. Keep the legacy RTDB `finance` data unchanged as a rollback archive until the new ledger has been reconciled and production sign-off is complete. The migration script is a one-time snapshot, not a live dual-write or automatic rollback mechanism.
