# Labmda payment foundation models

Provider-independent model definitions for Phase 01.

## Files
- `domain/src/main/java/com/zouitel/domain/model/PaymentModels.kt` — Kotlin serializable domain models.
- `functions/src/models/payment.ts` — TypeScript interfaces, unions, and basic invariant guards.

## Core invariants
1. Store money as integer PHP centavos (`₱1.00 = 100`); never use floating-point values for payment accounting.
2. Every payment belongs to exactly one transaction: an order or a ride trip.
3. An order payment may also reference its assigned `deliveryId`; a ride payment must not.
4. Payment allocations describe intended distribution; settlements record actual settlement outcomes.
5. Wallet balances are cached projections; the append-only ledger is the accounting source of truth.
6. A CASH payment remains `PENDING` until its `CashCollection` is confirmed, then its payment status may become `SUCCEEDED`.
7. Refunds, payouts, settlements, wallet balances, and ledger entries must be written by trusted backend code in later phases.

No payment provider SDK, API calls, Firebase writes, or checkout logic are included.

The `domain` module now applies the Kotlin serialization plugin and depends on `kotlinx-serialization-json` so the `@Serializable` models can be encoded.
