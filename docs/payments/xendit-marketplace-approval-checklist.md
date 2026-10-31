# Xendit standard-account marketplace approval checklist

## Decision being implemented

Labmda intends to operate in the Philippines using one Labmda-owned Xendit business account:

- Collect customer payments through Xendit's GCash Payment Request flow.
- Accept cash as the alternative payment method.
- Calculate merchant, courier and driver earnings, fees and commissions in Firebase Functions.
- Keep participant earnings pending until the underlying GCash transaction is settled and the account has sufficient available PHP balance.
- Pay approved recipients directly to their supported GCash wallet or bank account using Xendit's standard Payouts API.
- Do not use XenPlatform, sub-account balance transfers, or a `for-user-id` header.

This document distinguishes public API capabilities from approval that only Xendit can confirm for the Labmda business account.

## Public documentation confirms these technical capabilities

| Area | Publicly documented capability | Implementation consequence |
|---|---|---|
| GCash collections | Philippine GCash accepts PHP. Xendit's GCash-specific page lists T+2 calendar days (with a weekend caveat), while its broader available-payment-methods table lists T+2 business days. Treat the published estimate as indicative and use the transaction's actual settlement_status as the source of truth; ask Xendit to clarify the documentation discrepancy. | A successful payment callback must not immediately make earnings withdrawable. |
| Payout destinations | Philippine payout coverage includes the `PH_GCASH` recipient channel; the v3 domestic wallet example uses `routing_type_1: WALLET` and `routing_value_1: PH_GCASH`. | A direct payout can target a supported GCash destination, subject to account eligibility and channel limits. |
| Payout API | `POST /v3/payouts` requires Money-Out permission and supports the `Idempotency-key` header. | Every Labmda payout uses a persisted idempotency key and the same request body on retry. |
| Balance | `GET /balance?account_type=CASH&currency=PHP` returns the business cash balance and requires Balance Read permission. | Check actual available PHP funds before making earnings available and before payout submission. |
| Transaction settlement | `GET /transactions` supports exact reference lookup and returns `settlement_status`, including `PENDING`, `EARLY_SETTLED`, and `SETTLED`. It requires Transaction Read permission. | Reconcile the exact GCash transaction rather than assuming settlement from elapsed time. |
| XenPlatform separation | The optional `for-user-id` header and internal account transfers are documented as XenPlatform features. | Labmda omits that header and does not use internal Xendit sub-account transfers. |

Official references:

- https://docs.xendit.co/docs/gcash
- https://docs.xendit.co/docs/available-payment-methods
- https://docs.xendit.co/docs/payouts-coverage-philippines
- https://docs.xendit.co/apidocs/create-payout-v3
- https://docs.xendit.co/apidocs/get-balance
- https://docs.xendit.co/apidocs/list-transactions
- https://docs.xendit.co/docs/settlements-overview

## Must be confirmed directly with Xendit

Public API documentation does not approve a particular business model or enable a feature on a specific account. Before live use, ask Xendit to confirm each item in writing:

1. **Marketplace model approval:** May the Labmda Philippine business account collect customer payments for orders/rides and later pay independent restaurants, shops, couriers and drivers from the same standard business account, without XenPlatform?
2. **Money-In activation:** Is GCash Payment Request collection enabled for this account, including the intended online/marketplace use case?
3. **Money-Out activation:** Is the Payouts API enabled for direct third-party payouts to Labmda's participants?
4. **Recipient classification:** Which recipients must be submitted as `INDIVIDUAL` versus `BUSINESS`? Can a merchant registered as a sole proprietor receive the same direct payout flow?
5. **Purpose and relationship codes:** Confirm the correct `relationship`, `source_of_fund` and `purpose_code` values for restaurant/shop earnings, courier earnings and driver earnings. Do not assume that one code such as `SALARY` is correct for every recipient type.
6. **Recipient verification:** Confirm the KYC, name, address, mobile number and business-document fields required for GCash and bank payout recipients.
7. **Bank coverage:** Confirm the available Philippine bank payout channels and the required routing/account fields for each channel. The current app form only supports GCash.
8. **Limits and fees:** Confirm per-transaction and daily payout limits, minimums, fees, settlement/arrival times (including the published GCash T+2 calendar/business-day discrepancy), reversals, reserves and any required prefunding.
9. **Account balance permissions:** Confirm the API key can be granted Money-Out Write, Balance Read and Transaction Read in the required environment.
10. **Compliance and funds flow:** Confirm that the described collection, commission deduction, delayed earnings release and direct participant payout flow meets Xendit's terms and any applicable local requirements.

## Suggested support request

> We operate Labmda, a Philippines-only ordering, delivery and ride platform. Customers pay Labmda through our single Xendit business account using GCash or cash. For online GCash transactions, our backend calculates the merchant, courier/driver and platform allocations. After the order is delivered or the ride is completed, and after Xendit confirms that the GCash transaction is settled and funds are available, we intend to pay each participant directly to their own supported GCash wallet or Philippine bank account using the standard Payouts API.
>
> We do not intend to use XenPlatform, create Xendit sub-accounts for participants, or transfer balances between Xendit accounts. Participants would be recipients of direct payouts from our Labmda business account.
>
> Please confirm whether this business model is approved for our specific Philippine account, whether Money-In and Money-Out are enabled for this use case, which recipient types and purpose/relationship codes we must use, and the KYC, payout limits, fees and bank/GCash destination requirements.

## Repository implementation status

- The backend now checks the exact Xendit transaction's settlement status before releasing GCash earnings.
- Earnings remain pending while settlement is pending or unavailable funds do not cover existing wallet liabilities plus the new participant shares.
- Admin payout approval checks the current PHP cash balance before reserving and submitting a new payout.
- The current payout-account UI and provider payload support GCash recipients only. Bank destinations and business-recipient payloads remain a follow-up implementation after Xendit confirms the required schemas.
- This repository change does not enable live payments, set real Xendit credentials, deploy Firebase Functions, or perform any payout.

## Live enablement conditions

Do not enable production payment or payout credentials until all of the following are true:

- Xendit has confirmed the marketplace business model and account permissions in writing.
- Recipient KYC and payout destination requirements have been implemented.
- Security hardening is complete and verified.
- Firebase Functions and RTDB emulator tests cover settlement, balance checks, webhook idempotency, refunds, wallet accounting and payout failure/reversal.
- Xendit sandbox tests and production-readiness review have passed.
