# Payment and wallet Realtime Database schema

All records described here are written by trusted Firebase Cloud Functions using the Admin SDK. Client SDK writes are denied by \`database.rules.json\`. Callable functions authorize the signed-in UID or trusted admin role before any read or write.

## \`payments/{orderId}\`

The key is the order ID or ride trip ID. The server writes participant IDs and the amount at order/trip creation. The payment record stores canonical provider references as well as compatibility fields used by existing Android screens.

Important fields:

- \`provider\`: \`CASH\`, \`STRIPE\`, or \`PAYPAL\`
- \`providerReference\`: Stripe Checkout Session ID or PayPal Order ID
- \`providerPaymentIntentId\`: Stripe PaymentIntent ID (when available)
- \`providerAuthorizationId\`: PayPal authorization ID (when available)
- \`providerReceiptId\`: successful capture/charge reference
- \`status\`: internal lifecycle state, updated by trusted provider webhooks (cash uses the courier confirmation callable)
- \`amount\`: \`{ minorUnits, currencyCode }\`
- \`breakdownStatus\`: \`UNALLOCATED\`, \`AUTHORIZED\` is payment status (not breakdown status), \`ALLOCATING\`, \`AWAITING_GATEWAY_FEE\`, or \`ALLOCATED\`
- \`breakdown\`: \`merchantShare\`, \`courierShare\`, \`platformShare\`, \`gatewayFee\`, each a Money object
- \`refundAmount\`: cumulative provider-confirmed refund amount
- \`refundRequest\`: submitted refund metadata, finalized from a provider webhook
- \`updatedAtEpochMillis\`: last payment mutation timestamp

Example:

\`\`\`json
{
  "orderId": "server-order-or-trip-id",
  "contextType": "ORDER",
  "clientId": "firebase-uid",
  "merchantId": "firebase-uid",
  "merchantIds": { "firebase-uid": true },
  "courierId": "assigned-courier-uid",
  "provider": "STRIPE",
  "providerReference": "cs_provider-session-id",
  "providerPaymentIntentId": "pi_provider-intent-id",
  "status": "AUTHORIZED",
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
\`\`\`

Money values use integer minor units plus an ISO 4217 currency code. Direct reads are limited to the client, recorded merchant/authorized merchant managers, and assigned courier. The \`payments\` collection cannot be listed directly by clients, including admins; the admin UI uses \`adminListPayments\`. Direct client writes are denied.

Stripe and PayPal authorization/capture/refund state is finalized only by signature-verified provider webhooks. Checkout return URLs may call \`verifyPaymentReturn\`, which checks the provider and references but does not mark payments settled. The server uses manual capture: Stripe PaymentIntents have \`capture_method=manual\`; PayPal orders use \`intent=AUTHORIZE\`.

**Important:** \`UNALLOCATED\` and zero breakdown components are placeholders, not financial allocation. \`AWAITING_GATEWAY_FEE\` means the payment is captured but the trusted provider fee is not yet available; wallet settlement is deferred and retried. A final split must be non-negative, sum exactly to the captured amount including gateway fees, and be posted only once.

## \`wallets/{uid}\`

The node is created/normalized by trusted functions. Clients cannot change any balance field. Money amounts are stored as integers.

\`\`\`json
{
  "available": { "minorUnits": 0, "currencyCode": "USD" },
  "pending": { "minorUnits": 0, "currencyCode": "USD" },
  "cashOwed": { "minorUnits": 0, "currencyCode": "USD" },
  "reservedForPayouts": { "minorUnits": 0, "currencyCode": "USD" },
  "lifetimeEarnings": { "minorUnits": 0, "currencyCode": "USD" },
  "appliedLedgerIds": {},
  "payoutReservations": {},
  "closedPayouts": {},
  "currencyCode": "USD",
  "updatedAtEpochMillis": 0
}
\`\`\`

- \`available\`: money available for a cashout request.
- \`pending\`: earnings not yet available; this remains separate from payout reservations.
- \`cashOwed\`: cash collected by the courier/driver from customers that is due to the platform/merchant side. This is not a record of cash remitted by a courier to the platform.
- \`reservedForPayouts\`: \`available\` money reserved against queued or submitted cashouts.
- \`appliedLedgerIds\`, \`payoutReservations\`, and \`closedPayouts\`: internal idempotency markers written only by trusted functions.

Verified settlement postings atomically update wallet amounts and an idempotency marker in the wallet transaction. The append-only journal entry is written with a deterministic ID immediately after. The app-facing wallet snapshot returns \`available\`, \`pending\`, \`cashOwed\`, and lifetime earnings; internal payout-reservation fields must not be used as client authority.

## \`ledger/{entryId}\`

Server-owned financial audit records should include an ID, owner/participants, \`orderId\`, entry type, Money amount, status, provider references, timestamps, and idempotency/event identity. Relevant entry types include:

- \`PAYMENT_CONFIRMED_GROSS\`: signed provider confirmation; audit only and does not itself credit wallets.
- \`PAYMENT_SPLIT_ALLOCATED\`: a verified settlement summary; audit only, with the wallet postings recorded separately.
- \`EARNING\` / \`EARNING_AND_CASH_REMITTANCE_DUE\`: wallet-changing settlement postings.
- \`PROVIDER_REFUND_CONFIRMED\`: provider-confirmed refund record. If the payment was already allocated, the record is marked as requiring manual reversal/reconciliation rather than mutating balances without reversal logic.
- \`PAYOUT_PAID\` / \`PAYOUT_RESERVATION_RELEASED\`: idempotent closure of a payout reservation.

Client writes are denied. Participant-scoped rules limit direct reads to entries associated with a user's own UID.

## \`payouts/{payoutId}\`

Cashout records are server-owned. A request reserves the requested amount from \`available\` to \`reservedForPayouts\` and records a deterministic idempotency key. Typical status transitions are:

- \`RESERVING\` → \`QUEUED\` → \`TRANSFERRED\` (Stripe Connect only) → \`SUBMITTED\` → \`PAID\`
- On provider failure, \`FAILED\` releases the reservation back to \`available\` once.
- Rejected requests record a reason and do not reserve funds.

The scheduled \`runPayouts\` function runs every five minutes but exits until \`PAYOUTS_ENABLED=true\`. Only a server-verified/administrator-verified payout destination can be used. Provider webhooks finalize submitted payouts. Stripe Connected Accounts need \`metadata.firebaseUid\` matching the owner and \`payouts_enabled=true\`; PayPal payout destinations require a separate administrator ownership check. Provider payout setup and credentials must be configured and sandbox-tested before enabling the scheduler.

## \`payoutAccounts/{uid}\`

This path is server-only. It stores provider, opaque provider account reference, country, display label, verification status, verification evidence/actor and timestamps. The user can submit metadata through \`updatePayoutAccount\`, but cannot directly mark their account verified. Stripe references are checked against the Stripe API; PayPal references require the admin-only \`adminVerifyPayoutAccount\` workflow after external ownership checks. The client receives only display-safe account metadata from \`getPayoutAccount\`.

## \`config/commission\`

Admin-only callables \`adminGetCommissionSettings\`, \`adminUpdateCommissionSettings\`, and \`setCommissionRules\` manage this path:

\`\`\`json
{
  "currencyCode": "USD",
  "platformCommissionBps": 1000,
  "courierShareBps": 0,
  "updatedAtEpochMillis": 0
}
\`\`\`

Basis-point values are integers from 0 through 10,000 and their sum cannot exceed 10,000. Direct Realtime Database writes are denied, including for admins. Review commission policy before enabling real transactions; missing or inconsistent policy blocks settlement.

## \`paymentCustomers/{uid}\`

The node value is a provider-issued Stripe customer ID string, for example:

\`\`\`json
"cus_provider-issued-id"
\`\`\`

The value is kept outside \`users/{uid}\`, has no client read/write rule, and is never returned in a client response. Only trusted Functions can read/write this path.

## \`providerEvents/{provider}/{eventId}\`

Webhook processing records a temporary \`PROCESSING\` lease and then marks the event \`PROCESSED\`; failure metadata is recorded so the provider can retry. Stable provider event IDs make repeated deliveries idempotent.

## Commission and deployment warnings

No final wallet split should be computed from client data or placeholder zero values. The active code keeps payout execution disabled by default and blocks refunds after wallet allocation until reversal postings are implemented. A confirmed customer cash collection increases the courier's \`cashOwed\`; the separate process for verifying cash remittance to the platform is not implemented.

\`firebase.json\` intentionally configures Functions only. \`database.rules.json\` still does not cover every existing application path (including trips, restaurants, shops and user service records). Audit and add least-privilege rules for all live paths before deploying the full RTDB rules file. No Firebase deployment is performed by this change.
