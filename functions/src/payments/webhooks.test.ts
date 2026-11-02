import { describe, expect, it } from "vitest";
import { SimulatedWebhookVerifier, processSimulatedPaymentEvent } from "./webhooks.js";
import type { SimulatedPaymentEvent } from "./webhooks.js";

function fakeDatabase(seed: Record<string, any>) {
  let value = seed;
  return {
    ref: () => ({
      transaction: async (update: (current: Record<string, any> | null) => Record<string, any> | undefined) => {
        const next = update(structuredClone(value));
        const committed = next !== undefined;
        if (committed) value = next!;
        return { committed, snapshot: { val: () => structuredClone(value) } };
      },
    }),
    value: () => structuredClone(value),
  } as any;
}
const event: SimulatedPaymentEvent = {
  eventId: "event_1", sessionId: "session_1", paymentId: "payment_1",
  outcome: "SUCCEEDED", amountMinor: 12500, currency: "PHP",
  provider: "SIMULATED", simulationOnly: true, occurredAt: 1000,
};
function seed(expiresAt = 5000) {
  return {
    paymentSessions: { session_1: { id: "session_1", paymentId: "payment_1", amountMinor: 12500, currency: "PHP", status: "PENDING", provider: "SIMULATED", simulationOnly: true, expiresAt } },
    payments: { payment_1: { id: "payment_1", sessionId: "session_1", amountMinor: 12500, currency: "PHP", status: "PROCESSING", provider: "SIMULATED", simulationOnly: true } },
  };
}
describe("simulated GCash payment webhook", () => {
  it("verifies signatures and rejects tampering", () => {
    const verifier = new SimulatedWebhookVerifier("local-only-secret");
    const body = JSON.stringify(event);
    expect(verifier.verify(body, verifier.sign(body))).toBe(true);
    expect(verifier.verify(body + " ", verifier.sign(body))).toBe(false);
  });
  it("moves checkout to simulated success without succeeding the real payment", async () => {
    const db = fakeDatabase(seed());
    const result = await processSimulatedPaymentEvent(db, event);
    expect(result.status).toBe("SUCCEEDED");
    expect(db.value().paymentSessions.session_1.status).toBe("SUCCEEDED");
    expect(db.value().payments.payment_1.status).toBe("PROCESSING");
  });
  it("is idempotent for duplicate event IDs", async () => {
    const db = fakeDatabase(seed());
    await processSimulatedPaymentEvent(db, event);
    const duplicate = await processSimulatedPaymentEvent(db, event);
    expect(duplicate.duplicate).toBe(true);
    expect(Object.keys(db.value().paymentWebhookEvents)).toHaveLength(1);
  });
  it("records failure without marking payment as paid", async () => {
    const db = fakeDatabase(seed());
    const result = await processSimulatedPaymentEvent(db, { ...event, outcome: "FAILED" });
    expect(result.status).toBe("FAILED");
    expect(db.value().payments.payment_1.status).toBe("PROCESSING");
  });
  it("expires sessions instead of accepting late success", async () => {
    const db = fakeDatabase(seed(1));
    const result = await processSimulatedPaymentEvent(db, event);
    expect(result.status).toBe("EXPIRED");
    expect(db.value().payments.payment_1.status).toBe("PROCESSING");
  });
});
