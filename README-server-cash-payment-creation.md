# Phase 05 — Server-authoritative cash payment creation

## Callable

`createCashPaymentFromQuote({ quoteId, transactionId })`

This callable accepts identifiers only. It never accepts a customer total, merchant split, courier/driver earning, platform commission, or provider fee from the client.

1. Authenticates the customer and loads `pricing/quotes/{quoteId}`.
2. Verifies quote ownership, PHP currency, CASH method, quote type, and expiry.
3. Claims the quote with a deterministic payment ID (`pay_{quoteId}`) so a retry cannot create a second payment.
4. Loads the existing order or trip and verifies its customer owner and service/merchant relationship.
5. Loads merchant ownership from the restaurant/shop record.
6. Creates `payments/{paymentId}` in `PENDING` status.
7. Creates the immutable planned split under `paymentAllocations/{paymentId}/{allocationId}`.
8. Links the payment ID to the order/trip and marks the quote consumed.

The callable is exported from Functions `index.ts` and requires Firebase Authentication and App Check.

## Supported transaction mapping

| Service | Quote kind | Existing transaction | Payment relationship | Allocations |
| --- | --- | --- | --- | --- |
| Restaurant food | `FOOD` | `orders/{orderId}`, `orderType=FOOD` | `ORDER` | Merchant, courier, platform |
| Shop order | `SHOP` | `orders/{orderId}`, `orderType=RETAIL` | `ORDER` | Merchant, courier, platform |
| Standalone delivery/freight | `DELIVERY` | `trips/{tripId}`, serviceType contains Delivery/Freight | `RIDE` transaction reference | Courier, platform |
| Passenger ride | `RIDE` | `trips/{tripId}`, non-delivery serviceType | `RIDE` | Driver, platform |

Delivery and ride use the existing trip record model, while the payment transaction discriminator remains `RIDE` because the payment model has only ORDER/RIDE references. The earning beneficiary differentiates DRIVER from DELIVERY.

## Cash allocation rules

All values are PHP centavos. The allocation builder derives all values from the persisted quote:

- Food/shop: merchant earnings and courier earnings are read from the quote; platform receives the exact remainder of the customer total.
- Delivery: courier earnings are read from the quote; platform receives the remainder.
- Ride: driver earnings are read from the quote; platform receives the remainder.
- The allocation sum must equal the payment amount exactly. Zero-value allocations are omitted.
- A platform-funded discount that would make platform allocation negative is rejected. Platform discount subsidy funding must be modelled explicitly before such a quote can be accepted for cash.
- Merchant owner UID is required. Shops use `ownerUid`; restaurant source records may use `ownerUid`, `ownerId`, `userId`, `managerUid`, or `createdBy`. New restaurant registrations through `FoodRepository.addRestaurant` now persist `ownerUid`.

The payment remains `PENDING` until the assigned courier/driver confirms collection. The collection callable binds the actual assigned collector to the previously unassigned earning allocation before the receipt is posted. This prevents placeholder beneficiary IDs from receiving cash earnings.

## Android client

The presentation module now depends on `dev.gitlive:firebase-functions:2.7.0`, matching the existing GitLive Firebase stack. `CashPaymentRepository` provides typed calls for:

- `quoteFoodOrder`
- `quoteShopOrder`
- `quoteRide`
- `quoteDelivery`
- `createCashPaymentFromQuote`

Restaurant orders, shop orders, and passenger rides now request server quotes, persist the server-priced amount on the transaction record, and create the linked cash payment through the callable. Shop and food checkout reject GCash for now; this phase intentionally enables CASH only. The client does not write payment or allocation records.

## Idempotency and recovery

The quote is claimed using a deterministic payment ID. Repeating the same `quoteId + transactionId` is safe and returns the same payment when it already exists. Reusing the quote for another transaction is rejected. The payment and allocation paths are written using one Realtime Database multi-location update.

If a callable response is lost after the server commits, the client can retry the same quote and transaction IDs. Do not request a new quote for a retry of the same checkout attempt.

## Known integration boundaries

- A food/shop order must already exist before payment creation. The current app still creates the order record directly; the callable then validates ownership/type/source and attaches the server-generated payment. Migrating order creation itself into the backend remains a separate hardening step.
- Courier assignment must be written to `orders/{orderId}/deliveryId` before collection can be confirmed. The collection callable reads that authoritative assignment and binds it into the payment/allocation.
- A standalone delivery booking screen/flow is not currently present in the repository. The quote and payment client methods plus backend mapping are ready for a trip with serviceType Delivery/Freight, but a customer-facing booking flow still needs to call them.
- Existing restaurant records without an owner UID must be migrated before they can receive cash allocations.
- Cash remains the only payment method accepted by `createCashPaymentFromQuote`. GCash requires its own provider session, payment verification and webhook flow; do not mark a GCash quote as paid using this callable.
- Merge the financial rules fragment into the full application ruleset before production. Firebase Admin writes bypass RTDB client rules; clients must not be able to write payments, allocations or quote consumption state.

## Acceptance checks

- Customer cannot create a payment from another user's quote.
- Expired quotes cannot be consumed.
- GCash quotes are rejected by cash payment creation.
- FOOD quote cannot pay a RETAIL order or vice versa.
- A quote cannot be reused for a different transaction.
- A transaction cannot be linked to a second payment.
- Client-supplied amount or allocation fields are ignored because the callable accepts only quote and transaction IDs.
- Allocation sums equal the payment amount exactly.
- Payment starts PENDING and ledger receipt is not posted until cash collection is confirmed.
- Repeated payment creation and cash collection calls are idempotent.
