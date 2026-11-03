import { HttpsError } from "firebase-functions/v2/https";

export type MerchantPickup = { latitude: number; longitude: number };

/** Reads only a merchant-owned, persisted pickup point. Client values are never a fallback. */
export function requireMerchantPickup(latitude: unknown, longitude: unknown): MerchantPickup {
  if (typeof latitude !== "number" || typeof longitude !== "number"
    || !Number.isFinite(latitude) || latitude < -90 || latitude > 90
    || !Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
    throw new HttpsError(
      "failed-precondition",
      "This merchant has no valid saved pickup location. Ask the merchant to update it before ordering."
    );
  }
  return { latitude, longitude };
}

/** Prevents a quote from being used after the merchant changes its saved pickup point. */
export function assertQuotePickupMatches(quotedPickup: unknown, currentPickup: MerchantPickup): void {
  const point = quotedPickup as Partial<MerchantPickup> | null | undefined;
  if (!point || point.latitude !== currentPickup.latitude || point.longitude !== currentPickup.longitude) {
    throw new HttpsError(
      "failed-precondition",
      "The merchant pickup location changed after this quote. Request a new delivery quote."
    );
  }
}
