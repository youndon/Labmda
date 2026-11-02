import { randomUUID } from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { getDatabase } from "firebase-admin/database";
import { defineSecret } from "firebase-functions/params";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import {
  calculateDeliveryOnlyBreakdown, calculateOrderBreakdown, calculateRideFare, calculateProviderFee,
  decimalPesosToMinor, type DiscountRule, type PaymentMethod, type PricingConfig,
} from "./pricing/engine.js";

initializeApp();
const database = getDatabase();
const mapsApiKey = defineSecret("GOOGLE_MAPS_ROUTES_API_KEY");
const QUOTE_TTL_MS = 5 * 60 * 1000;

type Point = { latitude: number; longitude: number };
type QuoteRequest = { pickup: Point; dropoff: Point; paymentMethod?: PaymentMethod; discountCode?: string };

function requireAuth(uid?: string): string {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to request a quotation.");
  return uid;
}
function validatePoint(point: Point, field: string): void {
  if (!point || !Number.isFinite(point.latitude) || !Number.isFinite(point.longitude)
    || point.latitude < -90 || point.latitude > 90 || point.longitude < -180 || point.longitude > 180) {
    throw new HttpsError("invalid-argument", `${field} coordinates are invalid.`);
  }
}
function paymentMethod(value: unknown): PaymentMethod {
  if (value === undefined || value === "CASH") return "CASH";
  if (value === "GCASH") return "GCASH";
  throw new HttpsError("invalid-argument", "Payment method must be GCASH or CASH.");
}
async function loadConfig(): Promise<PricingConfig> {
  const snapshot = await database.ref("pricing/config").get();
  if (!snapshot.exists()) throw new HttpsError("failed-precondition", "Admin pricing configuration has not been completed.");
  const config = snapshot.val() as PricingConfig;
  if (config.currency !== "PHP" || !Number.isSafeInteger(config.version) || config.version < 1) {
    throw new HttpsError("failed-precondition", "Pricing configuration is invalid.");
  }
  return config;
}
async function loadDiscount(code: string | undefined): Promise<DiscountRule | undefined> {
  if (!code) return undefined;
  const normalized = code.trim().toUpperCase();
  if (!/^[A-Z0-9_-]{2,40}$/.test(normalized)) throw new HttpsError("invalid-argument", "Discount code is invalid.");
  const snapshot = await database.ref(`pricing/discounts/${normalized}`).get();
  if (!snapshot.exists()) throw new HttpsError("not-found", "Discount code was not found.");
  return snapshot.val() as DiscountRule;
}
async function routeMetrics(request: QuoteRequest): Promise<{ distanceMeters: number; durationSeconds: number }> {
  validatePoint(request.pickup, "Pickup");
  validatePoint(request.dropoff, "Drop-off");
  const response = await fetch("https://routes.googleapis.com/directions/v2:computeRoutes", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Goog-Api-Key": mapsApiKey.value(),
      "X-Goog-FieldMask": "routes.distanceMeters,routes.duration",
    },
    body: JSON.stringify({
      origin: { location: { latLng: { latitude: request.pickup.latitude, longitude: request.pickup.longitude } } },
      destination: { location: { latLng: { latitude: request.dropoff.latitude, longitude: request.dropoff.longitude } } },
      travelMode: "DRIVE",
      routingPreference: "TRAFFIC_AWARE",
    }),
  });
  if (!response.ok) throw new HttpsError("unavailable", "Could not calculate a current road route. Please try again.");
  const body = await response.json() as { routes?: Array<{ distanceMeters?: number; duration?: string }> };
  const route = body.routes?.[0];
  const durationSeconds = route?.duration ? Number(route.duration.replace(/s$/, "")) : NaN;
  if (!route || !Number.isSafeInteger(route.distanceMeters) || !Number.isFinite(durationSeconds) || durationSeconds <= 0) {
    throw new HttpsError("unavailable", "No valid driving route was found for these locations.");
  }
  return { distanceMeters: route.distanceMeters!, durationSeconds: Math.ceil(durationSeconds) };
}
async function saveQuote(uid: string, kind: string, config: PricingConfig, request: QuoteRequest, breakdown: object, extra: object = {}) {
  const quoteId = randomUUID();
  const createdAt = Date.now();
  const expiresAt = createdAt + QUOTE_TTL_MS;
  const record = {
    quoteId, uid, kind, pricingVersion: config.version, currency: "PHP",
    request, breakdown, ...extra, createdAt, expiresAt, status: "QUOTED",
  };
  await database.ref(`pricing/quotes/${quoteId}`).set(record);
  return record;
}
async function quoteOrder(kind: "FOOD" | "SHOP", uid: string, data: Record<string, unknown>) {
  const sourceId = String(data.sourceId ?? "");
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(sourceId)) throw new HttpsError("invalid-argument", "Merchant ID is invalid.");
  const items = data.items as Array<{ itemId: string; quantity: number }> | undefined;
  if (!sourceId || !Array.isArray(items) || items.length === 0 || items.length > 50) {
    throw new HttpsError("invalid-argument", "A source and 1–50 order items are required.");
  }
  const request = data as unknown as QuoteRequest;
  const [config, route, discount] = await Promise.all([loadConfig(), routeMetrics(request), loadDiscount(data.discountCode as string | undefined)]);
  const root = kind === "FOOD" ? "restaurants" : "shops";
  const collection = kind === "FOOD" ? "meals" : "products";
  const sourceSnapshot = await database.ref(`${root}/${sourceId}`).get();
  if (!sourceSnapshot.exists()) throw new HttpsError("not-found", "The selected merchant was not found.");
  if (kind === "FOOD" && sourceSnapshot.child("isOpen").val() === false) throw new HttpsError("failed-precondition", "This restaurant is currently closed.");
  let subtotalMinor = 0;
  const pricedItems: Array<{ itemId: string; name: string; quantity: number; unitPriceMinor: number; lineTotalMinor: number }> = [];
  for (const line of items) {
    if (!line || typeof line.itemId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(line.itemId) || !Number.isSafeInteger(line.quantity) || line.quantity < 1 || line.quantity > 99) {
      throw new HttpsError("invalid-argument", "Each item must have a valid ID and quantity from 1 to 99.");
    }
    const item = await database.ref(`${root}/${sourceId}/${collection}/${line.itemId}`).get();
    if (!item.exists() || item.child("isAvailable").val() === false) throw new HttpsError("failed-precondition", "An item is unavailable.");
    if (kind === "SHOP") {
      const stock = item.child("stockQuantity").val();
      if (typeof stock === "number" && stock < line.quantity) throw new HttpsError("failed-precondition", "An item has insufficient stock.");
    }
    const rawPrice = kind === "FOOD" ? item.child("basePrice").val() : item.child("price").val();
    const unitPriceMinor = decimalPesosToMinor(rawPrice, "item price");
    const lineTotalMinor = unitPriceMinor * line.quantity;
    if (!Number.isSafeInteger(lineTotalMinor) || !Number.isSafeInteger(subtotalMinor + lineTotalMinor)) throw new HttpsError("out-of-range", "Order total is too large.");
    subtotalMinor += lineTotalMinor;
    pricedItems.push({ itemId: line.itemId, name: String(item.child("name").val() ?? ""), quantity: line.quantity, unitPriceMinor, lineTotalMinor });
  }
  const method = paymentMethod(data.paymentMethod);
  const breakdown = calculateOrderBreakdown({ config, service: kind, subtotalMinor, route, paymentMethod: method, discount });
  return saveQuote(uid, kind, config, request, breakdown, { sourceId, items: pricedItems, route, paymentMethod: method });
}

export const quoteRide = onCall({ secrets: [mapsApiKey], enforceAppCheck: true }, async (call) => {
  const uid = requireAuth(call.auth?.uid);
  const data = call.data as QuoteRequest & { rideType?: string };
  const [config, route] = await Promise.all([loadConfig(), routeMetrics(data)]);
  const fare = calculateRideFare(config.ride, route);
  const method = paymentMethod(data.paymentMethod);
  const fee = calculateProviderFee(config, method, fare.fareMinor);
  const providerFeeMinor = fee.amount;
  const customerTotalMinor = fare.fareMinor + (fee.payer === "CUSTOMER" ? providerFeeMinor : 0);
  return saveQuote(uid, "RIDE", config, data, {
    currency: "PHP", fareMinor: fare.fareMinor, providerFeeMinor, customerTotalMinor,
    platformCommissionMinor: fare.platformCommissionMinor, driverEarningsMinor: fare.driverEarningsMinor,
    platformNetBeforeOtherCostsMinor: fare.platformCommissionMinor - (method === "GCASH" && fee.payer === "PLATFORM" ? providerFeeMinor : 0),
  }, { route, paymentMethod: method, rideType: data.rideType ?? "STANDARD" });
});

export const quoteFoodOrder = onCall({ secrets: [mapsApiKey], enforceAppCheck: true }, async (call) => {
  const uid = requireAuth(call.auth?.uid);
  return quoteOrder("FOOD", uid, call.data as Record<string, unknown>);
});
export const quoteShopOrder = onCall({ secrets: [mapsApiKey], enforceAppCheck: true }, async (call) => {
  const uid = requireAuth(call.auth?.uid);
  return quoteOrder("SHOP", uid, call.data as Record<string, unknown>);
});
export const quoteDelivery = onCall({ secrets: [mapsApiKey], enforceAppCheck: true }, async (call) => {
  const uid = requireAuth(call.auth?.uid);
  const data = call.data as QuoteRequest;
  const [config, route] = await Promise.all([loadConfig(), routeMetrics(data)]);
  const method = paymentMethod(data.paymentMethod);
  const breakdown = calculateDeliveryOnlyBreakdown({ config, route, paymentMethod: method });
  return saveQuote(uid, "DELIVERY", config, data, breakdown, { route, paymentMethod: method });
});


// Phase 04 — authenticated cash collection, settlement, remittance and reconciliation.
export {
  confirmCashCollection,
  confirmMerchantCashSettlement,
  confirmCashRemittance,
  getMyCashOutstanding,
  reconcileCashPayments,
} from "./finance/cash-operations.js";

// Phase 05 — development-only simulated GCash checkout, webhook and refund lifecycle.
export { createSimulatedGcashSession, simulateGcashPaymentResult } from "./payments/payment-sessions.js";
export { createSimulatedGcashRefund } from "./payments/refunds.js";
export { expireSimulatedGcashSessions } from "./payments/expiration.js";

// Phase 06 — provider earnings, private payout profiles and admin-managed payouts.
export {
  saveMyPayoutProfile,
  getMyPayoutProfile,
  getMyEarnings,
  getMyEarningsAnalytics,
  requestMyPayout,
  getMyPayoutHistory,
  getPendingPayoutRequests,
  approvePayoutRequest,
  rejectPayoutRequest,
  markPayoutAsPaid,
} from "./finance/payouts.js";

// Phase 07 — administrator financial overview.
export { getAdminFinancialOverview } from "./finance/admin-overview.js";

// Phase 08 — administrator pricing and commission configuration.
export { getAdminPricingConfig, updateAdminPricingConfig } from "./finance/admin-pricing.js";
