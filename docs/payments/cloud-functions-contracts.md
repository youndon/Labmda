# Payment Cloud Functions contracts

The Android payment layer calls callable HTTPS functions via GitLive Firebase Functions. The trusted `createOrder`, `createRideTrip`, order lifecycle, `createPaymentSession`, `requestCashPayment`, and `verifyPaymentReturn` handlers are implemented in `functions/src/index.ts`. Checkout creation and provider-return verification use the Stripe and PayPal APIs from server code. Signed provider webhooks, refunds/reconciliation, wallet accounting, admin commission handlers, and real payout execution remain unimplemented, so the integration is not production-ready.

## Security invariants

- Require a valid Firebase Auth context on every user-specific callable. Resolve the client UID from the verified auth context; never accept a caller-supplied UID as authority.
- Admin functions must verify a trusted admin claim or the server-controlled `users/{uid}/role == "ADMIN"` record. The mobile app must never be allowed to grant itself that role.
- Do not trust prices, fees, subtotals, totals, status, merchant identity, commission splits, or provider state supplied by the client. Re-read current catalog, merchant approval/status, and commission settings on the server.
- Create and mutate `orders/{orderId}`, `payments/{orderId}`, `wallets/{uid}`, refunds, and payout records only from trusted server code. Payment status changes must be driven by authenticated provider webhooks and verified server actions, not by redirect URLs.
- Keep provider secret keys in managed server secrets. The app may receive only a hosted-checkout URL, public identifiers, opaque payout account references, and display-safe status.
- Make order/session creation, webhook handling, refunds, and payout requests idempotent. Verify webhook signatures and deduplicate provider event IDs.

## Callable functions used by the Kotlin client

All property names are camelCase and currently match kotlinx.serialization DTOs in `PaymentRepository.kt`.

| Callable | Request | Response |
|---|---|---|
| `createOrder` | `{ orderType, sourceId, items: [{ id, quantity }], dropoffLocation, dropoffLat, dropoffLng, paymentMethod }` | `{ orderId, subtotalMinor, deliveryFeeMinor, totalMinor, currencyCode }` |
| `updateOrderStatus` | `{ orderId, status }` | `{ accepted, status }` |
| `claimCourierOrder` / `releaseCourierOrder` | `{ orderId }` | `{ accepted, orderId }` |
| `createRideTrip` | pickup/drop-off coordinates, passenger metadata, optional target driver UID, `paymentMethod` | `{ tripId, fareMinorUnits, currencyCode }`; fare is recomputed server-side |
| `createPaymentSession` | `{ orderId, method }`, method is `STRIPE` or `PAYPAL` | `{ orderId, sessionId, checkoutUrl, expiresAtEpochMillis }` |
| `requestCashPayment` | `{ orderId }` | `{ accepted, message }`; cash stays pending until collection |
| `verifyPaymentReturn` | `{ orderId, provider, sessionId, resultToken }` | `{ accepted, message }` |
| `getMerchantWallet` | no arguments | `WalletSnapshot` JSON shown below |
| `getCourierWallet` | no arguments | `WalletSnapshot` JSON shown below |
| `getPayoutAccount` | no arguments | `{ account: PayoutAccount or null }` |
| `updatePayoutAccount` | `PayoutAccount` fields: `provider, providerAccountId, countryCode, accountLabel, updatedAtEpochMillis` | `{ accepted, message }` |
| `requestPayout` | `{ amount: { minorUnits, currencyCode } }` | `{ accepted, message }` |
| `adminListPayments` | `{ cursor }` | `{ payments: PaymentRecord[], nextCursor }` |
| `adminRefundPayment` | `{ orderId, amountMinorUnits, currencyCode, reason }`; amount/currency may be null for full refund | `{ accepted, message }` |
| `adminReconcilePayments` | `{ fromEpochMillis, toEpochMillis }` | `{ reconciled, matchedCount, unmatchedCount, message }` |
| `adminGetCommissionSettings` | no arguments | `CommissionSettings` |
| `adminUpdateCommissionSettings` | `CommissionSettings` | `{ accepted, message }` |

The order screens now call `createOrder`; the function derives item prices and fees from RTDB and writes `orders/{orderId}` and `payments/{orderId}` in one server-side multi-location update. Retail orders require `settings/deliveryFees/RETAIL` to be configured. The initial payment record is pending and intentionally omits settlement splits until trusted commission and gateway-fee settings are available; provider sessions and verified webhook updates still need implementation. `requestPayout` intentionally fails closed even for approved accounts until transactional wallet reservation and provider payout execution exist. Do not reopen client writes to make existing screens work.

## Expected wallet response

```json
{
  "summary": {
    "availableBalance": { "minorUnits": 0, "currencyCode": "USD" },
    "pendingBalance": { "minorUnits": 0, "currencyCode": "USD" },
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

Write `payments/{orderId}` as a server-owned record containing at least `orderId`, `clientId`, `merchantId`, `courierId`, `method`, `status`, `amount`, `breakdown`, `providerPaymentId`, `refundAmount`, and `updatedAtEpochMillis`. Money values use `{ minorUnits, currencyCode }`; breakdown contains `merchantShare`, `courierShare`, `platformShare`, and `gatewayFee` Money values.

A provider redirect only triggers `verifyPaymentReturn`; it must never mark a payment successful on its own. Stripe and PayPal webhook handlers should verify signatures, update payment and wallet records atomically where possible, and use provider event IDs for replay protection.

For payouts, verify the merchant/shop/restaurant or courier was approved by an administrator, check available balance and payout-account ownership on the server, and reserve/debit funds transactionally. For refunds, enforce refundable amount and provider state on the server, then record the provider refund result and wallet reversals.




## Provider configuration and return flow

- Configure the server-only Firebase Functions secrets `STRIPE_SECRET_KEY`, `PAYPAL_CLIENT_ID`, and `PAYPAL_CLIENT_SECRET`. The PayPal environment string parameter `PAYPAL_ENVIRONMENT` defaults to `sandbox`; set it to `live` only when live credentials are configured.
- Supply the Stripe **publishable** key to the Android build with `-PstripePublishableKey=pk_test_...` or the `STRIPE_PUBLISHABLE_KEY` environment variable. Do not put a Stripe secret key in Gradle properties used by the client, source control, or the APK.
- `createPaymentSession` reads the stored payment amount in integer minor units and creates a provider-hosted session. The client can open the returned HTTPS checkout URL but never supplies the amount used for settlement.
- `verifyPaymentReturn` checks that the caller owns the payment, that the returned session matches the server-stored session, and that the provider API confirms the exact amount and currency before updating the payment/trip/order status. A redirect alone is not confirmation.
- The Android manifest currently supports the custom scheme `labmda://payment-return`. A **verified HTTPS Android App Link** requires a real domain plus a matching `/.well-known/assetlinks.json`; no domain was supplied for this repository, so that domain association is not configured here.
- Signed Stripe/PayPal webhooks still need to be added and deployed. Without webhooks, if a customer pays but does not return to the app, the local status might remain `REQUIRES_ACTION` until a later server reconciliation verifies it. Do not use the redirect as the only eventual fulfillment signal.
- Provider API credentials and app return redirects must be tested in the provider sandbox before release. No secrets were added to this repository.

## Deployment caution

`firebase.json` intentionally configures Functions only. `database.rules.json` restricts orders and payments, but this application also uses other RTDB paths (including trips, restaurants, shops, and user service records). Audit and add least-privilege rules for every existing path before deploying the database rules file; deploying the current rules as a whole would deny access to unlisted paths. No Firebase deployment was performed by this change.
