# CLAUDE.md

Labmda is a ride, food, shop and delivery super-app: one Android app with role modes (client, restaurant/shop manager, courier, driver, admin), backed by Firebase Realtime Database (RTDB) and Firebase Cloud Functions. `AGENTS.md` (imported above) has the module map and general rules. This file covers commands, the payment architecture and known problems. Verified against commit `725d617`.

## Commands

- Android: `./gradlew assembleDebug`, `./gradlew testDebugUnitTest` (CI runs both on JDK 17).
- Functions (Node 20): `cd functions && npm install && npm run build` (`tsc`), `npm run lint` (`tsc --noEmit`). There is no function test script yet.
- Android build config: Stripe publishable key via `-PstripePublishableKey=pk_test_...` or env `STRIPE_PUBLISHABLE_KEY`.
- Function secrets (Firebase secrets, never in the repo or APK): `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`. Plain params: `PAYPAL_ENVIRONMENT` (default `sandbox`), `PAYPAL_WEBHOOK_ID`, `PAYOUTS_ENABLED` (default `false`).

## Code layout that matters

- The package is spelled `com.zouitel.presentaion`. Keep that spelling.
- Screens live in `presentation/.../screens/<feature>/` (`food`, `shops`, `ride`, `delivery`, `account`, `payment`, `wallet`), each with a `modules.kt` Koin module included from `di/modules.kt`. Admin screens are in `admin/`.
- Voyager `Screen` plus `ScreenModel`. Firebase comes from the GitLive SDK (`Firebase.database`, `Firebase.functions`, `Firebase.auth`).
- Money is `model/Money.kt`: integer minor units plus an ISO currency code. Never use `Double` for payment amounts. Older screens still show `Double` prices; convert at the edge.
- `:domain`, `:core:repository` and `:core:datastore` hold only the settings feature. Orders, trips and payments are handled in `:presentation`.
- Payment SDKs: `stripe-android` (PaymentSheet) and PayPal `paypal-payments`, declared in `gradle/libs.versions.toml`.

## Payment architecture

Docs: `docs/payments/realtime-database-schema.md` and `docs/payments/cloud-functions-contracts.md`. They lag the code in places (see "Docs vs code"); when they disagree, trust `functions/src/index.ts`.

- The app never writes `orders`, `payments`, `wallets`, `ledger`, `payouts` or `config/commission`; `database.rules.json` denies client writes. Changes go through callable functions (`createOrder`, `createRideTrip`, `createPaymentSession`, `requestCashPayment`, `updateOrderStatus`, `confirmCashCollected`, wallet, payout and admin callables).
- The server recomputes prices, fees and fares. `createPaymentSession` fails rather than charge an amount that differs from the stored total.
- Stripe: a manual-capture PaymentIntent; the client secret goes to the app's PaymentSheet. PayPal: the server returns an approval URL. Capture or void is done by server code when an order or trip changes status (`onOrderStatusChange`, `onTripStatusChange`), with scheduled retries (`retryAuthorizedPaymentCapture`, `retryPaymentSettlement`). Stripe authorizations expire after about 7 days.
- `stripeWebhook` (HMAC signature check) and `paypalWebhook` (PayPal verification, `PAYPAL_WEBHOOK_ID`) are the source of truth for payment status. They deduplicate provider event IDs and check amount and currency. `verifyPaymentReturn` only inspects the provider; it must never set a final status.
- Wallet allocation can wait on the provider's gateway fee (`AWAITING_GATEWAY_FEE`). `runPayouts` does nothing unless `PAYOUTS_ENABLED=true`. Refunds are blocked after wallet allocation until reversal postings exist. Cash remittance verification is not implemented.
- Do not reopen client writes to make a screen work. Add or extend a callable instead.

## Known issues (verified at 725d617, not fixed)

1. Ride requests send the wrong field. An earlier rename from `PaymentMethod` to `PaymentMethodType` also renamed a JSON field: `CreateRideTripRequest` in `PassengerScreenModel.kt` declares `val PaymentMethodType: String`, but `createRideTrip` reads `body.paymentMethod`, so the server sees no method and rejects the request. The trip reader in the same file also reads the key `"PaymentMethodType"`, while the server writes `paymentMethod`. Local variables named `PaymentMethodType` (shadowing the enum) remain in `PassengerScreen.kt` and `StoreDetailsScreen.kt`.
2. Every callable sets `enforceAppCheck: true`, but no App Check dependency or initialization exists in the Android code. Deployed calls will be rejected until it is added or enforcement is relaxed for development.
3. `PaymentRepository.kt` calls `adminReconcilePayments`, which `functions/src/index.ts` does not export.
4. `database.rules.json` covers only `config`, `deliveryQueue`, `ledger`, `orders`, `paymentCustomers`, `payments`, `payouts`, `users` and `wallets`. Deploying it as is would deny paths the app still uses (trips, restaurants, shops, driver presence). `firebase.json` configures Functions only.
5. Tests are thin: `MoneyTest` plus placeholder example tests on the Android side and none for the functions. Webhook signature checks, status transitions and request DTO field names (see issue 1) need tests.

## Docs vs code

`cloud-functions-contracts.md` lists `getPayoutAccount`, `updatePayoutAccount` and `adminRefundPayment` as "Not implemented", but all three are exported by the functions. Update the docs when you touch these areas.

## Working rules

- Keep changes scoped. Do not edit `app/google-services.json` or auth setup unless asked.
- Do not claim a build or test passed unless you ran it. The Android SDK and Firebase credentials may be missing in a sandbox; say so.