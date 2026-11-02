# BDO Checkout merchant integration pack — Phase 02

## Current safe implementation

Labmda currently uses BDO Checkout Payment Request links created manually in the BDO merchant portal. An administrator attaches the one-time link to the server-created payment and separately verifies the exact GCash transaction in BDO before confirming it in Labmda.

This is an interim workflow, not an automated BDO API integration and not a provider webhook. Customer redirects and screenshots are not payment evidence. The existing manual flow must remain in place until the merchant-specific integration has passed the readiness gate.

## Public BDO information confirmed

BDO's public Checkout pages state that:

- BDO Checkout Payment Requests are created through the merchant portal and sent by chat or email.
- BDO Checkout supports GCash, but also lists other e-wallets and QR Ph-enabled payment options.
- BDO offers a separate Website Plug-in for website integration.
- Merchant approval and the account officer determine the rates and availability of cards and mobile payments.
- BDO says Checkout sales are credited to the nominated BDO account in four banking days, with iPay88 handling settlement.

Official references:

- Payment Requests and merchant FAQs: https://www.bdo.com.ph/business/business-solutions/payment-solutions/bdo-checkout-payment-requests
- Website Plug-in and merchant FAQs: https://www.bdo.com.ph/business/business-solutions/payment-solutions/bdo-checkout-website-plugin

These public pages do not provide the private merchant API request schema, credentials, webhook signature contract, transaction lookup endpoint, or a public sandbox contract. Do not infer those details.

## Required items from the BDO account officer

Before implementing the concrete adapter, obtain and retain the approved merchant integration pack covering:

1. Confirmation that Labmda's merchant account is approved for Website Plug-in/API integration and sandbox testing.
2. Sandbox and production base URLs, authentication method, credential issuance and rotation process.
3. Checkout/session creation request and response schemas, required fields, and supported idempotency behavior.
4. Whether the checkout can be configured to present GCash only. Public Payment Request documentation lists multiple payment methods; do not assume GCash-only restrictions exist.
5. Return, cancel and expiry behavior, including whether redirects are merely user experience signals or carry authenticated status.
6. Server-to-server payment lookup/status verification API and the authoritative transaction/reference fields.
7. Webhook/callback delivery contract: HTTP method, required headers, signature algorithm, canonicalization rules, timestamp tolerance, replay protection, retry policy and event identifiers.
8. Refund, void, reversal and chargeback process/API, including status verification.
9. Settlement reports, fees, timing, reconciliation identifiers and treatment of failed or reversed transactions.
10. Written confirmation of the permitted marketplace collection model: Labmda collects for the app's orders/rides, and provider entitlements are paid later by Labmda manually. Confirm whether this requires any separate BDO approval.

Never place credentials in Android resources, Realtime Database, source control or client-visible callable responses. Store them only in Firebase Functions secrets after the contract and account are approved.

## Internal adapter contract added in Phase 02 groundwork

`bdo-checkout-contract.ts` defines Labmda-normalized types for checkout creation, verified payment lookup and authenticated webhook events. These are not BDO wire schemas.

`assertBdoCheckoutReady` fails closed until every required approval, credential, API mapping, webhook verification, GCash-only configuration confirmation and sandbox test is complete.

No live HTTP adapter, endpoint, secret value, webhook signature algorithm or production enablement is included in this groundwork. Those require the actual BDO merchant pack.

## Production boundary

- Customer payment methods remain GCash and Cash.
- BDO Checkout remains the processor for online GCash collection, not a selectable payment method.
- Cash collection and manual provider payouts remain unchanged.
- Only verified provider status may mark a GCash payment successful and trigger ledger posting.
- Automated payouts remain out of scope; any BDO corporate cash-management integration requires separate approval and documentation.
