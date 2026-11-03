# Labmda payment foundation

## Provider decision

Labmda uses **Xendit as the GCash collection provider** for the Philippines. The customer-facing methods remain `GCASH` and `CASH`; Xendit is the processor, not a customer-selectable payment method. The implementation uses Xendit's Payments API v3 `POST /v3/payment_requests` with `type=PAY`, `country=PH`, `currency=PHP`, `channel_code=GCASH`, and automatic capture.

Labmda does **not** use XenPlatform. New customer payments are created under the admin-owned Xendit account. Third-party marketplace routing and direct provider payouts are separate approval items; payout mode remains manual until Xendit approves the account's disbursement arrangement.

## Core invariants

1. Store money as integer PHP centavos (`₱1.00 = 100`); never use floating-point values for payment accounting.
2. Every payment belongs to exactly one transaction: an order or a ride trip.
3. An order payment may also reference its assigned `deliveryId`; a ride payment must not.
4. Payment allocations describe intended distribution; settlements record actual settlement outcomes.
5. Wallet balances are cached projections; the append-only ledger is the accounting source of truth.
6. A CASH payment remains `PENDING` until its `CashCollection` is confirmed, then its payment status may become `SUCCEEDED`.
7. Refunds, payouts, settlements, wallet balances, payment confirmations, and ledger entries are written by trusted backend code.
8. Provider API credentials and webhook verification tokens are stored as Firebase Functions secrets, never in Android or Realtime Database.

## Xendit integration

- `functions/src/payments/xendit-payment.ts` creates idempotent GCash Payment Requests using a stable `labmda-payment-{paymentId}` idempotency key.
- `createXenditGcashCheckout` requires Firebase Authentication, App Check, and ownership of the payment record. It loads the amount and currency from the server-owned payment record.
- The checkout URL is returned from Xendit's `REDIRECT_CUSTOMER` action and copied to the canonical payment record. The customer tracking card opens the returned HTTPS URL.
- `xenditPaymentWebhook` authenticates `x-callback-token` before processing events. It matches the payment reference, Payment Request ID, business ID, amount, currency and GCash channel.
- Capture/failure/expiry notifications are de-duplicated in a single RTDB root transaction. Only a verified `payment.capture` event can mark an online payment `SUCCEEDED`; a browser return never confirms payment.
- The existing payment-state and financial-ledger triggers then synchronize the order/ride and post the receipt using the existing idempotent journal.
- Legacy BDO records and verification functions remain only for historical payments; new GCash payments are created with provider `XENDIT`.

## Activation gates

The code supports Xendit development and production API keys. Live API keys are blocked unless `XENDIT_LIVE_PAYMENTS_ENABLED=true`. The default is false. Do not enable live processing until Xendit confirms Labmda's GCash collection permission and the required marketplace/disbursement arrangement in writing.

Automated third-party payouts are deliberately not enabled by this checkout change. Keep provider payout requests and settlement reconciliation under the current admin-controlled manual workflow until the relevant Xendit approval and recipient verification are complete.

## Domain models

- `domain/src/main/java/com/zouitel/domain/model/PaymentModels.kt` — Kotlin serializable domain models.
- `functions/src/models/payment.ts` — TypeScript interfaces, unions, and basic invariant guards.
