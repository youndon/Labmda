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
