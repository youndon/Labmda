# Development-only GCash payment simulation

This module implements a test checkout lifecycle, not a payment gateway integration.

## Safety boundary

- The simulated provider is selected only when `FUNCTIONS_EMULATOR=true`, `NODE_ENV` is not production, and `K_SERVICE` is absent.
- Callable functions fail closed outside that emulator runtime.
- Every session, event and payment record carries `provider: SIMULATED` and `simulationOnly: true`.
- A simulated success updates only `paymentSessions/{sessionId}`. The linked `payments/{paymentId}` deliberately remains `PROCESSING`; it must never trigger financial ledger posting, merchant earnings, payout, or fulfillment as a real customer payment.
- The simulation result callable is development tooling. Do not add it to a production client build or expose a production endpoint for it.
- BDO is not implemented or activated.

## Flow

1. The client requests a server quote using the existing quote callables.
2. `createSimulatedGcashSession({ quoteId })` verifies ownership, quote expiry, GCash method and the server-calculated PHP-centavo total.
3. A simulated session and a non-settleable payment record are created.
4. Development UI chooses simulated `SUCCEEDED` or `FAILED` using `simulateGcashPaymentResult({ sessionId, outcome })`.
5. The webhook processor atomically records the event ID and updates checkout state. Repeated event IDs are no-ops; expired sessions become `EXPIRED`.
6. A future real Xendit adapter must implement the provider and signature-verifier interfaces, validate provider event identity/amount/currency, and only then transition a real payment to `SUCCEEDED`. That live adapter is intentionally not part of this phase.

## Callable exports

- `createSimulatedGcashSession`
- `simulateGcashPaymentResult` (emulator only)

The existing cash collection and financial ledger flows are unchanged.
