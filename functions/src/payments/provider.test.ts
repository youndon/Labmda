import { afterEach, describe, expect, it, vi } from "vitest";
import { SimulatedPaymentProvider, SimulatedPaymentRefundProvider, HmacPaymentSignatureVerifier, isSimulationRuntime } from "./provider.js";

describe("payment provider development adapters", () => {
  afterEach(() => vi.unstubAllEnvs());
  it("allows simulation runtime only inside the non-production Functions emulator", () => {
    vi.stubEnv("FUNCTIONS_EMULATOR", "true");
    vi.stubEnv("NODE_ENV", "test");
    vi.stubEnv("K_SERVICE", "");
    expect(isSimulationRuntime()).toBe(true);
    vi.stubEnv("K_SERVICE", "production-service");
    expect(isSimulationRuntime()).toBe(false);
    vi.stubEnv("K_SERVICE", "");
    vi.stubEnv("NODE_ENV", "production");
    expect(isSimulationRuntime()).toBe(false);
    vi.stubEnv("FUNCTIONS_EMULATOR", "false");
    vi.stubEnv("NODE_ENV", "test");
    expect(isSimulationRuntime()).toBe(false);
  });
  it("refuses to construct a simulated provider when simulation is disabled", () => {
    expect(() => new SimulatedPaymentProvider(false)).toThrow("disabled");
    expect(() => new SimulatedPaymentRefundProvider(false)).toThrow("disabled");
  });
  it("returns clearly marked simulated sessions and refunds", async () => {
    const provider = new SimulatedPaymentProvider(true);
    const session = await provider.createSession({
      paymentId: "payment_1", amountMinor: 15000, currency: "PHP",
      payerId: "payer_1", expiresAt: 999999, returnUrl: "labmda-dev://return",
    });
    expect(session.provider).toBe("SIMULATED");
    expect(session.simulationOnly).toBe(true);
    const refundProvider = new SimulatedPaymentRefundProvider(true);
    const refund = await refundProvider.createRefund({
      paymentId: "payment_1", providerSessionId: session.providerSessionId,
      amountMinor: 5000, currency: "PHP", reason: "test",
    });
    expect(refund.status).toBe("SIMULATED_SUCCEEDED");
    expect(refund.simulationOnly).toBe(true);
  });
  it("verifies HMAC signatures in constant-time adapter", () => {
    const verifier = new HmacPaymentSignatureVerifier("local-only-secret");
    const body = "{\"eventId\":\"event_1\"}";
    expect(verifier.verify(body, verifier.sign(body))).toBe(true);
    expect(verifier.verify(body, "0".repeat(64))).toBe(false);
  });
});
