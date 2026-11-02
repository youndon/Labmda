# Phase 03 — Central financial ledger

## Accounting contract

- The immutable journal under `finance/entries` is the source of truth. Wallet balances under `finance/accounts` are cached projections only.
- All values are integer PHP centavos. Never use floating-point values for ledger arithmetic.
- Each operation must balance to zero across all postings. Negative deltas debit the source; positive deltas credit the destination.
- Every posting names an account, balance bucket, party type, and party ID. Every operation names its event type, business reference, and stable idempotency key.
- Operation, entries, idempotency index, and account projections are committed in one Realtime Database transaction.
- Reusing an idempotency key with the same operation is safe; reusing it with different data is rejected.
- Ledger entries are append-only by API contract. Corrections must be new compensating operations, never edits/deletes.
- Reconciliation rebuilds each account bucket from journal entries and compares it with the cached projection.

## Balance buckets

| Bucket | Meaning |
| --- | --- |
| `PENDING` | Earned/received funds not yet eligible for withdrawal or settlement |
| `AVAILABLE` | Funds eligible for payout |
| `CASH_OWED` | Cash collected by a provider that must be remitted to the platform |
| `CLEARING` | External payment processor, cash-in-transit, or other settlement clearing account |

## Required financial events

- `CUSTOMER_PAYMENT`: record the external payment-provider collection or cash collection against a clearing account.
- `MERCHANT_EARNING`: credit the merchant's pending or available account.
- `DRIVER_EARNING`: credit the driver's pending or available account.
- `COURIER_EARNING`: credit the courier's pending or available account.
- `PLATFORM_REVENUE`: credit the platform revenue account after commissions/discount funding/provider fees are reconciled.
- `CASH_COLLECTION`: move cash from the collector's cash-in-hand obligation to the platform clearing/revenue destination.
- `PAYOUT` and `REFUND`: record actual settlement/refund movements, not merely requested states.

## Integration boundary

This module provides the validated journal, atomic balance projection, idempotency, and reconciliation primitives. It deliberately does not invent order/trip field names or trust client-supplied financial amounts. Payment webhooks, order/trip completion handlers, cash handover confirmation, payout execution, and admin authorization must call this module from trusted Cloud Functions after loading authoritative server-side records.

The `finance` root transaction is a correctness-first foundation and serializes financial writes. Before high-volume production, shard the journal/projections by account and add a durable outbox/settlement workflow while preserving the immutable operation contract.
