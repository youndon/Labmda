import { getDatabase } from "firebase-admin/database";
import { onSchedule } from "firebase-functions/v2/scheduler";

/** Remove expired quote records in bounded batches to keep RTDB storage tidy. */
export const cleanupExpiredPricingQuotes = onSchedule("every 60 minutes", async () => {
  const now = Date.now();
  const snapshot = await getDatabase().ref("pricing/quotes")
    .orderByChild("expiresAt")
    .endAt(now)
    .limitToFirst(500)
    .get();
  if (!snapshot.exists()) return;

  const removals: Record<string, null> = {};
  snapshot.forEach((child) => {
    const quote = child.val() as Record<string, unknown>;
    const isStaleCreation = quote.status === "CREATING"
      && typeof quote.creationStartedAt === "number"
      && now - quote.creationStartedAt < 10 * 60 * 1000;
    if (!isStaleCreation) removals[`pricing/quotes/${child.key}`] = null;
  });
  if (Object.keys(removals).length) await getDatabase().ref().update(removals);
});
