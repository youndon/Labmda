import type { Database } from "firebase-admin/database";

export interface SimulatedRefundRecord {
  id: string; sessionId: string; paymentId: string; requestedBy: string;
  amountMinor: number; currency: "PHP"; reason: string; status: string;
  provider: string; providerRefundId: string; simulationOnly: boolean;
  createdAt: number; updatedAt: number; completedAt: number;
}

/** Commit a simulated refund and its cumulative amount atomically. */
export async function commitSimulatedRefund(
  database: Database,
  args: { sessionId: string; uid: string; amountMinor: number; now: number; refundId: string; refund: SimulatedRefundRecord },
) {
  const { sessionId, uid, amountMinor, now, refundId, refund } = args;
  return database.ref().transaction((root: Record<string, any> | null) => {
    // Preserve an initial null cache value to trigger RTDB's server hash retry.
    if (root === null) return null;
    const current = root.paymentSessions?.[sessionId];
    if (!current || current.payerId !== uid || current.status !== "SUCCEEDED"
      || current.provider !== "SIMULATED" || current.simulationOnly !== true) return;
    const refunded = current.refundedAmountMinor ?? 0;
    if (!Number.isSafeInteger(refunded) || refunded + amountMinor > current.amountMinor) return;
    if (root.paymentRefunds?.[refundId]) return;
    return {
      ...root,
      paymentSessions: {
        ...root.paymentSessions,
        [sessionId]: { ...current, refundedAmountMinor: refunded + amountMinor, updatedAt: now },
      },
      paymentRefunds: { ...(root.paymentRefunds ?? {}), [refundId]: refund },
    };
  }, undefined, false);
}
