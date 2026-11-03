import { randomUUID } from "node:crypto";
import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { HmacPaymentSignatureVerifier, SimulatedPaymentProvider, isSimulationRuntime } from "./provider.js";
import { processSignedPaymentWebhook, type SimulatedPaymentEvent } from "./webhooks.js";

function database() { return getDatabase(); }
const SESSION_TTL_MS = 10 * 60 * 1000;
const webhookVerifier = new HmacPaymentSignatureVerifier(process.env.SIMULATED_WEBHOOK_SECRET ?? "local-emulator-only-secret");

function requireUser(uid?: string): string {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before starting checkout.");
  return uid;
}
function assertSimulationEnabled(): void {
  if (!isSimulationRuntime()) {
    throw new HttpsError("failed-precondition", "Simulated payments are available only in the Firebase development emulator.");
  }
}

/** Creates a development-only GCash checkout from a server-owned, unexpired quote. */
export const createSimulatedGcashSession = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUser(call.auth?.uid);
  assertSimulationEnabled();
  const quoteId = String(call.data?.quoteId ?? "");
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(quoteId)) throw new HttpsError("invalid-argument", "A valid quote ID is required.");
  const quoteSnapshot = await database().ref(`pricing/quotes/${quoteId}`).get();
  if (!quoteSnapshot.exists()) throw new HttpsError("not-found", "The checkout quote was not found.");
  const quote = quoteSnapshot.val() as Record<string, any>;
  if (quote.uid !== uid) throw new HttpsError("permission-denied", "This quote belongs to another customer.");
  if (!["QUOTED", "ORDER_CREATED", "RIDE_CREATED", "DELIVERY_CREATED"].includes(quote.status) || !Number.isSafeInteger(quote.expiresAt) || quote.expiresAt <= Date.now()) {
    throw new HttpsError("failed-precondition", "The checkout quote has expired or was already used.");
  }
  if (quote.paymentMethod !== "GCASH") throw new HttpsError("failed-precondition", "This simulated checkout supports GCash only.");
  const breakdown = quote.breakdown ?? {};
  const amountMinor = breakdown.customerTotalMinor;
  if (!Number.isSafeInteger(amountMinor) || amountMinor <= 0 || breakdown.currency !== "PHP") {
    throw new HttpsError("failed-precondition", "The quote does not contain a valid PHP checkout total.");
  }

  const paymentId = typeof quote.paymentId === "string" ? quote.paymentId : randomUUID();
  const sessionId = randomUUID();
  const expiresAt = Math.min(Date.now() + SESSION_TTL_MS, quote.expiresAt);
  const provider = new SimulatedPaymentProvider(true);
  const providerSession = await provider.createSession({
    paymentId, amountMinor, currency: "PHP", payerId: uid, expiresAt,
    returnUrl: "labmda-dev://checkout/result",
  });
  const now = Date.now();
  const session = {
    id: sessionId, paymentId, quoteId, orderId: String(quote.orderId ?? ""), tripId: String(quote.tripId ?? ""), payerId: uid, method: "GCASH",
    amountMinor, currency: "PHP", status: "PENDING",
    provider: "SIMULATED", providerSessionId: providerSession.providerSessionId,
    checkoutUrl: providerSession.checkoutUrl, simulationOnly: true,
    createdAt: now, updatedAt: now, expiresAt,
  };
  const paymentIntent = {
    id: paymentId, transactionType: quote.kind === "RIDE" ? "RIDE" : "ORDER",
    orderId: String(quote.orderId ?? ""), tripId: String(quote.tripId ?? ""), deliveryId: "", payerId: uid, method: "GCASH",
    // Never mark a simulated transaction as a real successful payment.
    status: "PROCESSING", amountMinor, currency: "PHP", refundedAmountMinor: 0,
    createdAt: now, updatedAt: now, completedAt: null,
    provider: "SIMULATED", simulationOnly: true, sessionId,
  };
  const quoteClaim = await database().ref(`pricing/quotes/${quoteId}`).transaction((current: Record<string, any> | null) => {
    if (current === null) return null;
    if (current.uid !== uid || !["QUOTED", "ORDER_CREATED", "RIDE_CREATED", "DELIVERY_CREATED"].includes(current.status) || current.expiresAt <= Date.now()) return;
    return { ...current, status: "CHECKOUT_STARTED", checkoutSessionId: sessionId };
  }, undefined, false);
  if (!quoteClaim.committed) throw new HttpsError("failed-precondition", "This quote has already started checkout or has expired.");
  const updates: Record<string, unknown> = {};
  updates[`paymentSessions/${sessionId}`] = session;
  updates[`paymentIntents/${paymentId}`] = paymentIntent;
  updates[`payments/${paymentId}/status`] = "PROCESSING";
  updates[`payments/${paymentId}/provider`] = "SIMULATED";
  updates[`payments/${paymentId}/updatedAt`] = now;
  await database().ref().update(updates);
  return {
    sessionId, paymentId, status: "PENDING", amountMinor, currency: "PHP",
    checkoutUrl: providerSession.checkoutUrl, expiresAt, simulationOnly: true,
  };
});

/** Dev-only control used by the checkout test screen to simulate provider success/failure. */
export const simulateGcashPaymentResult = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUser(call.auth?.uid);
  assertSimulationEnabled();
  const sessionId = String(call.data?.sessionId ?? "");
  const outcome = call.data?.outcome;
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(sessionId) || (outcome !== "SUCCEEDED" && outcome !== "FAILED")) {
    throw new HttpsError("invalid-argument", "A valid session ID and simulated outcome are required.");
  }
  const snapshot = await database().ref(`paymentSessions/${sessionId}`).get();
  if (!snapshot.exists()) throw new HttpsError("not-found", "Payment session was not found.");
  const session = snapshot.val() as Record<string, any>;
  if (session.payerId !== uid) throw new HttpsError("permission-denied", "This payment session belongs to another customer.");
  if (session.simulationOnly !== true || session.provider !== "SIMULATED") {
    throw new HttpsError("failed-precondition", "Only development simulation sessions can be completed here.");
  }
  const event: SimulatedPaymentEvent = {
    eventId: randomUUID(), sessionId, paymentId: session.paymentId,
    outcome, amountMinor: session.amountMinor, currency: "PHP",
    provider: "SIMULATED", simulationOnly: true, occurredAt: Date.now(),
  };
  const rawBody = JSON.stringify(event);
  return processSignedPaymentWebhook(database(), rawBody, webhookVerifier.sign(rawBody), webhookVerifier);
});
