import { createHmac, timingSafeEqual } from "node:crypto";
import type { Database } from "firebase-admin/database";

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
export interface PaymentWebhookVerifier {
  verify(rawBody: string, signature: string): boolean;
}
export class SimulatedWebhookVerifier implements PaymentWebhookVerifier {
  constructor(private readonly secret: string) {
    if (!secret) throw new Error("A development webhook secret is required.");
  }
  sign(rawBody: string): string {
    return createHmac("sha256", this.secret).update(rawBody).digest("hex");
  }
  verify(rawBody: string, signature: string): boolean {
    if (!/^[a-f0-9]{64}$/i.test(signature)) return false;
    const expected = Buffer.from(this.sign(rawBody), "hex");
    const supplied = Buffer.from(signature, "hex");
    return expected.length === supplied.length && timingSafeEqual(expected, supplied);
  }
}

/**
 * Processes the simulated provider event using a single RTDB root transaction:
 * event de-duplication and checkout-state transition commit atomically.
 * The linked Payment remains PROCESSING and can never be posted to the real ledger.
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
    const payments = root.payments ?? {};
    const session = sessions[event.sessionId];
    const payment = payments[event.paymentId];
    if (!session || !payment || session.paymentId !== event.paymentId || payment.sessionId !== event.sessionId) return;
    if (session.simulationOnly !== true || payment.simulationOnly !== true
      || session.provider !== "SIMULATED" || payment.provider !== "SIMULATED") return;
    if (session.amountMinor !== event.amountMinor || payment.amountMinor !== event.amountMinor
      || session.currency !== event.currency || payment.currency !== event.currency) return;
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
      // Intentionally do not change payments/{id}.status from PROCESSING.
      payments,
      paymentWebhookEvents: {
        ...events,
        [event.eventId]: { eventId: event.eventId, sessionId: event.sessionId, paymentId: event.paymentId, status: "PROCESSED", outcome: status, processedAt: now, simulationOnly: true },
      },
    };
  }, undefined, false);

  const root = result.snapshot.val() as Record<string, any> | null;
  const stored = root?.paymentWebhookEvents?.[event.eventId];
  if (stored) return { duplicate: !result.committed, status: stored.outcome as "SUCCEEDED" | "FAILED" | "EXPIRED" };
  throw new Error("Webhook event did not match a pending simulated payment session.");
}

/** Generic verifier adapter. Real provider implementations must be added separately and default-deny. */
export function verifyProviderWebhook(verifier: PaymentWebhookVerifier, rawBody: string, signature: string): boolean {
  return verifier.verify(rawBody, signature);
}
