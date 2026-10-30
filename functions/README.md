# Labmda payment functions

The Android client calls these Firebase callable functions. The server reads item prices and
pricing configuration from Realtime Database; clients never send order totals or trip fares.

## Xendit setup

1. Create a Xendit secret API key with permission to create Payments API requests.
2. Configure the Functions secret:
   `firebase functions:secrets:set XENDIT_SECRET_KEY`
3. Configure the webhook verification token from the Xendit Dashboard:
   `firebase functions:secrets:set XENDIT_WEBHOOK_TOKEN`
4. Create public HTTPS success and failure return pages, then set
   `PAYMENT_SUCCESS_RETURN_URL` and `PAYMENT_FAILURE_RETURN_URL` in the Functions
   environment (see `.env.example`).
5. Configure the Xendit Payment Request webhook to point to the deployed
   `xenditWebhook` function URL.

The callable and webhook functions are deployed in `asia-southeast1`. The payment currency is
PHP and the online channel in this first collection flow is GCash.

## Callable Functions API

The initial quote-first API is:

| Callable | Purpose | Input |
|---|---|---|
| `quoteOrder` | Validates a food/goods basket, reads the approved merchant/shop catalogue, obtains road distance and returns a locked order quote | `orderType`, `sourceId`, `items[]`, `dropoffLocation`, `dropoffLat`, `dropoffLng` |
| `createOrder` | Rechecks merchant approval, item availability and stock, then creates an order from the unexpired server-issued quote | `quoteId`, `idempotencyKey` |
| `quoteTrip` | Calculates a ride quote from server route distance and the configured service rate | `pickupLat`, `pickupLng`, `dropoffLat`, `dropoffLng`, optional `serviceType` |
| `createTrip` | Creates a trip using the unexpired ride quote and its pricing snapshot | `quoteId`, `idempotencyKey`, optional passenger/driver display fields |
| `adminUpdatePricing` | Publishes the complete pricing configuration | `configuration` |
| `adminSetCommissionRules` | Updates one or more commission rates without changing other pricing rules | `commissions.foodBps`, `goodsBps`, `deliveryBps`, `ridesBps` |

For `quoteOrder`, each item is a catalogue identifier and quantity, for example
`{ "id": "meal_123", "quantity": 2 }`. Do not send unit prices, subtotals, delivery fees,
distances, commission amounts or totals as authoritative values.

Quote records are created by the server and expire after 10 minutes. `createOrder` and
`createTrip` accept only the owning user's quote, revalidate availability/stock and use the
saved price and pricing-rule snapshot. Reusing an idempotency key returns the existing record
for the same authenticated owner.

The original payment-compatible callables (`createOrderPayment`, `createTripPayment`) remain
available for older Android screens. The quote-first `createOrder`/`createTrip` API creates
the authoritative order/trip data; the payment lifecycle can then be started through the
payment-specific flow.

## Local Firebase emulators

Install Node.js 22 and the Firebase CLI (`npm install -g firebase-tools`), then from the
repository root run:

```sh
npm --prefix functions install
npm --prefix functions run serve
```

The script builds TypeScript and starts the Functions, Realtime Database and Auth emulators
using the separate `firebase.emulator.json` and demo project ID `demo-labmda`, so local
requests do not target your production Firebase project. Emulator ports are configured there:
Functions 5001, Realtime Database 9000, Auth 9099, and Emulator UI 4000. The emulator uses
`database.emulator.rules.json`, which denies direct client reads/writes; server Functions use
the Admin SDK and can still access the emulator database. Production deployment uses only
`firebase.json` and does not deploy the emulator-only rules.

For local secret-backed functions, copy `functions/.secret.local.example` to
`functions/.secret.local` and fill in test credentials. The real `.secret.local` file is
ignored by Git. For local return-page environment values, copy `functions/.env.example` to
`functions/.env.local`. Never commit real API keys or payment secrets.

To deploy to your linked Firebase project, first select it with `firebase use --add`, then run
`npm --prefix functions run deploy`. This deploys Functions only; it does not change your
Realtime Database rules. Keep the existing app rules and add the quote/config write protections
documented below before enabling production checkout.

## Administrator pricing configuration

All platform pricing is stored server-side at `config/pricing/current`. The Android app must
not write to this path. Administrators publish a complete configuration through the callable
`updatePricingConfiguration`; reading it through `getPricingConfiguration` is also restricted
to administrators.

The schema template is `functions/pricing.example.json`. Amounts are integer PHP centavos
(100 centavos = PHP 1.00), and commission/cancellation percentages are integer basis points
(100 basis points = 1%). Do not enter peso decimals in the configuration.

The configuration contains:

- `delivery`: base fee, centavos per kilometre, minimum fee, rounding increment and rounding
  mode (`CEIL`, `NEAREST`, or `FLOOR`).
- `rides/{serviceType}`: base fare, centavos per kilometre, minimum fare, rounding rules and
  optional fixed additional charges.
- `commissions`: independent rates for food, goods, delivery and rides.
- `additionalCharges`: fixed charges for food, goods, delivery and rides.
- `cancellationFees` and `refundPolicy`: fixed cancellation amounts, optional percentage
  rates, cancellation timing and delivery-fee refund policy.

The zero amounts in `pricing.example.json` are placeholders, not recommended prices.
Enter actual delivery, ride, commission, refund and cancellation rates in the Admin Panel before
publishing. A configuration must be published before quote or checkout functions will accept
new orders. Every publish increments `version` and stores a historical copy under
`config/pricing/versions/{version}`. Each quote, order and trip stores its own route distance,
pricing version, applicable rules and calculated centavo breakdown. Existing snapshots are not
recalculated when an administrator later changes the current configuration.

### Secure administrator access

Pricing callables require the Firebase Authentication custom claim `admin: true`. Do not use
a client-editable `users/{uid}/isAdmin` field as authorization. On a trusted machine using the
Firebase Admin SDK, assign the claim to the intended administrator UID while preserving their
other custom claims:

```ts
import { getAuth } from "firebase-admin/auth";

const auth = getAuth();
const user = await auth.getUser("ADMIN_UID");
await auth.setCustomUserClaims(user.uid, {
  ...(user.customClaims ?? {}),
  admin: true,
});
```

After changing a custom claim, the administrator must obtain a refreshed ID token (sign out and
back in, or force-refresh the token) before calling the pricing functions.

### Google Routes API

The backend uses Google Routes API `computeRoutes` for driving distance. It requests
`routes.distanceMeters` and `routes.duration` through the required field mask. Haversine
distance and client-supplied kilometres are not used to calculate a billable price.

1. Enable Routes API in the Google Cloud project with billing enabled:
   `gcloud services enable routes.googleapis.com`.
2. Create an API key restricted to Routes API.
3. Store the key as a Firebase Functions secret:
   `firebase functions:secrets:set GOOGLE_ROUTES_API_KEY`.
4. Deploy Functions after configuring the secret.

Keep this key in Secret Manager; never include it in Android resources, Realtime Database or a
client request. Google documents the `computeRoutes` endpoint, API-key header and field-mask
requirements in its [Routes API setup guide](https://developers.google.com/maps/documentation/routes/get-api-key)
and [field-mask guide](https://developers.google.com/maps/documentation/routes/choose_fields).

### Realtime Database access

Deny client writes to `config/pricing`, `config/pricing/versions`, `pricingQuotes` and
`orderQuotes`. Cloud Functions use the Admin SDK and bypass RTDB rules. A quote may be read
only by its owning signed-in user; clients must not create, alter or extend quote records.

The repository does not currently contain the deployed Realtime Database rules file. Add
these branches to your existing rules (preserve your participant/order/trip rules):

```json
{
  "rules": {
    "config": {
      "pricing": {
        ".read": "auth != null && auth.token.admin === true",
        ".write": false
      }
    },
    "pricingQuotes": {
      "$quoteId": {
        ".read": "auth != null && data.child('userId').val() === auth.uid",
        ".write": false
      }
    },
    "orderQuotes": {
      "$quoteId": {
        ".read": "auth != null && data.child('userId').val() === auth.uid",
        ".write": false
      }
    }
  }
}
```

The Functions Admin SDK bypasses RTDB rules for server writes. Do not replace your complete
rules document with this fragment; merge only these paths into the existing rules tree.

## Realtime Database indexes and rules

The following query indexes are used by the current Android read models:

- `orders`: `clientId`, `sourceId`, `merchantId`, and `deliveryId`.
- `users`: the nested child path `driverService/isOnline`.
- `trips`: `passengerId`, `driverId`, and `status`.
- `payments`: `clientId` and `merchantId`.

Add these names to the relevant node's `.indexOn` array in your existing Realtime Database
rules. The rules must separately authorize each query for the signed-in participant or the
appropriate merchant role. The `users` query is for approved ride drivers with
`driverService/isOnline == true`; the `orders` open-delivery query is for eligible couriers with
`deliveryId == ""`, and the second query is for orders assigned to that courier. Indexes do not
grant read access, and rules are not query filters.

## Important deployment note

Deploying callable functions does not itself secure Realtime Database writes. Before production,
the database rules must deny client creation or modification of authoritative fields in
`orders`, `trips`, and `payments`, while retaining authorized participant reads and role-based
status transitions. The existing app still contains direct status, cancellation, courier
assignment, and trip-transition writes in older screens. Those paths must be migrated to
authorized callable transitions before applying deny-write rules globally.

The account payment activity page reads records by `clientId` and `merchantId`. This release
does not create merchant/courier wallet balances or execute payouts; those belong to the later
settlement phase.

## One-time GCash payment lifecycle

The app must create the order or trip from its server-issued quote first. Then call
createGcashPayment with the entity ID; do not send an amount, currency, merchant split or
client-calculated total:

```kotlin
paymentRepository.createGcashPayment(
    entityType = "ORDER", // or "TRIP"
    entityId = createdOrderId,
    idempotencyKey = checkoutAttemptKey
)
```

The existing Android PaymentRepository routes GCash selections through this quote-first sequence
for both food/goods orders and rides. The food, shop and passenger screens then open the returned
checkoutUrl (DEEPLINK_URL first, WEB_URL fallback) and continue showing the order/trip status
updated by Firebase Functions. For an already-created unpaid order or trip, the standalone
createGcashPayment callable can also be retried with the same idempotency key.

The callable verifies that the signed-in user owns the order/trip and obtains its total from
orders/{id}/totalPriceCentavos or trips/{id}/fareCentavos. It creates a one-time Xendit
Payment Request for PH, PHP, GCASH using automatic collection. GCash does not support a
card-style authorization/capture hold; the customer pays during checkout. Current documented
GCash amount limits are PHP 1.00–PHP 100,000.00, and the payment request expires after 15 minutes.

The response contains:

- checkoutUrl: the Xendit DEEPLINK_URL action when present, otherwise WEB_URL.
- webCheckoutUrl: the explicit browser fallback.
- checkoutActionDescriptor: DEEPLINK_URL or WEB_URL.
- paymentId, paymentStatus, the server amount and expiry timestamp.

On Android, open checkoutUrl with an ACTION_VIEW / URI handler. Keep webCheckoutUrl as the
fallback if no installed app handles the deeplink. Never mark an order or trip paid because
the app resumed, a return URL opened, or the user tapped a success-looking button.

### Authoritative status and webhook

Configure the Xendit Payment Request webhook to point to the deployed
xenditPaymentWebhook HTTPS function in asia-southeast1. Subscribe it to:

- payment.capture — confirmed successful collection; this is the event that moves the payment
  record to PAID.
- payment.failure — updates the attempt/payment to FAILED.
- payment_request.expiry — updates it to EXPIRED.
- refund.succeeded and refund.failed — updates the refund attempt and refunded balance.

The endpoint verifies the x-callback-token header against the XENDIT_WEBHOOK_TOKEN Functions
secret, validates the stored payment request/reference, amount, currency and GCash channel, and
applies status changes transactionally. Duplicate notifications are idempotent. Older provider
events cannot reverse a confirmed payment.
The old xenditWebhook endpoint remains only for the previous integration; configure new GCash
events on xenditPaymentWebhook.

Set the callback-token secret:

```bash
firebase functions:secrets:set XENDIT_SECRET_KEY
firebase functions:secrets:set XENDIT_WEBHOOK_TOKEN
```

Configure PAYMENT_SUCCESS_RETURN_URL and PAYMENT_FAILURE_RETURN_URL as Firebase Functions
string parameters in the selected Firebase project. Both must be public HTTPS URLs routed back
to the app/site. The return URLs are only for navigation; they are not payment confirmations.

Observe payments/{entityId} from the signed-in app with PaymentRepository.observePaymentStatus.
The status is server-owned; clients must not write payment or refund records. Recognized payment
states include CREATING, PENDING, PAID, FAILED, CANCELLED, and EXPIRED. Initiation errors are
recorded separately as INITIATION_FAILED. Refund state is tracked separately, with PENDING,
SUCCEEDED, FAILED, and aggregate payment state PARTIALLY_REFUNDED / REFUNDED.

### Administrator refunds

refundPayment is a callable protected by Firebase Auth custom claim admin: true. It accepts
entityType, entityId, idempotencyKey, a supported reason, and optional amountCentavos.
Omitting the amount requests the remaining refundable balance. The function uses the Xendit
refunds API with the provider payment_id (not the Payment Request ID), reserves the refundable
balance transactionally, and records a unique refund attempt. The 180-day GCash refund window is
checked against the provider-confirmed payment timestamp. Only Xendit confirmation or the
server-to-server refund status lookup changes refunded totals.

### Missed-notification reconciliation

reconcilePendingPayments runs every five minutes in Asia/Manila. It queries the private
paymentReconciliationQueue and refundReconciliationQueue, retrieves the provider status
directly from Xendit, validates the response against the saved reference and amount, and applies
terminal state changes. Temporary API errors are retried with backoff. The queues must remain
server-only.

Merge these paths into the deployed Realtime Database rules (do not replace the existing rules
tree). Keep payments readable only by the customer and relevant order/trip participants or
an administrator, and deny all client writes. Deny client reads and writes to both reconciliation
queues. Add dueAtTimestamp to each queue's indexOn:

```json
{
  "rules": {
    "payments": {
      "$paymentId": {
        ".read": "auth != null && (data.child('clientId').val() === auth.uid || data.child('merchantId').val() === auth.uid || root.child('orders').child($paymentId).child('deliveryId').val() === auth.uid || root.child('trips').child($paymentId).child('driverId').val() === auth.uid || auth.token.admin === true)",
        ".write": false
      }
    },
    "paymentReconciliationQueue": {
      ".read": false,
      ".write": false,
      ".indexOn": ["dueAtTimestamp"]
    },
    "refundReconciliationQueue": {
      ".read": false,
      ".write": false,
      ".indexOn": ["dueAtTimestamp"]
    }
  }
}
```

This payment step records collections and refunds; it does not distribute wallet balances or
run merchant/courier payouts. Use the existing order/trip completion workflow as the gate for
future controlled settlement and payout. Do not model GCash collection as money held for a
later manual capture.
