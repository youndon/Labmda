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
7. Refunds, payouts, settlements, wallet balances, payment confirmations, and ledger entries are written by trusted backend code.\n8. Customer payment methods are `GCASH` and `CASH`; BDO Checkout is the GCash processor, never a customer-facing payment method.

The repository uses BDO Checkout Payment Requests as a manual interim GCash flow: an admin attaches a one-time BDO link and verifies the paid transaction in the BDO portal. There is no Xendit integration. Automated BDO Website Plug-in API/session creation and webhook verification are not implemented until BDO supplies its merchant-specific integration contract. Quote-backed order, ride and standalone delivery creation, cash collection, payment allocations and ledger posting are implemented in trusted Firebase Functions.

The `domain` module now applies the Kotlin serialization plugin and depends on `kotlinx-serialization-json` so the `@Serializable` models can be encoded.
