# Development-only GCash payment simulation

This module implements a test checkout lifecycle, not a payment gateway integration.

## Safety boundary

- The simulated provider is selected only when `FUNCTIONS_EMULATOR=true`, `NODE_ENV` is not production, and `K_SERVICE` is absent.
- Callable functions fail closed outside that emulator runtime.
- Every session, event, payment intent and refund carries `provider: SIMULATED` and `simulationOnly: true`.
- A simulated success updates only `paymentSessions/{sessionId}`. The linked `paymentIntents/{paymentId}` deliberately remains `PROCESSING`; it is not a real `Payment` and cannot trigger financial ledger posting, merchant earnings, payout, or fulfillment.
- Simulated refunds use `SIMULATED_SUCCEEDED`, not the real refund status enum, and never post to the financial ledger or move money.
- The simulation result and refund callables are development tooling. Do not add them to a production client build or expose a production endpoint for them.
- BDO is not implemented or activated.

## Flow

1. The client requests a server quote using the existing quote callables.
2. `createSimulatedGcashSession({ quoteId })` verifies ownership, quote expiry, GCash method and the server-calculated PHP-centavo total.
3. A simulated session and non-settleable payment intent are created.
4. Development UI chooses simulated `SUCCEEDED` or `FAILED` using `simulateGcashPaymentResult({ sessionId, outcome })`.
5. The webhook processor verifies an HMAC signature, atomically records the event ID and updates checkout state. Repeated event IDs are no-ops; late events become `EXPIRED`.
6. A scheduled task expires abandoned pending sessions.
7. `createSimulatedGcashRefund({ sessionId, reason, amountMinor? })` demonstrates refund state without issuing a real refund.
8. A future real Xendit adapter must implement provider creation/refund and signature-verification interfaces, validate event identity/amount/currency, and only then transition a real payment to `SUCCEEDED`. That live adapter is intentionally not part of this phase.

## Callable exports

- `createSimulatedGcashSession`
- `simulateGcashPaymentResult` (emulator only)
- `createSimulatedGcashRefund` (emulator only)

The existing cash collection and financial ledger flows are unchanged.
