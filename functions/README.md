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

## Realtime Database pricing

Before accepting orders, configure numeric prices in PHP:

- Food: `restaurants/{restaurantId}/meals/{mealId}/basePrice` and
  `restaurants/{restaurantId}/deliveryFee`.
- Retail: `shops/{shopId}/products/{productId}/price` and
  `config/pricing/shopDeliveryFee`.
- Rides: `config/pricing/rides/ride/baseFare`,
  `config/pricing/rides/ride/perKm`, and
  `config/pricing/rides/ride/minimumFare`.

Ride pricing is calculated on the server using straight-line (Haversine) distance between the
submitted pickup and drop-off coordinates and the configured rate. The client-supplied fare
estimate is ignored. This is not road distance; before production, replace it with a trusted
server-side routing distance if fares must follow actual road routes.

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
