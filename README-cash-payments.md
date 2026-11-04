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

The GCash processor is BDO Checkout, not a customer-facing "BDO" payment method. The payment method remains `GCASH`; `CASH` remains the alternative. The interim flow uses BDO Checkout Payment Requests: an administrator creates a one-time request in the BDO portal, attaches the `checkout.bdo.com.ph` URL through `attachBdoCheckoutLink`, and the customer opens it from order or ride tracking. The admin financial screen provides both link attachment and payment verification. `confirmBdoGcashPayment` is a manual verification bridge: an administrator must verify the successful transaction in BDO and provide its exact reference and amount. It must not be called merely because the customer returned from checkout. Automated Website Plug-in API/webhook integration still requires BDO's merchant-specific technical documentation.

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


## Cash exposure controls and operations UI

- `alertOverdueCashCollections` runs hourly. It finds cash payments still `PENDING` more than the configured age (default 24 hours) after the linked order is `DELIVERED` or trip is `COMPLETED` and writes a deduplicated alert to `adminCashAlerts/{paymentId}`. Alerts repeat at most every 12 hours while the payment remains pending and resolve when its state changes. The admin Cash Controls screen displays this durable alert inbox; this is an in-app admin alert, not an FCM push notification.
- `cashControls/config` stores `alertAfterHours` and `maxCollectorOutstandingMinor`. Defaults are 24 hours and PHP 500.00 (50,000 centavos). Admins can update both through `updateAdminCashControls`; a cap of 0 disables the online cap.
- `setCollectorOnline` is the only supported transition to online for drivers and couriers. It checks the authenticated provider's combined platform and merchant cash-owed balance before setting `driverService/isOnline` or `deliveryServices/isOnline`. Database rules allow a client to set the flag to false but not true. The `claimRide` and `claimOrderDelivery` callables independently re-check the cap, so stale or forged online state cannot bypass the work-claim guard.
- `getMyCashOperations` supplies the collector's separate platform and merchant outstanding balances. Driver and courier dashboards link to the Cash Balance screen.
- `getMyCashSettlementItems` lists a merchant's own collected-cash allocations that are not yet handed over. Restaurant and shop workspaces link to the Merchant Cash Settlements screen, which invokes the existing `confirmMerchantCashSettlement` callable.
- The Admin Cash Controls screen shows overdue payments and collector balances, edits the age/cap settings, and confirms platform remittances through the existing `confirmCashRemittance` callable. Merchant handovers and platform remittances remain independent ledger transfers.
- The Android client does not write payment, allocation, cash collection, handover, remittance, or ledger records. All monetary values remain integer PHP centavos and all eligibility/authorization checks execute in Cloud Functions.

Deploy the Realtime Database rules fragment only after merging it into the complete production ruleset and running the existing security/emulator checks.
