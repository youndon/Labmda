# Payment Cloud Functions contracts

The Android payment layer calls callable HTTPS functions via GitLive Firebase Functions. Provider state is finalized by signed Stripe and PayPal webhooks. Checkout redirects may request/inspect authorization, but they do not set a final payment status.

## Security invariants

- Require a valid Firebase Auth context on every user-specific callable. Resolve the client UID from the verified auth context; never accept a caller-supplied UID as authority.
- Admin functions must verify a trusted admin claim or the server-controlled `users/{uid}/role == "ADMIN"` record. The mobile app must never be allowed to grant itself that role.
- Do not trust prices, fees, subtotals, totals, status, merchant identity, commission splits, or provider state supplied by the client. Re-read current catalog, merchant approval/status, and commission settings on the server.
- Create and mutate `orders/{orderId}`, `payments/{orderId}`, `wallets/{uid}`, refunds, and payout records only from trusted server code. Payment status changes must be driven by authenticated provider webhooks and verified server actions, not by redirect URLs.
- Keep provider secret keys in managed server secrets. The app may receive only a hosted-checkout URL, a Stripe PaymentIntent client secret, public identifiers, opaque payout account references, and display-safe status.
- Make order/session creation, webhook handling, refunds, and payout requests idempotent. Verify webhook signatures and deduplicate provider event IDs.

## Callable functions

| Callable | Request | Behavior |
|---|---|---|
| `createOrder` | `{ orderType, sourceId, items: [{ id, quantity }], dropoffLocation, dropoffLat, dropoffLng, paymentMethod }` | `{ orderId, subtotalMinor, deliveryFeeMinor, totalMinor, currencyCode }` |
| `updateOrderStatus` | `{ orderId, status }` | `{ accepted, status }` |
| `claimCourierOrder` / `releaseCourierOrder` | `{ orderId }` | `{ accepted, orderId }` |
| `createRideTrip` | pickup/drop-off coordinates, passenger metadata, optional target driver UID, `paymentMethod` | `{ tripId, fareMinorUnits, currencyCode }`; fare is recomputed server-side |
| `createPaymentSession` | `{ orderId, method }`, method is `STRIPE` or `PAYPAL` | `{ orderId, provider, sessionId, checkoutUrl, clientSecret, captureMethod, amountMinorUnits, currencyCode, expiresAtEpochMillis }`. Stripe returns a manual-capture PaymentIntent (`sessionId` = `pi_…`, `clientSecret` set, `checkoutUrl` empty); PayPal returns an approval `checkoutUrl` and empty `clientSecret` |
| `requestCashPayment` | `{ orderId }` | `{ accepted, message }`; cash stays pending until collection |
| `verifyPaymentReturn` | `{ orderId, provider, sessionId, resultToken }`; for a Stripe PaymentIntent pass the `pi_…` ID as both `sessionId` and `resultToken` (or `resultToken: "cancel"`) | `{ accepted, message }`; an authorized Stripe PaymentIntent becomes `AUTHORIZED`, not `SUCCEEDED` |
| `getMerchantWallet` | no arguments | `WalletSnapshot` JSON shown below |
| `getCourierWallet` | no arguments | `WalletSnapshot` JSON shown below |
| `getPayoutAccount` | no arguments | Not implemented in Functions yet |
| `updatePayoutAccount` | `PayoutAccount` fields: `provider, providerAccountId, countryCode, accountLabel, updatedAtEpochMillis` | Not implemented in Functions yet |
| `requestPayout` | `{ amount: { minorUnits, currencyCode } }` | Currently writes a `REJECTED_DISABLED` audit row under `payouts/`, then throws `failed-precondition`; it never reserves or transfers funds |
| `adminListPayments` | `{ cursor }` | `{ payments: PaymentRecord[], nextCursor }` |
| `adminRefundPayment` | `{ orderId, amountMinorUnits, currencyCode, reason }`; amount/currency may be null for full refund | Not implemented in Functions yet |
| `adminReconcilePayments` | `{ fromEpochMillis, toEpochMillis }` | Not implemented in Functions yet |
| `adminGetCommissionSettings` | no arguments | `CommissionSettings` |
| `adminUpdateCommissionSettings` | `CommissionSettings` | `{ accepted, message }` |

The order screens call `createOrder`; the function derives item prices and fees from RTDB and writes `orders/{orderId}` and `payments/{orderId}` in one server-side multi-location update. Retail orders require `settings/deliveryFees/RETAIL` to be configured. Payment records include provider, provider reference, amount, and all four breakdown components. Their `breakdownStatus` is `UNALLOCATED` and components start at zero; this must not be interpreted as a final split. Commission configuration is stored at `config/commission`, and wallet nodes are initialized server-side with zero balances. Wallet balance credits, cash collection postings, signed webhook reconciliation, and payouts remain disabled until a verified fee/allocation policy and transactional ledger posting are implemented. Do not reopen client writes to make existing screens work.

## Expected wallet response

```json
{
  "summary": {
    "availableBalance": { "minorUnits": 0, "currencyCode": "USD" },
    "pendingBalance": { "minorUnits": 0, "currencyCode": "USD" },
    "cashOwed": { "minorUnits": 0, "currencyCode": "USD" },
    "lifetimeEarnings": { "minorUnits": 0, "currencyCode": "USD" },
    "updatedAtEpochMillis": 0
  },
  "transactions": [
    {
      "id": "server-generated",
      "orderId": "server-generated",
      "type": "EARNING",
      "status": "AVAILABLE",
      "amount": { "minorUnits": 0, "currencyCode": "USD" },
      "description": "",
      "createdAtEpochMillis": 0
    }
  ],
  "payoutAccount": null
}
```

## Payment record and RTDB writes

Write `payments/{orderId}` as a server-owned record containing at least `orderId`, `clientId`, `merchantId`, authorized `merchantIds`, `courierId`, `provider`, `providerReference`, `method` (legacy compatibility), `status`, `amount`, `breakdownStatus`, `breakdown`, `providerPaymentId` (legacy compatibility), `refundAmount`, and `updatedAtEpochMillis`. Money values use `{ minorUnits, currencyCode }`; breakdown contains `merchantShare`, `courierShare`, `platformShare`, and `gatewayFee` Money values.

A provider redirect only triggers `verifyPaymentReturn`; it must never mark a payment successful on its own. Verified provider completion currently writes a deterministic gross-payment confirmation entry to `ledger/` with `affectsWallet: false`; it does not credit a wallet. Signed Stripe and PayPal webhooks still need to verify signatures, update payment status and final allocation atomically where possible, and deduplicate provider event IDs.

For payouts, verify the merchant/shop/restaurant or courier was approved by an administrator, check available balance and payout-account ownership on the server, and reserve/debit funds transactionally. For refunds, enforce refundable amount and provider state on the server, then record the provider refund result and wallet reversals.




## Provider configuration and return flow

- Configure the server-only Firebase Functions secrets `STRIPE_SECRET_KEY`, `PAYPAL_CLIENT_ID`, and `PAYPAL_CLIENT_SECRET`. The PayPal environment string parameter `PAYPAL_ENVIRONMENT` defaults to `sandbox`; set it to `live` only when live credentials are configured.
- Supply the Stripe **publishable** key to the Android build with `-PstripePublishableKey=pk_test_...` or the `STRIPE_PUBLISHABLE_KEY` environment variable. Do not put a Stripe secret key in Gradle properties used by the client, source control, or the APK.
- `createPaymentSession` reads `orders/{orderId}` (or `trips/{tripId}` for rides), checks that the caller owns it and that it is still payable, and recomputes the amount from the current catalog, delivery-fee settings, or ride coordinates. If the recomputed total differs from the total stored on the order, it fails with `failed-precondition` instead of charging a different amount. It then writes `payments/{orderId}` (provider, provider reference, recomputed amount, `captureMethod`, status `REQUIRES_ACTION`) and mirrors the payment status onto the order or trip.
- Stripe: creates a PaymentIntent with `capture_method=manual` for the Stripe customer stored at `paymentCustomers/{uid}`, card-style methods only (`allow_redirects=never`). Funds are only authorized; capture must be done later by trusted server code (not implemented yet). Card authorizations typically expire after about 7 days if not captured. The client secret is returned to the app for the Stripe PaymentSheet and is never stored in RTDB.
- **Android follow-up required:** `StripePaymentLauncher` and `PaymentRepository.createCheckout` still expect a hosted Stripe `checkoutUrl`. They must present the Stripe PaymentSheet with `clientSecret` and the publishable key, then call `verifyPaymentReturn` with the PaymentIntent ID. The Kotlin `PaymentStatus` enum also needs an `AUTHORIZED` value.
- PayPal: creates a `CAPTURE` checkout order and returns its approval URL; `verifyPaymentReturn` captures it after approval.
- Repeated calls reuse an open PaymentIntent or unexpired PayPal order with the same amount. Switching methods, or a changed amount, cancels an open (unauthorized) PaymentIntent first. Provider requests carry deterministic `Idempotency-Key` / `PayPal-Request-Id` headers.
- `verifyPaymentReturn` supports both PaymentIntents (`pi_…`) and legacy Checkout Sessions (`cs_…`). It checks that the caller owns the payment, that the returned session matches the server-stored session, and that the provider API confirms the exact amount and currency before updating the payment/trip/order status. A redirect alone is not confirmation.
- The Android manifest currently supports the custom scheme `labmda://payment-return`. A **verified HTTPS Android App Link** requires a real domain plus a matching `/.well-known/assetlinks.json`; no domain was supplied for this repository, so that domain association is not configured here.
- Signed Stripe/PayPal webhooks still need to be added and deployed. Without webhooks, if a customer pays but does not return to the app, the local status might remain `REQUIRES_ACTION` until a later server reconciliation verifies it. Do not use the redirect as the only eventual fulfillment signal.
- Provider API credentials and app return redirects must be tested in the provider sandbox before release. No secrets were added to this repository.

## Deployment caution

`firebase.json` intentionally configures Functions only. `database.rules.json` restricts orders and payments, but this application also uses other RTDB paths (including trips, restaurants, shops, and user service records). Audit and add least-privilege rules for every existing path before deploying the database rules file; deploying the current rules as a whole would deny access to unlisted paths. No Firebase deployment was performed by this change.

