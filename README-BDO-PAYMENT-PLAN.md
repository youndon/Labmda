# Labmda BDO payment plan

## Approved working configuration

- Collection provider: BDO Checkout.
- Initial customer methods: GCash and cash.
- Currency: PHP; all financial arithmetic uses integer centavos.
- Service scope: food orders, shop orders, standalone delivery, and rides.
- Checkout mode: BDO Checkout payment request links (manual link creation and attachment) until BDO supplies and approves the merchant-specific API contract.
- Provider payouts: manual bank or GCash transfers recorded by an administrator. A payout is not paid until the real transfer reference is recorded.
- Admin experience: extend the existing financial administration screens.
- Backend: Firebase Cloud Functions and Realtime Database.

## Phase 01 configuration contract

The server-owned Realtime Database node is `paymentConfig`:

- `current`: active configuration.
- `history/{version}`: immutable snapshots of previous configurations.

The customer-safe `getPaymentConfiguration` callable exposes enabled methods, enabled services, currency, provider label, and current checkout/payout mode. It never returns provider credentials or private administrator metadata.

The admin-only `updateAdminPaymentConfiguration` callable can enable/disable GCash and cash and independently enable/disable FOOD, SHOP, DELIVERY, and RIDE. It requires Firebase Authentication, App Check, and the trusted `admin === true` custom claim. It preserves a versioned history.

The current implementation deliberately rejects BDO API checkout and automated corporate payouts. Those modes must not be enabled until BDO supplies the merchant-specific integration and security specifications and the corresponding implementation is completed and tested.

## Existing behavior preserved

- Payment method remains `GCASH` or `CASH`; BDO Checkout is the processor, not a customer-facing payment method.
- Existing server-generated quotes and PHP pricing engine remain authoritative.
- Existing financial ledger, payment allocations, cash collection, manual payout, BDO checkout-link attachment, and administrator payment verification are retained.
- The interim BDO process is not an automated webhook. An administrator must verify the exact paid transaction in BDO before confirming it in Labmda.
- Simulated GCash checkout is development-only and must not be used as production payment confirmation.

## Implementation phases

1. Provider/payment configuration contract and runtime method/service gates.
2. BDO merchant onboarding, private API contract, sandbox credentials, and checkout adapter.
3. BDO callback contract, authentication/signature verification, replay protection, and idempotent event processing.
4. Android checkout and payment-status experience across all four services.
5. Ledger, provider fees, refunds, BDO settlement and reconciliation validation.
6. Manual provider payout workflow and destination management; automated BDO corporate payouts remain a separately approved future enhancement.
7. Existing admin financial screens: payment monitor, settlements, payout approvals, refunds, pricing and audit.
8. Database rules, callable authorization, App Check and security tests.
9. Production readiness, controlled live transactions, reconciliation and operational runbooks.

## Production blockers

Do not enable live automated checkout until BDO provides the actual merchant-specific API and callback specifications. Do not claim a webhook is authenticated until its documented verification contract has been implemented. Do not automate payouts until BDO approves and documents the corporate cash-management API for the Labmda business account.
