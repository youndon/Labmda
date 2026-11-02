import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";

const LIMIT = 100;

function requireAdmin(claims: Record<string, unknown> | undefined): void {
  if (claims?.admin !== true) throw new HttpsError("permission-denied", "Administrator access is required.");
}
function text(value: unknown): string { return typeof value === "string" ? value : ""; }
function minor(value: unknown): number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : 0;
}
function timestamp(value: unknown): number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : 0;
}
function records<T>(snapshot: any, map: (id: string, value: Record<string, unknown>) => T): T[] {
  const result: T[] = [];
  snapshot.forEach((child: any) => {
    const value = child.val();
    if (value && typeof value === "object" && !Array.isArray(value)) result.push(map(child.key ?? "", value as Record<string, unknown>));
  });
  return result.reverse();
}

/** Read-only admin view. It reports persisted records without changing payment state. */
export const getAdminPaymentMonitor = onCall({ enforceAppCheck: true }, async (call) => {
  if (!call.auth?.uid) throw new HttpsError("unauthenticated", "Sign in to continue.");
  requireAdmin(call.auth.token as Record<string, unknown>);
  const database = getDatabase();
  const [payments, sessions, refunds, simulatedRefunds] = await Promise.all([
    database.ref("payments").orderByChild("createdAt").limitToLast(LIMIT).get(),
    database.ref("paymentSessions").orderByChild("createdAt").limitToLast(LIMIT).get(),
    database.ref("refunds").orderByChild("createdAt").limitToLast(LIMIT).get(),
    database.ref("paymentRefunds").orderByChild("createdAt").limitToLast(LIMIT).get(),
  ]);

  const paymentRecords = records(payments, (id, value) => ({
    id: text(value.id) || id, paymentId: text(value.id) || id,
    transactionType: text(value.transactionType), orderId: text(value.orderId), tripId: text(value.tripId),
    payerId: text(value.payerId), method: text(value.method), status: text(value.status),
    amountMinor: minor(value.amountMinor), refundedAmountMinor: minor(value.refundedAmountMinor),
    currency: text(value.currency) || "PHP", provider: text(value.provider),
    simulationOnly: value.simulationOnly === true, createdAt: timestamp(value.createdAt), updatedAt: timestamp(value.updatedAt),
  }));

  const checkoutSessions = records(sessions, (id, value) => ({
    id: text(value.id) || id, paymentId: text(value.paymentId), payerId: text(value.payerId),
    method: text(value.method), status: text(value.status), amountMinor: minor(value.amountMinor),
    currency: text(value.currency) || "PHP", provider: text(value.provider), simulationOnly: value.simulationOnly === true,
    createdAt: timestamp(value.createdAt), updatedAt: timestamp(value.updatedAt), expiresAt: timestamp(value.expiresAt),
  }));

  const refundRecords = [
    ...records(refunds, (id, value) => ({
      id: text(value.id) || id, paymentId: text(value.paymentId), requestedBy: text(value.requestedBy),
      amountMinor: minor(value.amountMinor), currency: text(value.currency) || "PHP", reason: text(value.reason),
      status: text(value.status), provider: text(value.provider), simulationOnly: value.simulationOnly === true,
      source: "REFUNDS", createdAt: timestamp(value.createdAt), updatedAt: timestamp(value.updatedAt),
      completedAt: timestamp(value.completedAt),
    })),
    ...records(simulatedRefunds, (id, value) => ({
      id: text(value.id) || id, paymentId: text(value.paymentId), requestedBy: text(value.requestedBy),
      amountMinor: minor(value.amountMinor), currency: text(value.currency) || "PHP", reason: text(value.reason),
      status: text(value.status), provider: text(value.provider), simulationOnly: value.simulationOnly === true,
      source: "PAYMENT_REFUNDS", createdAt: timestamp(value.createdAt), updatedAt: timestamp(value.updatedAt),
      completedAt: timestamp(value.completedAt),
    })),
  ].sort((a, b) => b.createdAt - a.createdAt).slice(0, LIMIT * 2);

  return {
    currency: "PHP", generatedAt: Date.now(), sampleLimitPerSource: LIMIT,
    payments: paymentRecords, sessions: checkoutSessions, refunds: refundRecords,
    liveRefundExecutionAvailable: false,
  };
});
