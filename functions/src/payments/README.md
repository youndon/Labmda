# GCash payment lifecycle — integration boundary

## Current implementation status

The repository currently contains a **development-only simulated GCash checkout**. It is not connected to a live GCash merchant API. Simulation sessions are marked `provider: SIMULATED` and `simulationOnly: true`; simulated success does not create a succeeded financial payment and must never post to the ledger.

The live GCash API Portal / partner approval has **not** been obtained. This change adds a provider-neutral contract and a fail-closed capability gate; it does not invent GCash HTTP endpoints, credentials, webhook headers, signature algorithms, or response fields.

## Architecture retained

- Existing server-generated quotes remain authoritative for order, shop, delivery and ride amounts.
- All money remains integer PHP centavos.
- Existing financial ledger, payment allocations, commission calculations and wallet projections remain provider-independent.
- Android must receive only a short-lived checkout URL/reference; it must never receive GCash API credentials.
- A provider redirect or Android callback is not proof of payment.
- Only an authoritative provider verification result may transition a real payment to `SUCCEEDED` and trigger ledger posting.
- Collection and disbursement are separate capabilities. GCash collection approval does not imply approval to send payouts.

## New contract

`gcash-provider-contract.ts` defines Labmda's internal normalized contract for creating checkout, verifying collection, requesting refunds, creating GCash payouts and verifying payout results. These are **Labmda interfaces**, not representations of GCash's undocumented API schema. The concrete adapter must map them using the merchant API pack supplied by GCash.

`assertGCashCapabilityReady` fails closed unless the selected capability has explicit provider approval, an approval reference, merchant account ID, verified API contract version, server-side credentials, authoritative status verification and explicit admin enablement. Collection and disbursement readiness are checked independently.

## Work still blocked on GCash approval

### Collection
- Obtain approved GCash merchant/API access and sandbox documentation.
- Confirm hosted checkout or QR flow, callback/webhook contract, signature verification, transaction lookup, expiry, refunds, fees and settlement reporting.
- Implement the concrete adapter and trusted webhook/verification endpoint.
- Create server-owned payment and allocation records from the existing quote and actual order/trip assignments; current app order creation is not yet a complete trusted payment-creation boundary.
- After verified success, update the persisted payment and call the existing idempotent financial ledger posting service.

### Automatic GCash payouts
- Obtain separate approval for the GCash disbursement API/service.
- Confirm recipient eligibility, required recipient data, prefunding, limits, fees, idempotency behavior, status lookup/webhooks and reversal handling.
- Implement the concrete payout adapter and a retry-safe worker. A payout must not be marked paid until GCash confirms it.
- Existing payout requests currently use admin approval and manual transfer confirmation; they are not automatic.

## Safety rule

Until the relevant capability passes its readiness gate and the real adapter is implemented and tested, keep live GCash collection and disbursement disabled. Do not switch the simulated provider into production or interpret a simulated session as customer funds.


## Manual BDO checkout operations and notifications

- Android registers its FCM token through the authenticated `registerFcmToken` callable. Tokens are stored server-side and are never written by client code.
- `notifyBdoPaymentChanges` sends customer and administrator push notifications when a BDO checkout link is attached, a customer requests cancellation, or trusted payment status changes. A durable notification event record makes trigger retries idempotent.
- `remindOverdueBdoCheckoutPayments` checks pending BDO GCash links hourly. After 24 hours it reminds the customer and administrators, with a 12-hour reminder cooldown.
- Customers may call `requestBdoCheckoutCancellation` after a link is attached. This records the request and alerts administrators; it does not mark the order cancelled or the payment expired.
- An administrator must first verify in the BDO portal that the payment request has been closed, then use `expireBdoGcashPayment` with the closure reference. Labmda cannot safely auto-expire or revoke an external BDO link while it may still accept money.
- FCM delivery requires Android notification permission and a valid registered device token. Push delivery is best-effort; payment state remains available from the trusted RTDB payment/order/trip records.
