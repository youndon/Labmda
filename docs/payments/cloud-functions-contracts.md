# Payment Cloud Functions contracts

The Android payment layer calls callable HTTPS functions via GitLive Firebase Functions. **These functions are contracts for the backend to implement; the handlers and provider webhooks are not included in this repository yet.** The client will not work end-to-end until they exist and are deployed.

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

The current `PaymentScreen` expects the order to already exist. Since RTDB rules intentionally deny client writes to `orders`, the production order flow must first be migrated to a trusted create-order function, for example `createOrder`, which accepts only an order type, source ID, item IDs/quantities, delivery destination, and payment method. That function must derive all amounts from server-side catalog data and return the generated order ID. Do not reopen client writes to make the existing order screens work.

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
