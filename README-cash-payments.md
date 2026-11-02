# Phase 04 — Cash payment lifecycle

## Supported service mapping

All amounts are integer PHP centavos. Cash does not call a bank or payment gateway.

| App service | Payment transaction | Cash collector | Earnings allocations |
| --- | --- | --- | --- |
| Food orders | `ORDER` | Assigned courier (`deliveryId`) | Merchant, courier, platform |
| Shop orders | `ORDER` | Assigned courier (`deliveryId`) | Merchant, courier, platform |
| Standalone delivery | `ORDER` | Assigned courier (`deliveryId`) | Courier, platform |
| Rides | `RIDE` | Assigned driver (`trips/{tripId}/driverId`) | Driver, platform |

A cash payment must have a server-created `payments/{paymentId}` record and complete server-created `paymentAllocations/{paymentId}` children before the collector confirms collection. For cash, the sum of allocations must exactly equal `Payment.amountMinor`. Never accept payment totals, commission amounts, merchant splits, or courier/driver splits from the collection callable.

## Callable lifecycle

Exports from `functions/src/finance/cash-operations.ts`:

- `confirmCashCollection({ paymentId, notes? })`: only the assigned courier/driver can attest collection. The amount is loaded from the payment record. `payments/{paymentId}` and `cashCollections/{paymentId}` are committed together in one RTDB transaction; the collector assignment is checked again inside that transaction. The payment receipt is then posted through the idempotent financial journal.
- `confirmMerchantCashSettlement({ paymentId })`: the merchant beneficiary confirms that it physically received its allocated cash. The journal transfers the merchant amount from the collector's merchant-specific `CASH_OWED` account into the merchant's available wallet.
- `confirmCashRemittance({ remittanceId, collectorId, amountMinor })`: an administrator confirms that the platform commission was physically remitted. The ledger rejects amounts larger than the collector's platform cash-owed balance.
- `getMyCashOutstanding()`: a signed-in collector can read their platform and merchant cash obligations. It does not expose other collectors' balances.
- `reconcileCashPayments()`: administrator-only ledger reconciliation; persists an audit snapshot under `cashReconciliations/{id}`.

## Provider-independent payment state machine\n\n`functions/src/finance/payment-state.ts` defines normalized transitions for pending, processing, succeeded, failed, cancelled and expired payments, plus refund states. Provider-specific callbacks must be verified and normalized before using this state machine. Same-state retries are no-ops; failed, cancelled and expired payments cannot be reopened. Cash collection uses the direct `PENDING -> SUCCEEDED` transition.\n\n## Cash accounting

At collection, the receipt operation balances the full customer payment against its allocations:

- Merchant allocation: held in `cash_owed_{collectorId}_merchant_{merchantId}` until merchant confirmation.
- Platform commission: held in `cash_owed_{collectorId}` until an administrator confirms remittance.
- Driver/courier earning: recorded in `cash_retained_{collectorId}`, because the collector already has this portion in cash. It is not also credited to a withdrawable wallet, avoiding double payment.
- No external bank transfer or GCash transaction is required for CASH.

The merchant and platform cash-owed accounts are deliberately separate. A merchant settlement cannot reduce the commission owed to the platform, and a platform remittance cannot consume the merchant's cash.

The admin reconciliation callable also checks the operational cash equation:

`cash collected = merchant cash settled + platform commission remitted + collector retained earnings + current cash outstanding`

It stores both this cash reconciliation and the underlying immutable-ledger projection check. A non-zero difference is reported as unbalanced; it is not silently adjusted.

## Authorization contract

- Every callable requires Firebase Authentication and App Check.
- Collection is authorized against the assigned `deliveryId` for orders or `driverId` for rides. The caller cannot choose the collector.
- Merchant settlement requires the authenticated UID to equal the merchant beneficiary ID on the persisted allocation. When creating allocations, use the merchant owner's Firebase UID as `beneficiaryId` (for shops this is `ownerUid`).
- Admin-only callables require the trusted Firebase custom claim `admin === true`. Do not grant this claim from a client-writable profile field. Provision it only from a trusted administrative process.
- Financial records remain server-write-only. The client may invoke these callables but must not write payments, allocations, cash collections, handovers, remittances, wallets, or journal entries directly.

## Important integration boundary

This phase adds the cash collection, merchant settlement, platform remittance, collector balance, and ledger reconciliation callables. It does **not** yet create the authoritative payment and allocation records from the app's existing order/trip creation flows. The current repository's quote functions return server-priced quotes, but its financial README explicitly records that payment creation and live order/trip payment lifecycle wiring are not yet present.

Before enabling cash in production, the trusted order/trip backend must:
1. consume a valid, unexpired quote belonging to the authenticated customer;
2. create the payment and allocation records using the server quote breakdown and actual merchant/driver/courier assignments;
3. attach the assigned courier to order payments before collection, and read the assigned driver from the trip record for rides;
4. reject client attempts to edit payment amounts, payment status, beneficiary splits, or cash evidence.

The current Android order flows write order records directly. They must be migrated to that trusted payment/order creation boundary before this phase can honestly be considered end-to-end complete. Also merge the financial rules fragment into the full application ruleset; do not deploy the fragment alone. The repository's `firebase.json` currently deploys Functions only.

## Operational acceptance checks

- Cash can be selected in all four service quote flows.
- Collection is rejected for an unassigned or wrong collector.
- A retry of the same collection does not duplicate ledger entries.
- A cash allocation mismatch is rejected.
- Merchant settlement moves only that merchant's cash-owed amount.
- Platform remittance moves only the collector's platform cash-owed amount.
- An over-remittance is rejected by the ledger.
- Collector outstanding totals include both merchant and platform obligations.
- Reconciliation rebuilds balances from immutable journal entries and reports projection discrepancies.


## Trusted payment initialization

`initializeOrderCashPayment` and `initializeRideCashPayment` are authenticated, App Check-protected callables. They accept only a quote ID and an existing order/trip ID; clients never submit a payment amount, merchant split, driver/courier earning or commission.

- The quote must be server-created, owned by the caller, unexpired, unused, PHP-denominated and explicitly marked `CASH`.
- The order/trip must belong to the caller and match the quote's source, item quantities, route and server-computed totals.
- The current cash implementation requires an assigned courier for orders and an accepted/assigned driver for rides. It fails closed if assignment is missing or changed.
- Payment, payment allocations, pending cash-collection evidence, transaction payment ID, quote consumption and per-order/per-trip uniqueness index are committed in one RTDB root transaction.
- Retries for the same quote and transaction return the already-created payment ID. A second payment for the same order/trip is rejected.
- The collector can confirm only the exact amount on the pending payment through `confirmCashCollection`. The existing cash collection transaction then marks payment succeeded and writes matching collection evidence before the ledger receipt is posted.

This phase deliberately initializes CASH only. Production GCash checkout remains blocked until the Xendit account and integration are approved and a verified provider webhook can update the same canonical `payments/{paymentId}` record. The development simulated GCash flow remains isolated under `paymentIntents` and must not be treated as production revenue.
