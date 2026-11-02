import { describe, expect, it } from "vitest";
import { HmacPaymentSignatureVerifier } from "./provider.js";
import { processSignedPaymentWebhook } from "./webhooks.js";
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
function seed(expiresAt = Date.now() + 5000) {
  return {
    paymentSessions: { session_1: { id: "session_1", paymentId: "payment_1", amountMinor: 12500, currency: "PHP", status: "PENDING", provider: "SIMULATED", simulationOnly: true, expiresAt } },
    paymentIntents: { payment_1: { id: "payment_1", sessionId: "session_1", amountMinor: 12500, currency: "PHP", status: "PROCESSING", provider: "SIMULATED", simulationOnly: true } },
  };
}
const secret = "local-only-secret";
const verifier = new HmacPaymentSignatureVerifier(secret);
const signed = (value: SimulatedPaymentEvent) => {
  const body = JSON.stringify(value);
  return { body, signature: verifier.sign(body) };
};
describe("simulated GCash payment webhook", () => {
  it("verifies signatures and rejects tampering", async () => {
    const db = fakeDatabase(seed());
    const payload = signed(event);
    await expect(processSignedPaymentWebhook(db, payload.body + " ", payload.signature, verifier)).rejects.toThrow("signature");
    expect(db.value().paymentSessions.session_1.status).toBe("PENDING");
  });
  it("moves checkout to simulated success without succeeding the payment intent", async () => {
    const db = fakeDatabase(seed());
    const payload = signed(event);
    const result = await processSignedPaymentWebhook(db, payload.body, payload.signature, verifier);
    expect(result.status).toBe("SUCCEEDED");
    expect(db.value().paymentSessions.session_1.status).toBe("SUCCEEDED");
    expect(db.value().paymentIntents.payment_1.status).toBe("PROCESSING");
  });
  it("is idempotent for duplicate event IDs", async () => {
    const db = fakeDatabase(seed());
    const payload = signed(event);
    await processSignedPaymentWebhook(db, payload.body, payload.signature, verifier);
    const duplicate = await processSignedPaymentWebhook(db, payload.body, payload.signature, verifier);
    expect(duplicate.duplicate).toBe(true);
    expect(Object.keys(db.value().paymentWebhookEvents)).toHaveLength(1);
  });
  it("rejects reuse of an event ID with a different outcome", async () => {
    const db = fakeDatabase(seed());
    const first = signed(event);
    await processSignedPaymentWebhook(db, first.body, first.signature, verifier);
    const reused = signed({ ...event, outcome: "FAILED" });
    await expect(processSignedPaymentWebhook(db, reused.body, reused.signature, verifier)).rejects.toThrow("reused");
  });
  it("records failure without marking the payment intent successful", async () => {
    const db = fakeDatabase(seed());
    const payload = signed({ ...event, outcome: "FAILED" });
    const result = await processSignedPaymentWebhook(db, payload.body, payload.signature, verifier);
    expect(result.status).toBe("FAILED");
    expect(db.value().paymentIntents.payment_1.status).toBe("PROCESSING");
  });
  it("expires sessions instead of accepting late success", async () => {
    const db = fakeDatabase(seed(1));
    const payload = signed(event);
    const result = await processSignedPaymentWebhook(db, payload.body, payload.signature, verifier);
    expect(result.status).toBe("EXPIRED");
    expect(db.value().paymentIntents.payment_1.status).toBe("PROCESSING");
  });
});
