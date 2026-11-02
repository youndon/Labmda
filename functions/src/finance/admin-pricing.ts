import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";

type PricingConfig = {
  version: number;
  currency: "PHP";
  ride: {
    baseFareMinor: number;
    perKmMinor: number;
    perMinuteMinor: number;
    minimumFareMinor: number;
    platformCommissionBps: number;
  };
  delivery: {
    baseFeeMinor: number;
    perKmMinor: number;
    minimumFeeMinor: number;
    platformCommissionBps: number;
  };
  food: { commissionBps: number };
  shop: { commissionBps: number };
  providerFees: {
    GCASH: { rateBps: number; fixedFeeMinor: number; payer: "PLATFORM" | "CUSTOMER" };
  };
};

function requireAdmin(claims: Record<string, unknown> | undefined): void {
  if (claims?.admin !== true) {
    throw new HttpsError("permission-denied", "Administrator access is required.");
  }
}

function integer(value: unknown, field: string, maximum = Number.MAX_SAFE_INTEGER): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0 || value > maximum) {
    throw new HttpsError("invalid-argument", `${field} must be a non-negative whole number.`);
  }
  return value;
}

function validateConfig(input: unknown, version: number): PricingConfig {
  if (!input || typeof input !== "object") throw new HttpsError("invalid-argument", "Pricing configuration is required.");
  const value = input as Record<string, any>;
  const ride = value.ride ?? {};
  const delivery = value.delivery ?? {};
  const food = value.food ?? {};
  const shop = value.shop ?? {};
  const gcash = value.providerFees?.GCASH ?? {};
  const payer = gcash.payer;
  if (payer !== "PLATFORM" && payer !== "CUSTOMER") {
    throw new HttpsError("invalid-argument", "GCash provider-fee payer must be PLATFORM or CUSTOMER.");
  }
  return {
    version,
    currency: "PHP",
    ride: {
      baseFareMinor: integer(ride.baseFareMinor, "Ride base fare"),
      perKmMinor: integer(ride.perKmMinor, "Ride price per km"),
      perMinuteMinor: integer(ride.perMinuteMinor, "Ride price per minute"),
      minimumFareMinor: integer(ride.minimumFareMinor, "Ride minimum fare"),
      platformCommissionBps: integer(ride.platformCommissionBps, "Ride commission", 10000),
    },
    delivery: {
      baseFeeMinor: integer(delivery.baseFeeMinor, "Delivery base fee"),
      perKmMinor: integer(delivery.perKmMinor, "Delivery price per km"),
      minimumFeeMinor: integer(delivery.minimumFeeMinor, "Delivery minimum fee"),
      platformCommissionBps: integer(delivery.platformCommissionBps, "Delivery commission", 10000),
    },
    food: { commissionBps: integer(food.commissionBps, "Food commission", 10000) },
    shop: { commissionBps: integer(shop.commissionBps, "Shop commission", 10000) },
    providerFees: {
      GCASH: {
        rateBps: integer(gcash.rateBps, "GCash provider-fee rate", 10000),
        fixedFeeMinor: integer(gcash.fixedFeeMinor, "GCash fixed provider fee"),
        payer,
      },
    },
  };
}

export const getAdminPricingConfig = onCall({ enforceAppCheck: true }, async (call) => {
  if (!call.auth?.uid) throw new HttpsError("unauthenticated", "Sign in to continue.");
  requireAdmin(call.auth.token as Record<string, unknown>);
  const snapshot = await getDatabase().ref("pricing/config").get();
  return { configured: snapshot.exists(), config: snapshot.exists() ? snapshot.val() : null };
});

export const updateAdminPricingConfig = onCall({ enforceAppCheck: true }, async (call) => {
  if (!call.auth?.uid) throw new HttpsError("unauthenticated", "Sign in to continue.");
  requireAdmin(call.auth.token as Record<string, unknown>);
  const ref = getDatabase().ref("pricing/config");
  let saved: PricingConfig | null = null;
  const result = await ref.transaction((current) => {
    const previousVersion = Number.isSafeInteger(current?.version) && current.version > 0 ? current.version : 0;
    if (previousVersion >= 2_000_000_000) throw new Error("Pricing configuration version limit reached.");
    saved = validateConfig(call.data?.config, previousVersion + 1);
    return saved;
  }, undefined, false);
  if (!result.committed || !saved) throw new HttpsError("aborted", "Pricing configuration was not saved. Please retry.");
  await getDatabase().ref(`pricing/configHistory/${saved.version}`).set({
    ...saved,
    updatedAt: Date.now(),
    updatedBy: call.auth.uid,
  });
  return { success: true, config: saved };
});
