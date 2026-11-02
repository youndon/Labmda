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

The service is intentionally not wired to a database trigger or callable yet: the repository does not currently contain a trusted payment verification/webhook flow or an agreed payment-write lifecycle. It must only be invoked after a trusted collection verifier has written `SUCCEEDED` (and, for cash, a matching `COLLECTED` record). It does not create payment sessions, verify external transactions, receive webhooks, execute payouts, or authorize administrators.

This PR does not yet invoke the adapter from live order/trip lifecycle handlers or cash handover flows. Refund processing, payout execution, and admin-authorized reconciliation also remain separate integration work.

The `finance` root transaction is a correctness-first foundation and serializes financial writes. Before high-volume production, shard the journal/projections by account and add a durable outbox/settlement workflow while preserving the immutable operation contract.


## Financial Realtime Database security policy

The repository includes `database.financial.rules.fragment.json` as a **financial-path rules fragment**, not as a complete application ruleset. It denies client writes to authoritative payment, allocation, settlement, wallet, legacy ledger, cash collection, payout, refund, journal, provider-event, handover, and reconciliation records. Read access is scoped to the payer, beneficiary, owner, requester, or cash collector represented by each record.

Important deployment constraint:

- The current `firebase.json` configures Cloud Functions only; it does not deploy Realtime Database rules.
- Do not rename this fragment to `database.rules.json`, attach it to `firebase.json`, or deploy it by itself. Realtime Database denies paths without an applicable grant, so doing so would also block the application's existing non-financial paths.
- Before deployment, merge these financial rules into the complete application ruleset, review all existing read/query patterns, add rules for the rest of the app's paths, and run Realtime Database Rules Emulator tests.
- Firebase Admin SDK writes from trusted Cloud Functions bypass these client rules. That is the intended server-side write boundary; Cloud Functions must still authenticate and authorize every callable and verify provider evidence.
- No client-visible rule grants access to the private `finance` journal. If the app needs wallet screens, expose a separately designed, user-scoped read model rather than opening the central journal.

This fragment intentionally does not grant an admin bypass: the repository does not yet establish a trusted admin custom-claims contract. Admin review and reconciliation endpoints must be added only after that authorization contract is defined.


## Phase 06 — provider earnings and manual payouts

Provider balances continue to come from the immutable `finance/entries` journal and the cached `finance/accounts` projection. All payout amounts are integer PHP centavos.

### Provider identity and wallet accounts

- Merchant wallets use `wallet_merchant_{merchantId}`. The signed-in user is associated with merchant IDs by matching `ownerUid` on `shops` and `restaurants`.
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
3. `requestMyPayout` requires a configured destination and a minimum request of PHP 100.00. It reserves funds by moving the amount from the provider wallet's `AVAILABLE` bucket to its `CLEARING` bucket.
4. `getPendingPayoutRequests` is administrator-only. `approvePayoutRequest` moves a request to approved.
5. The administrator sends the money outside Labmda using GCash or BDO, then calls `markPayoutAsPaid` with the actual transfer reference. This moves the reserved amount out of the provider wallet's clearing balance and records an external payout-clearing posting.
6. `rejectPayoutRequest` releases the reserved amount back to `AVAILABLE`.
7. `getMyPayoutHistory` returns the provider's own payout records with account numbers masked.

The payout state is locked while rejection or paid confirmation is being posted to the ledger, preventing two administrators from completing competing state transitions. The ledger rejects a payout reservation that would make the provider's available balance negative. Ledger operations use stable payout-specific idempotency keys.

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

This phase does not connect to a BDO API, GCash disbursement API, payment gateway, or Xendit. Admins manually send the transfer and record its reference in Labmda. The app must not mark a payout as paid before the real transfer has been completed. Before production, merge the financial rules fragment into the deployed RTDB ruleset and verify that the callable functions are deployed with App Check enforcement and the expected `admin` custom claim.
