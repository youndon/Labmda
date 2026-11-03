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

  const updates: Record<string, null> = {};
  snapshot.forEach((child) => {
    const quote = child.val() as Record<string, unknown>;
    const isStaleCreation = quote.status === "CREATING"
      && typeof quote.creationStartedAt === "number"
      && now - quote.creationStartedAt < 10 * 60 * 1000;
    if (!isStaleCreation) updates[child.key!] = null;
  });
  if (Object.keys(updates).length) {
    const removals: Record<string, null> = {};
    Object.keys(updates).forEach((quoteId) => {
      removals[`pricing/quotes/${quoteId}`] = null;
    });
    await getDatabase().ref().update(removals);
  }
});
