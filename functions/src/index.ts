import { randomUUID } from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { getDatabase } from "firebase-admin/database";
import { defineSecret } from "firebase-functions/params";
import { assertPaymentMethodEnabled, assertPaymentServiceEnabled } from "./payments/payment-config.js";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { requireMerchantPickup } from "./operations/merchant-location.js";
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
function validateAddress(value: unknown, field: string): string {
  if (typeof value !== "string") throw new HttpsError("invalid-argument", `${field} address is required.`);
  const address = value.trim();
  if (address.length < 2 || address.length > 250 || /[\u0000-\u001F\u007F]/.test(address)) {
    throw new HttpsError("invalid-argument", `${field} address must be 2–250 valid characters.`);
  }
  return address;
}
async function enforceRouteQuoteRateLimit(uid: string): Promise<void> {
  const now = Date.now();
  const ref = database.ref(`rateLimits/routeQuotes/${uid}`);
  const result = await ref.transaction((current: { windowStartedAt?: number; count?: number } | null) => {
    const start = typeof current?.windowStartedAt === "number" ? current.windowStartedAt : 0;
    const count = typeof current?.count === "number" ? current.count : 0;
    if (now - start < 60_000 && count >= 10) return;
    return now - start >= 60_000
      ? { windowStartedAt: now, count: 1 }
      : { windowStartedAt: start || now, count: count + 1 };
  }, undefined, false);
  if (!result.committed) throw new HttpsError("resource-exhausted", "Too many fare or delivery quotes. Please wait a minute and try again.");
}
function validatePoint(point: Point, field: string): void {
  if (!point || !Number.isFinite(point.latitude) || !Number.isFinite(point.longitude)
    || point.latitude < -90 || point.latitude > 90 || point.longitude < -180 || point.longitude > 180) {
    throw new HttpsError("invalid-argument", `${field} coordinates are invalid.`);
  }
}
async function paymentMethod(value: unknown): Promise<PaymentMethod> {
  const method: PaymentMethod = value === undefined || value === "CASH"
    ? "CASH"
    : value === "GCASH"
      ? "GCASH"
      : (() => { throw new HttpsError("invalid-argument", "Payment method must be GCASH or CASH."); })();
  await assertPaymentMethodEnabled(method);
  return method;
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
async function routeMetrics(uid: string, request: QuoteRequest): Promise<{ distanceMeters: number; durationSeconds: number }> {
  await enforceRouteQuoteRateLimit(uid);
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
  const items = data.items as Array<{ itemId: string; quantity: number; specialInstructions?: string }> | undefined;
  if (!sourceId || !Array.isArray(items) || items.length === 0 || items.length > 50) {
    throw new HttpsError("invalid-argument", "A source and 1–50 order items are required.");
  }
  const clientRequest = data as unknown as QuoteRequest;
  const dropoffAddress = validateAddress(data.dropoffAddress, "Drop-off");
  validatePoint(clientRequest.dropoff, "Drop-off");
  const root = kind === "FOOD" ? "restaurants" : "shops";
  const collection = kind === "FOOD" ? "restaurantMeals" : "shopProducts";
  const sourceSnapshot = await database.ref(`${root}/${sourceId}`).get();
  if (!sourceSnapshot.exists()) throw new HttpsError("not-found", "The selected merchant was not found.");
  if (kind === "FOOD" && sourceSnapshot.child("isOpen").val() === false) throw new HttpsError("failed-precondition", "This restaurant is currently closed.");
  const storedLatitude = sourceSnapshot.child("latitude").val();
  const storedLongitude = sourceSnapshot.child("longitude").val();
  if (typeof storedLatitude !== "number" || typeof storedLongitude !== "number") {
    throw new HttpsError("failed-precondition", "This merchant has no verified pickup location. Contact the merchant before ordering.");
  }
  const pickup = requireMerchantPickup(storedLatitude, storedLongitude);
  const request: QuoteRequest = { ...clientRequest, pickup, dropoffAddress } as QuoteRequest;
  const [config, route, discount] = await Promise.all([loadConfig(), routeMetrics(uid, request), loadDiscount(data.discountCode as string | undefined)]);
  for (const line of items) {
    if (!line || typeof line.itemId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(line.itemId) || !Number.isSafeInteger(line.quantity) || line.quantity < 1 || line.quantity > 99) {
      throw new HttpsError("invalid-argument", "Each item must have a valid ID and quantity from 1 to 99.");
    }
  }
  const pricedItems = await Promise.all(items.map(async (line) => {
    const item = await database.ref(`${collection}/${sourceId}/${line.itemId}`).get();
    if (!item.exists() || item.child("isAvailable").val() === false) throw new HttpsError("failed-precondition", "An item is unavailable.");
    if (kind === "SHOP") {
      const stock = item.child("stockQuantity").val();
      if (typeof stock === "number" && stock < line.quantity) throw new HttpsError("failed-precondition", "An item has insufficient stock.");
    }
    const rawPrice = kind === "FOOD" ? item.child("basePrice").val() : item.child("price").val();
    const unitPriceMinor = decimalPesosToMinor(rawPrice, "item price");
    const lineTotalMinor = unitPriceMinor * line.quantity;
    if (!Number.isSafeInteger(lineTotalMinor)) throw new HttpsError("out-of-range", "Order total is too large.");
    const specialInstructions = typeof line.specialInstructions === "string" ? line.specialInstructions.trim().slice(0, 500) : "";
    return { itemId: line.itemId, name: String(item.child("name").val() ?? ""), quantity: line.quantity, specialInstructions, unitPriceMinor, lineTotalMinor };
  }));
  const subtotalMinor = pricedItems.reduce((sum, item) => {
    const total = sum + item.lineTotalMinor;
    if (!Number.isSafeInteger(total)) throw new HttpsError("out-of-range", "Order total is too large.");
    return total;
  }, 0);
  await assertPaymentServiceEnabled(kind);
  const method = await paymentMethod(data.paymentMethod);
  const breakdown = calculateOrderBreakdown({ config, service: kind, subtotalMinor, route, paymentMethod: method, discount });
  return saveQuote(uid, kind, config, request, breakdown, { sourceId, items: pricedItems, route, paymentMethod: method });
}

export const quoteRide = onCall({ secrets: [mapsApiKey], enforceAppCheck: true }, async (call) => {
  const uid = requireAuth(call.auth?.uid);
  const rawData = call.data as QuoteRequest & { rideType?: unknown; pickupAddress?: unknown; dropoffAddress?: unknown };
  const vehicleTypes = ["CAR", "BIKE", "E_BIKE", "SCOOTER", "MOTORCYCLE", "TRUCK", "VAN", "OTHER"];
  const rideType = rawData.rideType === undefined ? "CAR" : rawData.rideType;
  if (typeof rideType !== "string" || !vehicleTypes.includes(rideType)) {
    throw new HttpsError("invalid-argument", "Select a supported ride vehicle type.");
  }
  const data = { ...rawData, rideType, pickupAddress: validateAddress(rawData.pickupAddress, "Pickup"), dropoffAddress: validateAddress(rawData.dropoffAddress, "Drop-off") };
  const [config, route] = await Promise.all([loadConfig(), routeMetrics(uid, data)]);
  const multiplierBps = config.ride.vehicleMultipliersBps?.[rideType] ?? 10000;
  const fare = calculateRideFare(config.ride, route, multiplierBps);
  await assertPaymentServiceEnabled("RIDE");
  const method = await paymentMethod(data.paymentMethod);
  const fee = calculateProviderFee(config, method, fare.fareMinor);
  const providerFeeMinor = fee.amount;
  const customerTotalMinor = fare.fareMinor + (fee.payer === "CUSTOMER" ? providerFeeMinor : 0);
  return saveQuote(uid, "RIDE", config, data, {
    currency: "PHP", fareMinor: fare.fareMinor, providerFeeMinor, customerTotalMinor,
    platformCommissionMinor: fare.platformCommissionMinor, driverEarningsMinor: fare.driverEarningsMinor,
    platformNetBeforeOtherCostsMinor: fare.platformCommissionMinor - (method === "GCASH" && fee.payer === "PLATFORM" ? providerFeeMinor : 0),
  }, { route, paymentMethod: method, rideType });
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
  const rawData = call.data as QuoteRequest & { pickupAddress?: unknown; dropoffAddress?: unknown };
  const data = { ...rawData, pickupAddress: validateAddress(rawData.pickupAddress, "Pickup"), dropoffAddress: validateAddress(rawData.dropoffAddress, "Drop-off") };
  const [config, route] = await Promise.all([loadConfig(), routeMetrics(uid, data)]);
  await assertPaymentServiceEnabled("DELIVERY");
  const method = await paymentMethod(data.paymentMethod);
  const breakdown = calculateDeliveryOnlyBreakdown({ config, route, paymentMethod: method });
  return saveQuote(uid, "DELIVERY", config, data, breakdown, { route, paymentMethod: method });
});

export { createOrderFromQuote, createRideFromQuote, createDeliveryFromQuote } from "./operations/quote-operations.js";
export { cleanupExpiredPricingQuotes } from "./pricing/quote-cleanup.js";
export {
  releaseOrderAllocationsOnDelivered,
  releaseRideAllocationsOnCompleted,
} from "./operations/payment-release-trigger.js";
export { syncPublicDriverPresence, syncDriverPresenceOnServiceChange, syncActiveRideRequest } from "./operations/driver-presence.js";
export { submitRestaurantApplication, submitShopApplication, setFoodMode, setShopMode, adminUpdateProviderStatus } from "./operations/provider-approvals.js";

export {
  transitionOrderStatus,
  claimOrderDelivery,
  releaseOrderDelivery,
  adminReassignOrderDelivery,
  completeOrderDelivery,
  claimRide,
  transitionTripStatus,
  cancelPassengerTrip,
  cancelCustomerOrder,
} from "./operations/transaction-operations.js";

// Phase 08 — cash exposure controls, aged collection alerts and cash operations views.
export {
  setCollectorOnline,
  getMyCashOperations,
  getMyCashSettlementItems,
  getAdminCashControlOverview,
  updateAdminCashControls,
  alertOverdueCashCollections,
} from "./finance/cash-controls.js";

// Phase 08 — authenticated cash collection, settlement, remittance and reconciliation.
export {
  confirmCashCollection,
  confirmMerchantCashSettlement,
  confirmCashRemittance,
  getMyCashOutstanding,
  reconcileCashPayments,
} from "./finance/cash-operations.js";

// Phase 01 — server-owned BDO payment configuration and customer-safe payment options.
export { getPaymentConfiguration, updateAdminPaymentConfiguration } from "./payments/payment-config.js";

// BDO Checkout GCash verification bridge (admin-verified until BDO provides its merchant webhook contract).
export { confirmBdoGcashPayment, attachBdoCheckoutLink, expireBdoGcashPayment } from "./payments/bdo-payment-operations.js";

// Development-only simulated GCash checkout, webhook and refund lifecycle.
export { createSimulatedGcashSession, simulateGcashPaymentResult } from "./payments/payment-sessions.js";
export { createSimulatedGcashRefund } from "./payments/refunds.js";
export { expireSimulatedGcashSessions } from "./payments/expiration.js"; // null outside the emulator

// Phase 05 — cancel and refund paid ride requests that expire without driver acceptance.
export { recoverUnacceptedPaidRides } from "./payments/unaccepted-ride-recovery.js";

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
  recoverInterruptedPayouts,
} from "./finance/payouts.js";

// Phase 07 — administrator financial overview.
export { getAdminFinancialOverview } from "./finance/admin-overview.js";

// Phase 08 — administrator pricing and commission configuration.
export { getAdminPricingConfig, updateAdminPricingConfig } from "./finance/admin-pricing.js";

// Post trusted successful payments into the immutable financial journal.
export { postSucceededPaymentToLedger } from "./finance/payment-ledger-trigger.js";

// Phase 05 — project canonical payment status into customer order and ride records.
export { syncPaymentStateToCustomerTransaction } from "./payments/payment-state-sync-trigger.js";

// Phase 09 — manual BDO settlement records and bank-statement reconciliation.
export { createExpectedBdoSettlement, recordBdoBankTransfer, reconcileBdoSettlements } from "./finance/bdo-settlement.js";

// Phase 10 — complete administrator financial administration.
export {
  getAdminPaymentMonitor,
  getAdminFinancialBalances,
  runAdminFinancialReconciliation,
  resolveAdminFinancialDiscrepancy,
  createAdminRefundCase,
  reviewAdminRefundCase,
  completeAdminRefundCase,
  getAdminRefundCases,
  getAdminAuditTrail,
} from "./finance/admin-financial.js";
