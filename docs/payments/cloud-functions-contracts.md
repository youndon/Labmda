# Payment Cloud Functions contracts

The Android payment layer calls callable HTTPS functions via GitLive Firebase Functions. Provider state is finalized by signed Stripe and PayPal webhooks. Checkout redirects may request/inspect authorization, but they do not set a final payment status.

## Security invariants

- User-specific callable functions derive the caller UID from Firebase Auth. Do not trust a caller-supplied UID.
- Admin functions require the server-controlled \`users/{uid}/role == "ADMIN"\` record.
- Recompute order prices, delivery fees, ride fares, and totals on the server. Compare the result to the stored payment amount before creating a provider session.
- Payment, wallet, ledger, payout, payout-account, commission, and provider-event records are written only by trusted Functions/Admin SDK code.
- Stripe webhooks validate the \`Stripe-Signature\` header against \`STRIPE_WEBHOOK_SECRET\`, including its timestamp and HMAC. PayPal webhooks call PayPal's webhook signature verification API using \`PAYPAL_WEBHOOK_ID\`.
- Provider events are deduplicated at \`providerEvents/{provider}/{eventId}\`. Provider API actions use stable idempotency/request IDs.
- Provider secret keys are server-only. No Firebase deployment or provider request is performed by these source changes.

## Callable functions

| Callable | Request | Behavior |
|---|---|---|
| \`createOrder\` | \`{ orderType, sourceId, items: [{ id, quantity }], dropoffLocation, dropoffLat, dropoffLng, paymentMethod }\` | Reprices catalog items and fees on the server, then creates order and payment records. |
| \`createRideTrip\` | Pickup/drop-off coordinates, passenger metadata, optional target driver UID, \`paymentMethod\` | Recomputes ride fare from validated coordinates and writes a ride plus its payment record. |
| \`createPaymentSession\` | \`{ orderId, method }\`; method is \`STRIPE\` or \`PAYPAL\` | Reads the order/trip, recomputes and compares the amount, creates a Stripe Checkout Session whose PaymentIntent uses manual capture, or a PayPal order with \`AUTHORIZE\` intent. Saves provider references and returns a hosted checkout URL. |
| \`verifyPaymentReturn\` | \`{ orderId, provider, sessionId, resultToken }\` | Checks ownership, session identity, amount, and provider authorization state. It does **not** mark payment status as paid; webhooks do that. |
| \`requestCashPayment\` | \`{ orderId }\` | Selects cash while leaving payment pending; cash is not considered collected until an assigned courier/driver confirms it after completion. |
| \`confirmCashCollected\` | \`{ orderId }\` | Assigned courier for a delivered order or assigned driver for a completed ride confirms cash receipt. Server records the collection and attempts settlement. |
| \`getMerchantWallet\` / \`getCourierWallet\` | No arguments | Returns server-owned wallet amounts and participant ledger transactions. |
| \`getPayoutAccount\` | No arguments | Returns the caller's payout account metadata, including verification status. |
| \`updatePayoutAccount\` | \`{ provider, providerAccountId, countryCode, accountLabel, recipientType? }\` | Stores account metadata. Stripe accounts are checked against the Stripe API and Firebase UID metadata; PayPal accounts require administrator verification before cashout. Do not send card or bank credentials. |
| \`requestCashout\` / \`requestPayout\` | \`{ amount: { minorUnits, currencyCode }, idempotencyKey? }\` | Checks account approval, currency, available balance, payout enablement, and verified destination, then reserves funds and queues the payout. The legacy \`requestPayout\` name is retained for the current Android UI. |
| \`refundPayment\` / \`adminRefundPayment\` | \`{ orderId, amountMinorUnits?, currencyCode?, reason }\` | Admin-only provider refund request. Final refunded status and cumulative refund amount are recorded by provider webhooks. Refunds are blocked after wallet allocation because wallet reversal accounting is not implemented. |
| \`adminGetCommissionSettings\` / \`adminUpdateCommissionSettings\` / \`setCommissionRules\` | Read policy or submit \`{ currencyCode, platformCommissionBps, courierShareBps }\` | Admin-only commission policy with basis-point validation. |
| \`adminListPayments\` | \`{ cursor? }\` | Admin-only paginated payment view. |

The existing order-status and courier-claim/release callable APIs remain compatible with the Android client.

## Database lifecycle triggers

- **\`onOrderStatusChange\` — \`orders/{orderId}/status\`:** on \`ACCEPTED\`, captures a previously authorized Stripe/PayPal payment. On \`CANCELLED\`, attempts to expire checkout or release the authorization; a captured payment may be refunded automatically only before wallet allocation. On \`DELIVERED\`, settles an already-captured online payment when the provider fee and commission split reconcile. Cash remains pending until \`confirmCashCollected\`.
- **\`onTripStatusChange\` — \`trips/{tripId}/status\`:** on \`COMPLETED\`, captures a previously authorized ride fare. Settlement follows the verified capture webhook.
- **\`stripeWebhook\`:** checks Stripe's signature and timestamps; handles manual-capture authorization, capture success/failure/cancellation, Checkout Session expiration, refund updates, and connected-account payout outcomes.
- **\`paypalWebhook\`:** verifies webhook signatures with PayPal; handles order approval/authorization, capture success, authorization voids, refund updates, and payout-item outcomes.
- **\`retryPaymentSettlement\`:** retries completed orders/rides still waiting for provider-fee data.
- **\`runPayouts\`:** scheduled every five minutes. It submits queued, verified Stripe Connect or PayPal payouts and waits for the provider webhook for final success/failure.

## Amounts, settlement and idempotency

All amounts are integers in the smallest currency unit, paired with an ISO 4217 currency code. The payment amount is authoritative only after the server has recomputed it. Provider webhooks compare captured/authorized amounts and currencies with the server-owned payment record before changing status.

Settlement is gated until a provider fee can be verified and the configured commission policy reconciles. A completed record can remain in \`AWAITING_GATEWAY_FEE\`; the scheduled retry re-attempts fee lookup. The split is written to \`payments/{orderId}/breakdown\` only after non-negative shares add up exactly to the captured amount:

- Merchant share for orders: order subtotal less the configured platform commission.
- Courier share for orders: configured courier share of the delivery fee, when a courier is assigned.
- Driver share for rides: fare less the platform commission and the provider gateway fee.
- Platform share: the reconciled remaining amount after the other shares and gateway fee.

A completed **cash** delivery uses a zero gateway fee. The courier wallet is credited with its configured share, while \`cashOwed\` records cash collected from the customer that is due to the platform/merchant side. Every wallet posting has a deterministic ledger ID and an idempotency marker on the wallet. Provider-confirmed gross payment entries are audit-only and do not credit wallets.

The policy defaults to \`platformCommissionBps: 1000\` and \`courierShareBps: 0\`. Review these values before real transactions. Inconsistent amounts, negative splits, missing assigned courier/driver for a completed ride, or an unavailable gateway fee block settlement rather than guessing.

## Payout enablement

Payout execution is disabled by default. Set the Firebase Functions parameter \`PAYOUTS_ENABLED=true\` only after a provider payout account workflow and policy have been verified.

- Stripe requires a connected account ID whose Stripe metadata contains \`firebaseUid\` matching the signed-in user and \`payouts_enabled=true\`. The job transfers funds to the connected account, then submits a connected-account payout. Provider webhooks finalize status.
- PayPal payout destinations are saved in \`payoutAccounts/{uid}\` as pending verification and must be approved using the admin-only \`adminVerifyPayoutAccount\` callable after out-of-band ownership checks.
- Cashout requests reserve \`available\` funds into \`reservedForPayouts\`; they do not reuse the wallet's \`pending\` earnings balance. Success consumes the reservation. A provider failure returns the reservation to \`available\` once.
- An unverified account, disabled payout config, or insufficient balance prevents transfer. Do not turn the feature on without testing against provider sandboxes and confirming provider permissions.

## Provider configuration

Configure these server-only parameters/secrets in the Functions environment:

- Secret \`STRIPE_SECRET_KEY\`
- Secret \`STRIPE_WEBHOOK_SECRET\`
- Secrets \`PAYPAL_CLIENT_ID\` and \`PAYPAL_CLIENT_SECRET\`
- Parameter \`PAYPAL_ENVIRONMENT=sandbox\` until live PayPal credentials are ready
- Parameter \`PAYPAL_WEBHOOK_ID\` matching the webhook registered in the same PayPal environment
- Parameter \`PAYOUTS_ENABLED=false\` until payout integration/account verification is deliberately enabled

Stripe and PayPal webhook URLs must be registered with the providers. The Android app currently uses the custom scheme \`labmda://payment-return\`; this is not a verified HTTPS Android App Link.

## Remaining production requirements and deployment warning

This code adds server-side lifecycle functions, but production readiness still requires security/emulator tests, provider sandbox tests, and careful operational setup. The database rules file defaults to deny and still does not define least-privilege access for every app RTDB path (such as trips, restaurants, shops, and user service records). Audit all paths before deploying the database rules file. No Firebase deploy, provider credential setup, webhook registration, Stripe Connect onboarding, or PayPal account verification is performed by the code changes alone.

Refunds after wallet allocation require explicit idempotent reversal postings and are deliberately blocked. Cash remittance by courier to the platform also requires a separate verified remittance/reconciliation process; \`confirmCashCollected\` confirms receipt from the customer, not receipt of cash by the platform.
