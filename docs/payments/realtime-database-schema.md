# Payment and wallet Realtime Database schema

All records described here are written by trusted Firebase Cloud Functions using the Admin SDK. Client SDK writes are denied by `database.rules.json`. Callable functions authorize the signed-in UID or trusted admin role before any read or write.

## `payments/{orderId}`

The key is the related order ID or ride trip ID. The server writes the participant IDs and amount at creation, then updates provider/status fields only after trusted server actions.

```json
{
  "orderId": "server-order-or-trip-id",
  "contextType": "ORDER",
  "clientId": "firebase-uid",
  "merchantId": "firebase-uid",
  "courierId": "",
  "provider": "STRIPE",
  "providerReference": "provider-checkout-session-id",
  "status": "REQUIRES_ACTION",
  "amount": { "minorUnits": 1250, "currencyCode": "USD" },
  "breakdownStatus": "UNALLOCATED",
  "breakdown": {
    "merchantShare": { "minorUnits": 0, "currencyCode": "USD" },
    "courierShare": { "minorUnits": 0, "currencyCode": "USD" },
    "platformShare": { "minorUnits": 0, "currencyCode": "USD" },
    "gatewayFee": { "minorUnits": 0, "currencyCode": "USD" }
  },
  "refundAmount": { "minorUnits": 0, "currencyCode": "USD" },
  "updatedAtEpochMillis": 0
}
```

Money values always use integer minor units plus an ISO 4217 currency code. `provider` is one of `CASH`, `STRIPE`, or `PAYPAL`; `providerReference` is the provider's hosted checkout/session reference, not a secret. `providerPaymentId` remains as a compatibility field for current screens and old records.

The direct Realtime Database read rule allows the individual payment record only to `clientId`, `merchantId`, or the assigned `courierId`. The `payments` collection itself cannot be listed by clients, including admins; admin screens use the authenticated `adminListPayments` callable. All direct client writes are denied.

**Important:** `breakdownStatus: "UNALLOCATED"` and zero-value breakdown components are placeholders, not a financial allocation. Wallet credits must not be posted from these placeholders. A final merchant/courier/platform allocation requires an explicit fee ownership policy, actual provider fee data, idempotent ledger posting, and verified provider events.

## `wallets/{uid}`

The node is created/normalized by trusted functions when the wallet is first requested or an order/driver is assigned. Balances are Money objects, never client-supplied decimal amounts.

```json
{
  "available": { "minorUnits": 0, "currencyCode": "USD" },
  "pending": { "minorUnits": 0, "currencyCode": "USD" },
  "cashOwed": { "minorUnits": 0, "currencyCode": "USD" },
  "lifetimeEarnings": { "minorUnits": 0, "currencyCode": "USD" },
  "currencyCode": "USD",
  "updatedAtEpochMillis": 0
}
```

The owner can read their own wallet and an admin can read wallets through the admin path, but clients cannot write any wallet field. The callable wallet response maps `available`, `pending`, and `cashOwed` to the presentation DTO. Balances currently initialize to zero; verified earnings and cash collection have not yet been posted to balances because allocation/accounting is intentionally fail-closed.

## `ledger/{entryId}`

Append-only server-owned journal entries should include `uid` (the wallet owner), `orderId`, `type`, `amount` (Money), `status`, `provider`, `providerReference`, `createdAtEpochMillis`, and an idempotency/event identifier. Order-associated records may also carry `clientId`, `merchantId`, and `courierId` for participant-scoped reads. Client writes are denied; each party can read only a ledger entry associated with their UID. This PR defines the rule boundary and wallet transaction response, but does not post balance-changing ledger entries yet.

## `payouts/{payoutId}`

Server-owned payout request/result records should include `uid`, role-specific `merchantId` or `courierId`, `amount` (Money), `status`, `providerReference`, and created/updated timestamps. The owner can read their own payout records; direct writes are denied. Actual payout execution is disabled, and `requestPayout` deliberately fails closed without reserving funds or creating a live payout.

## `config/commission`

The admin-only callable reads or writes the policy at this path:

```json
{
  "currencyCode": "USD",
  "platformCommissionBps": 1000,
  "courierShareBps": 0,
  "updatedAtEpochMillis": 0
}
```

Basis-point values must be integers from 0 through 10,000 and their sum cannot exceed 10,000. Direct Realtime Database writes are denied, including for admins; use `adminUpdateCommissionSettings`. These values are stored now but do not yet create wallet credits until the ledger allocation rules are finalized.

## `paymentCustomers/{uid}`

Stripe customer references are stored separately from `users/{uid}`:

```json
{
  "stripeCustomerId": "cus_provider-issued-id",
  "provider": "STRIPE",
  "updatedAtEpochMillis": 0
}
```

Only trusted Functions can read or write this path. It has no client read/write rule; the customer ID is never copied into `users/` or returned in a client response.

## Deployment caution

`firebase.json` intentionally configures Functions only. The current `database.rules.json` still does not cover every existing app path (including trips, restaurants, shops, and user service records). Audit and add least-privilege rules for all existing paths before deploying the rules file; deploying it as a whole now could block existing app features. No Firebase deployment is performed by this PR.
