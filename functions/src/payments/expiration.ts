import { getDatabase } from "firebase-admin/database";
import { onSchedule } from "firebase-functions/v2/scheduler";

/** Periodically expires abandoned development checkout sessions. */
const expireSimulatedGcashSessionsHandler = onSchedule("every 1 minutes", async () => {
  const db = getDatabase();
  const snapshot = await db.ref("paymentSessions").orderByChild("status").equalTo("PENDING").get();
  if (!snapshot.exists()) return;
  const now = Date.now();
  const updates: Record<string, unknown> = {};
  snapshot.forEach((child) => {
    const session = child.val() as Record<string, unknown>;
    if (typeof session.expiresAt === "number" && session.expiresAt <= now
      && session.provider === "SIMULATED" && session.simulationOnly === true) {
      updates[`paymentSessions/${child.key}/status`] = "EXPIRED";
      updates[`paymentSessions/${child.key}/updatedAt`] = now;
      updates[`paymentSessions/${child.key}/completedAt`] = now;
    }
  });
  if (Object.keys(updates).length) await db.ref().update(updates);
});

// Keep the simulated checkout scheduler available to local emulators only.
export const expireSimulatedGcashSessions = process.env.FUNCTIONS_EMULATOR === "true"
  ? expireSimulatedGcashSessionsHandler
  : null;
