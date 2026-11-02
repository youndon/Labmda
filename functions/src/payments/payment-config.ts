import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import type { PaymentMethod } from "../pricing/engine.js";

export const PAYMENT_CONFIG_PATH = "paymentConfig";
export const PAYMENT_SERVICES = ["FOOD", "SHOP", "DELIVERY", "RIDE"] as const;
export type PaymentService = (typeof PAYMENT_SERVICES)[number];

export interface PaymentConfiguration {
  version: number;
  provider: "BDO_CHECKOUT";
  currency: "PHP";
  enabledMethods: PaymentMethod[];
  services: Record<PaymentService, boolean>;
  checkoutMode: "MANUAL_LINK";
  payoutMode: "MANUAL";
  updatedAt: number;
  updatedBy: string;
}

export const DEFAULT_PAYMENT_CONFIGURATION: PaymentConfiguration = {
  version: 1,
  provider: "BDO_CHECKOUT",
  currency: "PHP",
  enabledMethods: ["GCASH", "CASH"],
  services: { FOOD: true, SHOP: true, DELIVERY: true, RIDE: true },
  checkoutMode: "MANUAL_LINK",
  payoutMode: "MANUAL",
  updatedAt: 0,
  updatedBy: "SYSTEM",
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function validatePaymentConfiguration(value: unknown): PaymentConfiguration {
  if (!isRecord(value)) throw new Error("Payment configuration must be an object.");
  if (value.provider !== "BDO_CHECKOUT" || value.currency !== "PHP") {
    throw new Error("Payment provider and currency must be BDO_CHECKOUT and PHP.");
  }
  if (!Array.isArray(value.enabledMethods) || value.enabledMethods.length === 0
    || value.enabledMethods.some((method) => method !== "GCASH" && method !== "CASH")
    || new Set(value.enabledMethods).size !== value.enabledMethods.length) {
    throw new Error("At least one unique supported payment method (GCASH or CASH) must be enabled.");
  }
  if (!isRecord(value.services) || PAYMENT_SERVICES.some((service) => typeof value.services[service] !== "boolean")) {
    throw new Error("FOOD, SHOP, DELIVERY and RIDE must each have an explicit enabled state.");
  }
  if (value.checkoutMode !== "MANUAL_LINK" || value.payoutMode !== "MANUAL") {
    throw new Error("BDO API checkout and automated payouts are unavailable until BDO approves and documents those integrations.");
  }
  if (!Number.isSafeInteger(value.version) || (value.version as number) < 1
    || !Number.isSafeInteger(value.updatedAt) || (value.updatedAt as number) < 0
    || typeof value.updatedBy !== "string" || !value.updatedBy) {
    throw new Error("Payment configuration metadata is invalid.");
  }
  return {
    version: value.version as number,
    provider: "BDO_CHECKOUT",
    currency: "PHP",
    enabledMethods: [...value.enabledMethods] as PaymentMethod[],
    services: {
      FOOD: value.services.FOOD as boolean,
      SHOP: value.services.SHOP as boolean,
      DELIVERY: value.services.DELIVERY as boolean,
      RIDE: value.services.RIDE as boolean,
    },
    checkoutMode: "MANUAL_LINK",
    payoutMode: "MANUAL",
    updatedAt: value.updatedAt as number,
    updatedBy: value.updatedBy,
  };
}

export async function loadPaymentConfiguration(): Promise<PaymentConfiguration> {
  const snapshot = await getDatabase().ref(`${PAYMENT_CONFIG_PATH}/current`).get();
  if (!snapshot.exists()) return { ...DEFAULT_PAYMENT_CONFIGURATION, services: { ...DEFAULT_PAYMENT_CONFIGURATION.services }, enabledMethods: [...DEFAULT_PAYMENT_CONFIGURATION.enabledMethods] };
  try {
    return validatePaymentConfiguration(snapshot.val());
  } catch (error) {
    throw new HttpsError("failed-precondition", error instanceof Error ? error.message : "Payment configuration is invalid.");
  }
}

export async function assertPaymentMethodEnabled(method: PaymentMethod): Promise<void> {
  const config = await loadPaymentConfiguration();
  if (!config.enabledMethods.includes(method)) {
    throw new HttpsError("failed-precondition", `${method} payments are currently unavailable.`);
  }
}

export async function assertPaymentServiceEnabled(service: PaymentService): Promise<void> {
  const config = await loadPaymentConfiguration();
  if (!config.services[service]) {
    throw new HttpsError("failed-precondition", `${service.toLowerCase()} payments are currently unavailable.`);
  }
}

/** Returns only customer-safe configuration. Provider credentials and admin metadata are never exposed. */
export const getPaymentConfiguration = onCall({ enforceAppCheck: true }, async (call) => {
  if (!call.auth?.uid) throw new HttpsError("unauthenticated", "Sign in to view available payment methods.");
  const config = await loadPaymentConfiguration();
  return {
    version: config.version,
    provider: config.provider,
    currency: config.currency,
    enabledMethods: config.enabledMethods,
    services: config.services,
    checkoutMode: config.checkoutMode,
    payoutMode: config.payoutMode,
  };
});

/** Admins may enable/disable currently supported methods and app services; provider and manual payout mode stay fixed. */
export const updateAdminPaymentConfiguration = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = call.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to continue.");
  if (call.auth?.token?.admin !== true) throw new HttpsError("permission-denied", "Administrator access is required.");

  const data = call.data as Record<string, unknown> | undefined;
  if (!isRecord(data)) throw new HttpsError("invalid-argument", "Payment configuration changes are required.");
  const current = await loadPaymentConfiguration();

  let enabledMethods = current.enabledMethods;
  if (data.enabledMethods !== undefined) {
    if (!Array.isArray(data.enabledMethods) || data.enabledMethods.length === 0
      || data.enabledMethods.some((method) => method !== "GCASH" && method !== "CASH")
      || new Set(data.enabledMethods).size !== data.enabledMethods.length) {
      throw new HttpsError("invalid-argument", "Enable at least one unique supported method: GCASH or CASH.");
    }
    enabledMethods = [...data.enabledMethods] as PaymentMethod[];
  }

  const services = { ...current.services };
  if (data.services !== undefined) {
    if (!isRecord(data.services) || Object.entries(data.services).some(([key, value]) =>
      !PAYMENT_SERVICES.includes(key as PaymentService) || typeof value !== "boolean")) {
      throw new HttpsError("invalid-argument", "Service settings must contain boolean values for FOOD, SHOP, DELIVERY or RIDE.");
    }
    Object.assign(services, data.services);
  }

  const configRef = getDatabase().ref(PAYMENT_CONFIG_PATH);
  let saved: PaymentConfiguration | undefined;
  const result = await configRef.transaction((root: Record<string, unknown> | null) => {
    const rootRecord = isRecord(root) ? root : {};
    const stored = rootRecord.current;
    const base = stored === undefined ? current : validatePaymentConfiguration(stored);
    const next: PaymentConfiguration = {
      ...base,
      version: base.version + 1,
      enabledMethods,
      services,
      updatedAt: Date.now(),
      updatedBy: uid,
    };
    const history = isRecord(rootRecord.history) ? { ...rootRecord.history } : {};
    history[String(next.version)] = next;
    saved = next;
    return { ...rootRecord, current: next, history };
  }, undefined, false);

  if (!result.committed || !saved) throw new HttpsError("aborted", "Payment configuration changed concurrently. Please retry.");
  return { saved: true, version: saved.version, enabledMethods: saved.enabledMethods, services: saved.services };
});
