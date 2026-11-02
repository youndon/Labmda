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
  const expectedVersion = integer(call.data?.expectedVersion, "Expected pricing version", 2_000_000_000);
  const rootRef = getDatabase().ref("pricing");
  const updatedAt = Date.now();
  let versionConflict = false;
  const result = await rootRef.transaction((current) => {
    const root = (current ?? {}) as Record<string, any>;
    const currentConfig = root.config ?? null;
    const previousVersion = Number.isSafeInteger(currentConfig?.version) && currentConfig.version > 0 ? currentConfig.version : 0;
    if (previousVersion !== expectedVersion) {
      versionConflict = true;
      return;
    }
    if (previousVersion >= 2_000_000_000) throw new HttpsError("out-of-range", "Pricing configuration version limit reached.");
    const saved = validateConfig(call.data?.config, previousVersion + 1);
    const history = (root.configHistory ?? {}) as Record<string, unknown>;
    return {
      ...root,
      config: saved,
      configHistory: {
        ...history,
        [saved.version]: { ...saved, updatedAt, updatedBy: call.auth!.uid },
      },
    };
  }, undefined, false);
  if (!result.committed) {
    if (versionConflict) throw new HttpsError("aborted", "Pricing changed since you opened this screen. Refresh and review the latest rates before saving.");
    throw new HttpsError("aborted", "Pricing configuration was not saved. Please retry.");
  }
  const saved = result.snapshot.child("config").val() as PricingConfig;
  return { success: true, config: saved };
});
