# Phase 03 — Central financial ledger

## Accounting contract

- The immutable journal under `finance/entries` is the source of truth. Wallet balances under `finance/accounts` are cached projections only.
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

`functions/src/finance/payment-ledger.ts` maps a succeeded, server-loaded `Payment` and its persisted `PaymentAllocation` records into one balanced receipt operation. It validates transaction references, PHP currency, allocation identities and statuses, and ensures allocations do not exceed the payment amount. Pending allocations credit pending balances; ready allocations credit available balances. Any unallocated remainder is held in a payment-specific clearing account until it is allocated or reconciled.

For cash payments, the caller must also supply the persisted `CashCollection` record in `COLLECTED` status. The adapter checks that it matches the payment and records the platform allocation in the collector's `CASH_OWED` balance. A confirmed remittance can then move the owed amount into platform revenue. The ledger rejects a remittance that would make the collector's cash-owed balance negative.

Non-cash collections use a generic payment-provider clearing account. No particular bank or payment gateway is assumed.

## Integration boundary

The backend-only `postPaymentReceiptFromDatabase` service in `functions/src/finance/payment-repository.ts` loads the authoritative records from these Realtime Database paths:

- `payments/{paymentId}`: the `Payment` record.
- `paymentAllocations/{paymentId}/{allocationId}`: persisted allocation records keyed by allocation ID.
- `cashCollections/{paymentId}`: the matching `CashCollection` record for cash payments.

It verifies record IDs against their database paths, then passes the records to the payment adapter and idempotent ledger writer. Do not expose this service as a callable that accepts client-supplied payment objects.

`postSucceededPaymentToLedger` is now a Realtime Database `onValueWritten` trigger on `/payments/{paymentId}/status`. It posts only on a transition into `SUCCEEDED`, reloads payment and allocation records from the database, and uses the journal idempotency key so retries and the cash callable can safely race. For cash, the trigger requires the matching `COLLECTED` evidence. `postPaymentReceiptFromDatabase` remains backend-only and is also called directly after cash collection for immediate feedback.

`functions/src/finance/payment-allocations.ts` now provides tested, quote-backed allocation builders for orders and rides. It accounts for merchant-funded discounts by reducing merchant earnings, platform-funded discounts by reducing platform proceeds, customer-paid provider fees by allocating that fee to the platform, and driver/courier earnings. These builders are deliberately pure and backend-only: they must be called by the trusted order/trip payment creator after it has verified the actual merchant owner, assigned courier/driver, and transaction IDs. The trusted `createOrderFromQuote`, `createRideFromQuote`, and `createDeliveryFromQuote` callables now persist a payment record alongside each quote-created resource. Courier/driver assignment verifies the actual beneficiary and then persists the matching allocation set. The ledger trigger posts only after the payment reaches `SUCCEEDED`; if allocations are not yet available, trigger retries continue, and the assignment operation retries the idempotent posting once the allocations exist. Refund processing, payout execution, and admin-authorized reconciliation remain separate integration work.

The `finance` root transaction is a correctness-first foundation and serializes financial writes. Before high-volume production, shard the journal/projections by account and add a durable outbox/settlement workflow while preserving the immutable operation contract.


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

Provider balances continue to come from the immutable `finance/entries` journal and the cached `finance/accounts` projection. The ledger transaction also maintains a private `finance/providerActivity/{uid}` projection so the earnings callable does not need to scan every operation in the central journal. All payout amounts are integer PHP centavos.

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

The BDO workflow does not connect to BDO APIs or parse statement files automatically; an administrator records each actual statement line. Customer GCash collection uses BDO Checkout as the processor; the payment method stored in Labmda remains `GCASH`, not `BDO`. The current `confirmBdoGcashPayment` callable is a manual verification bridge: an administrator must confirm the exact successful transaction in the BDO merchant portal before the payment is marked `SUCCEEDED`. It is not an automated webhook and must never be invoked based only on a client redirect. BDO's public Website Plug-in page confirms GCash acceptance, but the merchant-specific API and webhook contract must come from BDO after merchant onboarding. Its `balanced` flag is true only when transfer references match expected settlements/paid payouts, no expected inbound settlement or paid payout remains unmatched, and the BDO statement closing balance equals the opening balance plus the period movements posted to `finance/accounts/bdo_bank` from the immutable journal. The statement period must cover all bank lines being reconciled, and every line must be recorded once. The current workflow does not yet automatically ingest BDO files or fetch balances from the bank.

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
