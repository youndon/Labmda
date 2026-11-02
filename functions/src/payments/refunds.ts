import { randomUUID } from "node:crypto";
import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { SimulatedPaymentRefundProvider, isSimulationRuntime } from "./provider.js";

function requireUser(uid?: string): string {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to request a refund.");
  return uid;
}

/**
 * Development refund lifecycle only. No money is returned and no ledger entries
 * are written. Production refund adapters must be implemented and verified separately.
 */
export const createSimulatedGcashRefund = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUser(call.auth?.uid);
  if (!isSimulationRuntime()) throw new HttpsError("failed-precondition", "Simulated refunds are available only in the Firebase development emulator.");
  const sessionId = String(call.data?.sessionId ?? "");
  const reason = String(call.data?.reason ?? "").trim().slice(0, 500);
  const requestedAmount = call.data?.amountMinor;
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(sessionId) || !reason) {
    throw new HttpsError("invalid-argument", "A valid session ID and refund reason are required.");
  }
  const db = getDatabase();
  const ref = db.ref(`paymentSessions/${sessionId}`);
  const snapshot = await ref.get();
  if (!snapshot.exists()) throw new HttpsError("not-found", "Payment session was not found.");
  const session = snapshot.val() as Record<string, any>;
  if (session.payerId !== uid) throw new HttpsError("permission-denied", "This session belongs to another customer.");
  if (session.provider !== "SIMULATED" || session.simulationOnly !== true || session.status !== "SUCCEEDED") {
    throw new HttpsError("failed-precondition", "Only a simulated successful checkout can enter the refund demonstration.");
  }
  const amountMinor = requestedAmount === undefined ? session.amountMinor : requestedAmount;
  if (!Number.isSafeInteger(amountMinor) || amountMinor <= 0 || amountMinor > session.amountMinor) {
    throw new HttpsError("invalid-argument", "Refund amount must be a positive amount no greater than the simulated checkout total.");
  }
  const refundId = randomUUID();
  const now = Date.now();
  const provider = new SimulatedPaymentRefundProvider(true);
  const providerRefund = await provider.createRefund({
    paymentId: session.paymentId, providerSessionId: session.providerSessionId,
    amountMinor, currency: "PHP", reason,
  });
  const refund = {
    id: refundId, sessionId, paymentId: session.paymentId, requestedBy: uid,
    amountMinor, currency: "PHP", reason, status: providerRefund.status,
    provider: providerRefund.provider, providerRefundId: providerRefund.providerRefundId,
    simulationOnly: true, createdAt: now, updatedAt: now, completedAt: now,
  };
  await db.ref(`paymentRefunds/${refundId}`).set(refund);
  return { refundId, status: refund.status, amountMinor, currency: "PHP", simulationOnly: true };
});
