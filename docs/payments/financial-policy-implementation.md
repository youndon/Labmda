# Labmda financial policy and implementation plan

## Approved business policies

These are the agreed rules for the PHP marketplace:

| Policy | Rule |
|---|---|
| Discounts | Merchant-funded. The merchant bears the discount. |
| Cash collection | The assigned courier collects food/goods cash; the assigned driver collects ride cash. |
| Earnings release | Participant earnings become eligible only after the order is delivered or the trip is completed. |
| Payouts | Manual cashout request, administrator approval, then provider submission. |
| Provider fees | Labmda bears collection and payout provider fees from platform revenue. They must not reduce merchant, courier, or driver allocations. |

## Existing repository foundation

The current Functions backend already includes:

- PHP-centavo and basis-point pricing configuration.
- Server-calculated delivery and ride route quotes.
- Pricing snapshots on orders and trips.
- GCash checkout creation and Xendit webhook handling.
- Cash collection, cash remittance, wallet summaries, and immutable accounting journals.
- Encrypted payout-account storage and Xendit payout submission/reconciliation.

This implementation extends that foundation. It does not introduce a second payment system.

## Manual payout workflow

The participant-facing callable is `requestPayout`. It now:

1. Requires an authenticated, approved merchant, courier, or driver.
2. Requires a verified payout destination.
3. Checks the manual minimum payout amount and outstanding refund debt.
4. Uses a wallet transaction to create a single active request lock.
5. Stores a snapshot of the requested amount in `payoutRequests/{requestId}`.
6. Leaves the available wallet balance untouched while the request awaits review.

Administrator callables:

- `adminApprovePayoutRequest`: reserves the approved request amount and submits the Xendit payout.
- `adminRejectPayoutRequest`: rejects a pending request and releases the request lock without changing wallet balances.

The payout amount is the amount captured when the user requested the cashout. Earnings received after that request are not silently added to it. Approval rechecks that the wallet still has enough available earnings and that the recipient account is still verified.

Provider terminal events update the payout request to `PAID`, `FAILED`, or `REVERSED`. The existing payout reconciliation queue remains for resolving uncertain provider outcomes; it does not initiate new payouts automatically.

The old scheduled `processAutomaticPayouts` function and automatic payout queue triggers have been removed. The RTDB rules expose payout requests only to their owner or an administrator; all writes remain server-only.

## Financial calculation policy

All amounts are integer PHP centavos. Commission rates are basis points.

For food/goods:

- Merchant commission base = eligible item subtotal after merchant-funded discounts.
- Merchant payable = discounted item subtotal minus merchant commission.
- Courier payable = delivery fee minus delivery commission.
- Platform gross revenue = merchant commission + delivery commission + platform service charges.
- Customer total = discounted item subtotal + delivery fee + disclosed service charges.

The allocations must reconcile exactly to the customer amount. A merchant discount must never be accepted as an amount supplied by the Android client. The quote and order must load the discount from a server-owned merchant promotion record and preserve the discount rule and calculated amount in the pricing snapshot.

For rides:

- Driver payable = fare minus ride commission.
- Platform gross revenue = ride commission plus disclosed platform service charges.
- Customer total must reconcile to driver payable plus platform allocation.

Provider collection and payout fees are platform operating expenses. They must be recorded separately from participant allocations and must not be deducted from merchant/courier/driver earnings.

## Cash accounting

The assigned courier collects food/goods cash. The assigned driver collects ride cash.

For the illustrative food order of PHP 590.00:

- Courier cash collected: PHP 590.00.
- Courier's own delivery earnings: PHP 81.00.
- Merchant payable: PHP 450.00.
- Platform cash due: PHP 59.00.
- Courier remittance obligation: PHP 509.00.

The courier's cash collection is not a PHP 590.00 earning. Merchant cash earnings remain pending until the collector's required remittance is reconciled. The courier's cash earnings are physical cash retained by the courier, not an electronic wallet payout.

For a cash ride of PHP 100.00, the driver retains the PHP 85.00 driver allocation and owes PHP 15.00 to the platform.

## Implementation tasks

### Completed in this change

- [x] Replace immediate participant payout submission with a manual payout request.
- [x] Add administrator approval and rejection callables.
- [x] Reserve wallet funds only after administrator approval.
- [x] Keep the requested amount fixed; reject approval if the wallet no longer covers it.
- [x] Protect against multiple outstanding requests and payout-account changes while a request is pending.
- [x] Include pending requests in participant wallet and administrator dashboard responses.
- [x] Synchronize request state with provider payout terminal events.
- [x] Remove the automatic payout scheduler and automatic payout queue triggers.
- [x] Add owner/admin read-only RTDB rules for `payoutRequests`.

### Remaining before enabling production cashouts

- [ ] Add emulator tests for owner-only payout-request reads, denied client writes, admin approval authorization, rejection, idempotency, concurrent requests, and wallet-lock release.
- [ ] Add callable integration tests for approval, provider submission, duplicate approval, provider timeout, payout failure, payout reversal, and retry/reconciliation.
- [ ] Update the Android wallet UI to display `activePayoutRequest`, and distinguish `PENDING_ADMIN_APPROVAL`, `PROCESSING`, `PAID`, `REJECTED`, `FAILED`, and `REVERSED`.
- [ ] Update the admin payment dashboard to show pending payout requests and expose approve/reject actions.
- [ ] Validate supported recipient account types and required Xendit recipient fields against the enabled Philippine disbursement product and account approval.
- [ ] Test the full flow in Xendit sandbox before enabling real payout credentials.

### Merchant-funded discount feature

The policy is agreed, but the repository currently calculates `discount: 0`. Before discount amounts can change a checkout total, implement a server-owned merchant promotion model:

- Store promotions under `merchantDiscounts/{merchantUid}/{discountId}`.
- Support an explicit promotion type (percentage basis points or fixed centavos), validity window, minimum subtotal, optional maximum discount, and active state.
- Allow only the owning approved merchant to create/update its promotion through authenticated callables.
- Let the client submit only a promotion ID. Never trust a client-submitted discount amount or percentage.
- Revalidate the promotion during quote creation and checkout, and snapshot the exact promotion rule and discount amount.
- Calculate merchant commission on the post-discount item subtotal. The merchant funds the full customer discount; Labmda does not reimburse it.
- Reject expired, disabled, wrong-merchant, or over-subtotal promotions.

Until this feature is implemented and tested, discounts remain unavailable (zero) rather than accepting untrusted client discounts.

### Provider fee accounting

The current allocation breakdown uses a zero gateway-fee placeholder. The platform-fee policy means actual provider fees must be booked as a separate platform expense when confirmed by Xendit settlement or transaction fee data.

- Do not estimate or subtract an unconfirmed fee from participant shares.
- Add a server-side provider-fee reconciliation input sourced from authenticated provider data or an independently verified settlement report.
- Record each fee idempotently against its payment/payout reference.
- Post balanced ledger entries to a platform provider-fee expense account and the relevant provider clearing account.
- Include fees in the admin reconciliation dashboard and platform net-revenue report.

## Release gates

- Functions TypeScript build passes.
- Realtime Database emulator tests pass against both production and emulator rules.
- Android compilation and wallet/admin UI tests pass.
- Xendit sandbox collection, webhook, payout, failure, and reconciliation cases pass.
- No live payout or production rules deployment occurs as part of a code merge. Production activation requires separate approval and configured server-side secrets.
