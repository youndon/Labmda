import type { Database } from "firebase-admin/database";
import type { PaymentSignatureVerifier } from "./provider.js";

export interface SimulatedPaymentEvent {
  eventId: string;
  sessionId: string;
  paymentId: string;
  outcome: "SUCCEEDED" | "FAILED";
  amountMinor: number;
  currency: "PHP";
  provider: "SIMULATED";
  simulationOnly: true;
  occurredAt: number;
}

/**
 * Processes a simulated provider event using one RTDB root transaction:
 * event de-duplication and checkout-state transition commit atomically.
 * The payment intent remains PROCESSING and is never eligible for ledger posting.
 */
export async function processSimulatedPaymentEvent(
  database: Database,
  event: SimulatedPaymentEvent,
): Promise<{ duplicate: boolean; status: "SUCCEEDED" | "FAILED" | "EXPIRED" }> {
  if (event.provider !== "SIMULATED" || event.simulationOnly !== true) throw new Error("Only explicitly simulated events are accepted by this processor.");
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(event.eventId) || !/^[A-Za-z0-9_-]{1,128}$/.test(event.sessionId)
    || !/^[A-Za-z0-9_-]{1,128}$/.test(event.paymentId)) throw new Error("Webhook identifiers are invalid.");
  if (!Number.isSafeInteger(event.amountMinor) || event.amountMinor <= 0 || event.currency !== "PHP") throw new Error("Webhook amount or currency is invalid.");

  const result = await database.ref().transaction((root: Record<string, any> | null) => {
    if (!root) return;
    const events = root.paymentWebhookEvents ?? {};
    if (events[event.eventId]) return root;
    const sessions = root.paymentSessions ?? {};
    const intents = root.paymentIntents ?? {};
    const session = sessions[event.sessionId];
    const intent = intents[event.paymentId];
    if (!session || !intent || session.paymentId !== event.paymentId || intent.sessionId !== event.sessionId) return;
    if (session.simulationOnly !== true || intent.simulationOnly !== true
      || session.provider !== "SIMULATED" || intent.provider !== "SIMULATED") return;
    if (session.amountMinor !== event.amountMinor || intent.amountMinor !== event.amountMinor
      || session.currency !== event.currency || intent.currency !== event.currency) return;
    if (session.status !== "PENDING") return;
    const now = Date.now();
    const expired = session.expiresAt <= now || event.occurredAt > session.expiresAt;
    const status = expired ? "EXPIRED" : event.outcome;
    return {
      ...root,
      paymentSessions: {
        ...sessions,
        [event.sessionId]: { ...session, status, updatedAt: now, completedAt: now },
      },
      // Intentionally do not change paymentIntents/{id}.status from PROCESSING.
      paymentIntents: intents,
      paymentWebhookEvents: {
        ...events,
        [event.eventId]: {
          eventId: event.eventId,
          sessionId: event.sessionId,
          paymentId: event.paymentId,
          amountMinor: event.amountMinor,
          currency: event.currency,
          receivedOutcome: event.outcome,
          status: "PROCESSED",
          outcome: status,
          processedAt: now,
          simulationOnly: true,
        },
      },
    };
  }, undefined, false);

  const root = result.snapshot.val() as Record<string, any> | null;
  const stored = root?.paymentWebhookEvents?.[event.eventId];
  if (stored) {
    if (stored.sessionId !== event.sessionId || stored.paymentId !== event.paymentId
      || stored.receivedOutcome !== event.outcome
      || stored.amountMinor !== event.amountMinor || stored.currency !== event.currency) {
      throw new Error("Webhook event ID was reused with different payment data.");
    }
    return { duplicate: !result.committed, status: stored.outcome as "SUCCEEDED" | "FAILED" | "EXPIRED" };
  }
  throw new Error("Webhook event did not match a pending simulated payment session.");
}

/** Signature validation happens before parsing or applying any webhook payload. */
export async function processSignedPaymentWebhook(
  database: Database,
  rawBody: string,
  signature: string,
  verifier: PaymentSignatureVerifier,
): Promise<{ duplicate: boolean; status: "SUCCEEDED" | "FAILED" | "EXPIRED" }> {
  if (!verifier.verify(rawBody, signature)) throw new Error("Webhook signature verification failed.");
  let event: SimulatedPaymentEvent;
  try {
    event = JSON.parse(rawBody) as SimulatedPaymentEvent;
  } catch {
    throw new Error("Webhook body is not valid JSON.");
  }
  return processSimulatedPaymentEvent(database, event);
}
