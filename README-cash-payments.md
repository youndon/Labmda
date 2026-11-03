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

## Provider-independent payment state machine

`functions/src/finance/payment-state.ts` defines normalized transitions for pending, processing, succeeded, failed, cancelled and expired payments, plus refund states. Provider-specific callbacks must be verified and normalized before using this state machine. Same-state retries are no-ops; failed, cancelled and expired payments cannot be reopened. Cash collection uses the direct `PENDING -> SUCCEEDED` transition.

## Cash accounting

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

Food, shop, ride and standalone delivery creation now consume one-time server quotes and persist the payment record from the trusted Cloud Functions. The Android food, shop and passenger ride paths call these trusted endpoints; standalone delivery is created through the same quote flow. The courier/driver assignment operations create the quote-derived allocations once the actual beneficiary is known.

The GCash processor for new payments is **Xendit**, not a customer-facing "Xendit" method. The payment method remains `GCASH`; `CASH` remains the alternative. The customer tracking card invokes `createXenditGcashCheckout`, which creates or reuses one server-side Xendit Payments API v3 Payment Request for the canonical payment record and opens the returned HTTPS `REDIRECT_CUSTOMER` action. The Android app never receives the Xendit secret key.

The public `xenditPaymentWebhook` endpoint validates the `x-callback-token` header before processing `payment.capture`, `payment.failure`, and `payment_request.expiry` events. It matches the Labmda payment reference, Xendit Payment Request ID, business ID, GCash channel, PHP amount and currency, then records event de-duplication and payment status atomically. Only an authenticated successful capture changes the payment to `SUCCEEDED`; returning from the browser is not proof of payment.

Labmda does not use XenPlatform. New GCash collections are made under the admin-owned Xendit account. Direct third-party payouts remain manual until Xendit approves Labmda's disbursement arrangement. Live Xendit calls are disabled by default and require `XENDIT_LIVE_PAYMENTS_ENABLED=true` after approval. The old BDO link attachment and manual confirmation callables are retained solely for historical BDO payment records.

The financial and order/ride rules fragments still need to be merged into the complete application ruleset and tested before deployment. Do not deploy either fragment alone.

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


## Phase 08 — cash collection completion gate

A courier/driver may confirm cash only after the service is complete:

- FOOD, SHOP and DELIVERY orders must already be `DELIVERED`, and both the payment's `deliveryId` and the order's `deliveryId` must still match the authenticated courier.
- RIDE payments must belong to a `COMPLETED` trip whose `driverId` still matches the authenticated driver.
- The linked order/trip must still reference the exact payment ID being collected.
- These checks run inside the same Realtime Database root transaction that writes `cashCollections/{paymentId}` and changes the payment to `SUCCEEDED`. A stale read, changed assignment, incomplete service, or mismatched transaction reference aborts the entire write.
- The amount remains loaded from the server-created payment record in PHP centavos. The caller cannot choose or edit the amount or the collector.

This prevents a courier or driver from prematurely recording a cash receipt while a service is still active, and closes the race where a service could be reassigned or its linked payment changed between the callable's initial read and the collection transaction.

Focused tests cover successful delivered-order and completed-ride collection, retries, wrong collector assignments, incomplete services, changed order courier assignment, failed payments, and amount mismatches.
