import { randomUUID } from "node:crypto";
import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { SimulatedPaymentRefundProvider, isSimulationRuntime } from "./provider.js";
import { commitSimulatedRefund } from "./refund-transaction.js";

function requireUser(uid?: string): string {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to request a refund.");
  return uid;
}

/** Development refund lifecycle only. No real money or ledger entries are created. */
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
  const snapshot = await db.ref(`paymentSessions/${sessionId}`).get();
  if (!snapshot.exists()) throw new HttpsError("not-found", "Payment session was not found.");
  const session = snapshot.val() as Record<string, any>;
  if (session.payerId !== uid) throw new HttpsError("permission-denied", "This session belongs to another customer.");
  if (session.provider !== "SIMULATED" || session.simulationOnly !== true || session.status !== "SUCCEEDED") {
    throw new HttpsError("failed-precondition", "Only a simulated successful checkout can enter the refund demonstration.");
  }
  const amountMinor = requestedAmount === undefined ? session.amountMinor : requestedAmount;
  if (!Number.isSafeInteger(amountMinor) || amountMinor <= 0) {
    throw new HttpsError("invalid-argument", "Refund amount must be a positive PHP-centavo amount.");
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
  const result = await commitSimulatedRefund(db, {
    sessionId, uid, amountMinor, now, refundId, refund,
  });
  const savedRoot = result.snapshot.val() as Record<string, any> | null;
  const savedRefund = savedRoot?.paymentRefunds?.[refundId];
  if (!result.committed || !savedRefund || savedRefund.sessionId !== sessionId
    || savedRefund.requestedBy !== uid || savedRefund.amountMinor !== amountMinor) {
    throw new HttpsError("failed-precondition", "The refund exceeds the remaining refundable amount or the session is no longer refundable.");
  }
  return { refundId, status: refund.status, amountMinor, currency: "PHP", simulationOnly: true };
});
