# Payment Cloud Functions contracts

The Android payment layer calls callable HTTPS functions via GitLive Firebase Functions. The trusted `createOrder`, `updateOrderStatus`, `claimCourierOrder`, and `releaseCourierOrder` handlers are implemented in `functions/src/index.ts`. Provider checkout sessions, payment verification/webhooks, refunds, wallet accounting, and real payout execution remain unimplemented, so card payments and payouts are not production-ready.

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
| `createPaymentSession` | `{ orderId, method }`, method is `STRIPE` or `PAYPAL` | `{ orderId, sessionId, checkoutUrl, expiresAtEpochMillis }` |
| `requestCashPayment` | `{ orderId }` | `{ accepted, message }` |
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

The order screens now call `createOrder`; the function derives item prices and fees from RTDB and writes `orders/{orderId}` and `payments/{orderId}` in one server-side multi-location update. Retail orders require `settings/deliveryFees/RETAIL` to be configured. The initial payment record is pending; provider sessions and verified webhook updates still need implementation. `requestPayout` intentionally fails closed even for approved accounts until transactional wallet reservation and provider payout execution exist. Do not reopen client writes to make existing screens work.

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


## Deployment caution

`firebase.json` intentionally configures Functions only. `database.rules.json` restricts orders and payments, but this application also uses other RTDB paths (including trips, restaurants, shops, and user service records). Audit and add least-privilege rules for every existing path before deploying the database rules file; deploying the current rules as a whole would deny access to unlisted paths. No Firebase deployment was performed by this change.
