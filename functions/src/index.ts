import { createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { DataSnapshot, getDatabase } from "firebase-admin/database";
import { logger } from "firebase-functions";
import { defineSecret, defineString } from "firebase-functions/params";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { onValueWritten } from "firebase-functions/v2/database";
import { calculateNetParticipantShares, isSettledGcashPaymentTransaction, matchesGcashPaymentTransaction, phpAmountToCentavos } from "./xenditSettlement";

initializeApp();

const REGION = "asia-southeast1";
const db = getDatabase();
const XENDIT_SECRET_KEY = defineSecret("XENDIT_SECRET_KEY");
const XENDIT_WEBHOOK_TOKEN = defineSecret("XENDIT_WEBHOOK_TOKEN");
const XENDIT_PAYOUT_WEBHOOK_TOKEN = defineSecret("XENDIT_PAYOUT_WEBHOOK_TOKEN");
const XENDIT_PAYOUT_SECRET_KEY = defineSecret("XENDIT_PAYOUT_SECRET_KEY");
const PAYOUT_ACCOUNT_ENCRYPTION_KEY = defineSecret("PAYOUT_ACCOUNT_ENCRYPTION_KEY");
const GOOGLE_ROUTES_API_KEY = defineSecret("GOOGLE_ROUTES_API_KEY");
const PAYMENT_SUCCESS_RETURN_URL = defineString("PAYMENT_SUCCESS_RETURN_URL");
const PAYMENT_FAILURE_RETURN_URL = defineString("PAYMENT_FAILURE_RETURN_URL");

type PaymentMethod = "GCASH" | "CASH";
type OrderType = "FOOD" | "RETAIL";

type CatalogLine = {
  id: string;
  name: string;
  basePrice: number;
  quantity: number;
  specialInstructions: string;
};

type XenditAction = {
  type?: string;
  value?: string;
  descriptor?: string;
};

type XenditPaymentRequest = {
  payment_request_id?: string;
  latest_payment_id?: string;
  reference_id?: string;
  status?: string;
  updated?: string;
  request_amount?: number;
  currency?: string;
  channel_code?: string;
  country?: string;
  failure_code?: string;
  actions?: XenditAction[];
};

function asRecord(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object"
    ? value as Record<string, unknown>
    : {};
}

function asString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function asNumber(value: unknown, field: string): number {
  const number = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(number)) {
    throw new HttpsError("invalid-argument", field + " must be a valid number.");
  }
  return number;
}

function roundMoney(value: number): number {
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

function validateCoordinates(lat: number, lng: number, prefix: string): void {
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
    throw new HttpsError("invalid-argument", prefix + " coordinates are invalid.");
  }
}

function requireUid(request: { auth?: { uid: string } | null }): string {
  const uid = request.auth?.uid;
  if (!uid) {
    throw new HttpsError("unauthenticated", "Please sign in before starting checkout.");
  }
  return uid;
}

function parsePaymentMethod(value: unknown): PaymentMethod {
  const code = asString(value).toUpperCase();
  if (code === "GCASH") return "GCASH";
  if (code === "CASH") return "CASH";
  throw new HttpsError("invalid-argument", "Choose GCash or Cash.");
}

function parseIdempotencyKey(value: unknown): string {
  const key = asString(value);
  if (!/^[A-Za-z0-9_-]{12,80}$/.test(key)) {
    throw new HttpsError("invalid-argument", "Invalid checkout request key.");
  }
  return key;
}

function normalizeCatalogItems(value: unknown): Array<{ id: string; quantity: number; specialInstructions: string }> {
  if (!Array.isArray(value) || value.length === 0 || value.length > 30) {
    throw new HttpsError("invalid-argument", "Your cart must contain between 1 and 30 items.");
  }

  const seen = new Set<string>();
  return value.map((raw) => {
    const item = asRecord(raw);
    const id = asString(item.id);
    const quantity = Number(item.quantity);
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(id) || !Number.isInteger(quantity) || quantity < 1 || quantity > 50) {
      throw new HttpsError("invalid-argument", "Each item needs a valid catalog ID and quantity between 1 and 50.");
    }
    if (seen.has(id)) {
      throw new HttpsError("invalid-argument", "A catalog item must appear only once in the requested items.");
    }
    seen.add(id);
    return {
      id,
      quantity,
      specialInstructions: asString(item.specialInstructions).slice(0, 300),
    };
  });
}

async function createXenditGcashRequest(args: {
  referenceId: string;
  amount: number;
  description: string;
  entityType: "ORDER" | "TRIP";
  entityId: string;
  clientId: string;
  idempotencyKey: string;
}): Promise<{ paymentId: string; checkoutUrl: string; status: string; webCheckoutUrl: string; checkoutActionDescriptor: string }> {
  const secret = XENDIT_SECRET_KEY.value();
  const successUrl = PAYMENT_SUCCESS_RETURN_URL.value();
  const failureUrl = PAYMENT_FAILURE_RETURN_URL.value();

  if (!secret) {
    throw new HttpsError("failed-precondition", "The Xendit secret key is not configured.");
  }
  if (!successUrl.startsWith("https://") || !failureUrl.startsWith("https://")) {
    throw new HttpsError("failed-precondition", "Configure public HTTPS payment return URLs before enabling GCash.");
  }

  const response = await fetch("https://api.xendit.co/v3/payment_requests", {
    method: "POST",
    headers: {
      Authorization: "Basic " + Buffer.from(secret + ":").toString("base64"),
      "Content-Type": "application/json",
      "api-version": "2024-11-11",
      "Idempotency-key": args.idempotencyKey,
    },
    body: JSON.stringify({
      reference_id: args.referenceId,
      type: "PAY",
      country: "PH",
      currency: "PHP",
      request_amount: args.amount,
      capture_method: "AUTOMATIC",
      channel_code: "GCASH",
      channel_properties: {
        success_return_url: successUrl,
        failure_return_url: failureUrl,
      },
      description: args.description.slice(0, 255),
      metadata: {
        entity_type: args.entityType,
        entity_id: args.entityId,
        client_id: args.clientId,
      },
    }),
  });

  const body = asRecord(await response.json().catch(() => ({})));
  if (!response.ok) {
    logger.error("Xendit payment request failed", {
      status: response.status,
      response: body,
      referenceId: args.referenceId,
    });
    throw new HttpsError("unavailable", "Xendit could not start this payment. Please try again.");
  }

  const request = body as XenditPaymentRequest;
  const paymentId = asString(request.payment_request_id);
  const actions = request.actions ?? [];
  const mobileAction = actions.find((candidate) =>
    candidate.type === "REDIRECT_CUSTOMER" && candidate.descriptor === "DEEPLINK_URL"
  );
  const webAction = actions.find((candidate) =>
    candidate.type === "REDIRECT_CUSTOMER" && candidate.descriptor === "WEB_URL"
  );

  function normalizeActionUrl(action: XenditAction | undefined): string {
    const raw = asString(action?.value);
    if (!raw) return "";
    const candidate = /^[a-z][a-z0-9+.-]*:/i.test(raw)
      ? raw
      : "https://" + raw.replace(/^\/+/, "");
    try {
      const parsed = new URL(candidate);
      if (parsed.protocol === "https:") return parsed.toString();
      if (
        action?.descriptor === "DEEPLINK_URL" &&
        !["http:", "javascript:", "data:", "file:"].includes(parsed.protocol)
      ) {
        return parsed.toString();
      }
    } catch {
      return "";
    }
    return "";
  }

  // Mobile GCash deeplinks are preferred; desktop/web checkout remains the fallback.
  const deeplinkUrl = normalizeActionUrl(mobileAction);
  const webCheckoutUrl = normalizeActionUrl(webAction);
  const checkoutUrl = deeplinkUrl || webCheckoutUrl;
  const checkoutActionDescriptor = deeplinkUrl ? "DEEPLINK_URL" : webCheckoutUrl ? "WEB_URL" : "";

  const initialStatus = asString(request.status).toUpperCase();
  const status = initialStatus === "SUCCEEDED"
    ? "SUCCEEDED"
    : initialStatus === "EXPIRED"
      ? "EXPIRED"
      : ["CANCELED", "CANCELLED"].includes(initialStatus)
        ? "CANCELLED"
        : initialStatus === "FAILED"
          ? "FAILED"
          : "PENDING";
  return { paymentId, checkoutUrl, status, webCheckoutUrl, checkoutActionDescriptor };
}

async function loadOrderCatalog(
  uid: string,
  orderType: OrderType,
  sourceId: string,
  requestedItems: Array<{ id: string; quantity: number; specialInstructions: string }>
): Promise<{
  sourceName: string;
  pickupLocation: string;
  pickupLat: number;
  pickupLng: number;
  deliveryFee: number;
  items: CatalogLine[];
  merchantId: string;
}> {
  if (orderType === "FOOD") {
    const [restaurantSnapshot, profileSnapshot] = await Promise.all([
      db.ref("restaurants/" + sourceId).get(),
      db.ref("users/" + sourceId).get(),
    ]);
    if (!restaurantSnapshot.exists() || !profileSnapshot.exists()) {
      throw new HttpsError("not-found", "This restaurant is no longer available.");
    }

    const restaurant = asRecord(restaurantSnapshot.val());
    const profile = asRecord(profileSnapshot.val());
    if (asString(profile.foodStatus).toUpperCase() !== "APPROVED") {
      throw new HttpsError("failed-precondition", "This restaurant is not approved to accept orders.");
    }
    if (restaurant.isOpen === false) {
      throw new HttpsError("failed-precondition", "This restaurant is currently closed.");
    }

    const items = await Promise.all(requestedItems.map(async (requested) => {
      const snapshot = await db.ref("restaurants/" + sourceId + "/meals/" + requested.id).get();
      if (!snapshot.exists()) {
        throw new HttpsError("not-found", "A selected meal is no longer available.");
      }
      const meal = asRecord(snapshot.val());
      if (meal.isAvailable === false) {
        throw new HttpsError("failed-precondition", asString(meal.name) + " is currently unavailable.");
      }
      const price = asNumber(meal.basePrice, "meal price");
      if (price < 0) throw new HttpsError("failed-precondition", "A meal has an invalid price.");
      return {
        id: requested.id,
        name: asString(meal.name) || "Meal",
        basePrice: roundMoney(price),
        quantity: requested.quantity,
        specialInstructions: requested.specialInstructions,
      };
    }));

    return {
      sourceName: asString(restaurant.name) || "Restaurant",
      pickupLocation: asString(restaurant.address),
      pickupLat: Number(restaurant.latitude ?? 0),
      pickupLng: Number(restaurant.longitude ?? 0),
      deliveryFee: 0, // Global administrator pricing is applied after route calculation.
      items,
      merchantId: sourceId,
    };
  }

  const shopSnapshot = await db.ref("shops/" + sourceId).get();
  if (!shopSnapshot.exists()) {
    throw new HttpsError("not-found", "This shop is no longer available.");
  }

  const shop = asRecord(shopSnapshot.val());
  if (asString(shop.status).toUpperCase() !== "APPROVED") {
    throw new HttpsError("failed-precondition", "This shop is not approved to accept orders.");
  }
  if (shop.isOnline === false) {
    throw new HttpsError("failed-precondition", "This shop is currently offline.");
  }

  // Delivery charges are calculated from the administrator's route pricing below.

  const items = await Promise.all(requestedItems.map(async (requested) => {
    const snapshot = await db.ref("shops/" + sourceId + "/products/" + requested.id).get();
    if (!snapshot.exists()) {
      throw new HttpsError("not-found", "A selected product is no longer available.");
    }
    const product = asRecord(snapshot.val());
    if (product.isAvailable === false) {
      throw new HttpsError("failed-precondition", asString(product.name) + " is currently unavailable.");
    }
    const stock = Number(product.stockQuantity);
    if (Number.isFinite(stock) && stock >= 0 && stock < requested.quantity) {
      throw new HttpsError("failed-precondition", asString(product.name) + " does not have enough stock.");
    }
    const price = asNumber(product.price, "product price");
    if (price < 0) throw new HttpsError("failed-precondition", "A product has an invalid price.");
    return {
      id: requested.id,
      name: asString(product.name) || "Product",
      basePrice: roundMoney(price),
      quantity: requested.quantity,
      specialInstructions: requested.specialInstructions,
    };
  }));

  if (shop.latitude === undefined || shop.latitude === null || shop.longitude === undefined || shop.longitude === null) {
    throw new HttpsError("failed-precondition", "This shop has no pickup location yet. Ask the shop owner to update its map location before placing an order.");
  }
  const pickupLat = asNumber(shop.latitude, "Shop pickup latitude");
  const pickupLng = asNumber(shop.longitude, "Shop pickup longitude");
  validateCoordinates(pickupLat, pickupLng, "Shop pickup");
  if (pickupLat === 0 && pickupLng === 0) {
    throw new HttpsError("failed-precondition", "This shop has no valid pickup location yet. Ask the shop owner to update its map location before placing an order.");
  }

  return {
    sourceName: asString(shop.name) || "Shop",
    pickupLocation: asString(shop.address),
    pickupLat,
    pickupLng,
    deliveryFee: 0,
    items,
    merchantId: asString(shop.ownerUid),
  };
}


// -----------------------------------------------------------------------------
// Server-owned pricing configuration, route calculation, quotes and snapshots.
// Prices/rates are stored as integer PHP centavos and basis points in Realtime DB.
// -----------------------------------------------------------------------------

type RoundingMode = "CEIL" | "NEAREST" | "FLOOR";

type DeliveryPricingRule = {
  baseFeeCentavos: number;
  pricePerKmCentavos: number;
  minimumFeeCentavos: number;
  roundingIncrementCentavos: number;
  roundingMode: RoundingMode;
};

type RidePricingRule = {
  baseFareCentavos: number;
  pricePerKmCentavos: number;
  minimumFareCentavos: number;
  roundingIncrementCentavos: number;
  roundingMode: RoundingMode;
  additionalCharges: Array<{ code: string; label: string; amountCentavos: number }>;
};

type PricingConfiguration = {
  version: number;
  currency: "PHP";
  delivery: DeliveryPricingRule;
  rides: Record<string, RidePricingRule>;
  commissions: {
    foodBps: number;
    goodsBps: number;
    deliveryBps: number;
    ridesBps: number;
  };
  additionalCharges: {
    foodCentavos: number;
    goodsCentavos: number;
    deliveryCentavos: number;
    ridesCentavos: number;
  };
  cancellationFees: {
    foodCentavos: number;
    goodsCentavos: number;
    deliveryCentavos: number;
    ridesCentavos: number;
    customerCancellationBps: number;
    merchantCancellationBps: number;
  };
  refundPolicy: {
    freeCancellationBeforeAcceptance: boolean;
    refundDeliveryFeeOnCancellation: boolean;
    refundWindowHours: number;
  };
};

type RouteTravelMode = "DRIVE" | "BICYCLE" | "TWO_WHEELER";

type RouteResult = {
  provider: "GOOGLE_ROUTES_API";
  distanceMeters: number;
  duration: string;
  travelMode: RouteTravelMode;
  encodedPolyline: string;
};

const MAX_SAFE_CENTAVOS = Number.MAX_SAFE_INTEGER;
const QUOTE_LIFETIME_MS = 10 * 60 * 1000;

function nonNegativeInteger(value: unknown, field: string): number {
  const number = asNumber(value, field);
  if (!Number.isSafeInteger(number) || number < 0 || number > MAX_SAFE_CENTAVOS) {
    throw new HttpsError("invalid-argument", field + " must be a non-negative integer.");
  }
  return number;
}

function boundedBasisPoints(value: unknown, field: string): number {
  const number = nonNegativeInteger(value, field);
  if (number > 10000) {
    throw new HttpsError("invalid-argument", field + " must be between 0 and 10000 basis points.");
  }
  return number;
}

function parseRoundingMode(value: unknown, field: string): RoundingMode {
  const mode = asString(value).toUpperCase();
  if (mode !== "CEIL" && mode !== "NEAREST" && mode !== "FLOOR") {
    throw new HttpsError("invalid-argument", field + " must be CEIL, NEAREST, or FLOOR.");
  }
  return mode;
}

function parseDeliveryRule(value: unknown, prefix: string): DeliveryPricingRule {
  const record = asRecord(value);
  const increment = nonNegativeInteger(record.roundingIncrementCentavos, prefix + " rounding increment");
  if (increment < 1) {
    throw new HttpsError("invalid-argument", prefix + " rounding increment must be at least one centavo.");
  }
  return {
    baseFeeCentavos: nonNegativeInteger(record.baseFeeCentavos, prefix + " base fee"),
    pricePerKmCentavos: nonNegativeInteger(record.pricePerKmCentavos, prefix + " price per kilometre"),
    minimumFeeCentavos: nonNegativeInteger(record.minimumFeeCentavos, prefix + " minimum fee"),
    roundingIncrementCentavos: increment,
    roundingMode: parseRoundingMode(record.roundingMode, prefix + " rounding mode"),
  };
}

function parseRideRule(value: unknown, serviceType: string): RidePricingRule {
  const record = asRecord(value);
  const increment = nonNegativeInteger(record.roundingIncrementCentavos, serviceType + " rounding increment");
  if (increment < 1) {
    throw new HttpsError("invalid-argument", serviceType + " rounding increment must be at least one centavo.");
  }
  const rawCharges = Array.isArray(record.additionalCharges) ? record.additionalCharges : [];
  const additionalCharges = rawCharges.map((raw, index) => {
    const charge = asRecord(raw);
    const code = asString(charge.code);
    const label = asString(charge.label);
    if (!/^[a-z0-9_-]{2,40}$/.test(code) || !label) {
      throw new HttpsError("invalid-argument", serviceType + " additional charge " + index + " needs a valid code and label.");
    }
    return {
      code,
      label: label.slice(0, 80),
      amountCentavos: nonNegativeInteger(charge.amountCentavos, serviceType + " additional charge"),
    };
  });
  return {
    baseFareCentavos: nonNegativeInteger(record.baseFareCentavos, serviceType + " base fare"),
    pricePerKmCentavos: nonNegativeInteger(record.pricePerKmCentavos, serviceType + " price per kilometre"),
    minimumFareCentavos: nonNegativeInteger(record.minimumFareCentavos, serviceType + " minimum fare"),
    roundingIncrementCentavos: increment,
    roundingMode: parseRoundingMode(record.roundingMode, serviceType + " rounding mode"),
    additionalCharges,
  };
}

function normalizePricingConfiguration(value: unknown): PricingConfiguration {
  const record = asRecord(value);
  const ridesRecord = asRecord(record.rides);
  const rideEntries = Object.entries(ridesRecord);
  if (rideEntries.length === 0) {
    throw new HttpsError("invalid-argument", "At least one ride service pricing rule is required.");
  }

  const rides: Record<string, RidePricingRule> = {};
  for (const [serviceType, rule] of rideEntries) {
    if (!/^[a-z0-9_-]{2,24}$/.test(serviceType)) {
      throw new HttpsError("invalid-argument", "Invalid ride service pricing key: " + serviceType);
    }
    rides[serviceType] = parseRideRule(rule, serviceType);
  }

  const commissions = asRecord(record.commissions);
  const additional = asRecord(record.additionalCharges);
  const cancellation = asRecord(record.cancellationFees);
  const refunds = asRecord(record.refundPolicy);

  return {
    version: nonNegativeInteger(record.version ?? 0, "pricing version"),
    currency: "PHP",
    delivery: parseDeliveryRule(record.delivery, "delivery"),
    rides,
    commissions: {
      foodBps: boundedBasisPoints(commissions.foodBps, "food commission"),
      goodsBps: boundedBasisPoints(commissions.goodsBps, "goods commission"),
      deliveryBps: boundedBasisPoints(commissions.deliveryBps, "delivery commission"),
      ridesBps: boundedBasisPoints(commissions.ridesBps, "ride commission"),
    },
    additionalCharges: {
      foodCentavos: nonNegativeInteger(additional.foodCentavos, "food additional charges"),
      goodsCentavos: nonNegativeInteger(additional.goodsCentavos, "goods additional charges"),
      deliveryCentavos: nonNegativeInteger(additional.deliveryCentavos, "delivery additional charges"),
      ridesCentavos: nonNegativeInteger(additional.ridesCentavos, "ride additional charges"),
    },
    cancellationFees: {
      foodCentavos: nonNegativeInteger(cancellation.foodCentavos, "food cancellation fee"),
      goodsCentavos: nonNegativeInteger(cancellation.goodsCentavos, "goods cancellation fee"),
      deliveryCentavos: nonNegativeInteger(cancellation.deliveryCentavos, "delivery cancellation fee"),
      ridesCentavos: nonNegativeInteger(cancellation.ridesCentavos, "ride cancellation fee"),
      customerCancellationBps: boundedBasisPoints(cancellation.customerCancellationBps, "customer cancellation rate"),
      merchantCancellationBps: boundedBasisPoints(cancellation.merchantCancellationBps, "merchant cancellation rate"),
    },
    refundPolicy: {
      freeCancellationBeforeAcceptance: refunds.freeCancellationBeforeAcceptance === true,
      refundDeliveryFeeOnCancellation: refunds.refundDeliveryFeeOnCancellation === true,
      refundWindowHours: nonNegativeInteger(refunds.refundWindowHours, "refund window hours"),
    },
  };
}

function requireAdmin(request: { auth?: { uid: string; token?: unknown } | null }): string {
  const uid = requireUid(request);
  const claims = asRecord(request.auth?.token);
  if (claims.admin !== true) {
    throw new HttpsError(
      "permission-denied",
      "Administrator access is required. Set the Firebase Authentication custom claim admin=true for this administrator."
    );
  }
  return uid;
}


async function getUserProfileRecord(uid: string): Promise<Record<string, unknown>> {
  const snapshot = await db.ref("users/" + uid).get();
  if (!snapshot.exists()) throw new HttpsError("failed-precondition", "Your Labmda profile is missing.");
  return asRecord(snapshot.val());
}
async function requireApprovedMerchant(uid: string, order: Record<string, unknown>): Promise<void> {
  const profile = await getUserProfileRecord(uid);
  const status = asString(asString(order.orderType).toUpperCase() === "RETAIL" ? profile.shopStatus : profile.foodStatus).toUpperCase();
  if (status !== "APPROVED" && status !== "ACTIVE") throw new HttpsError("permission-denied", "Only an administrator-approved merchant can manage this order.");
}
async function requireApprovedCourier(uid: string): Promise<void> {
  const profile = await getUserProfileRecord(uid);
  const status = asString(asRecord(profile.deliveryServices).status).toUpperCase();
  if (status !== "APPROVED" && status !== "ACTIVE") throw new HttpsError("permission-denied", "Only an approved courier can perform this delivery action.");
}
async function requireApprovedDriver(uid: string): Promise<Record<string, unknown>> {
  const profile = await getUserProfileRecord(uid);
  const status = asString(asRecord(profile.driverService).status).toUpperCase();
  if (status !== "APPROVED" && status !== "ACTIVE") throw new HttpsError("permission-denied", "Only an approved driver can perform this ride action.");
  return profile;
}

async function validateTargetDriverForRide(targetDriverId: string, serviceType: string): Promise<void> {
  if (!targetDriverId) return;
  const profile = await requireApprovedDriver(targetDriverId);
  const driverService = asRecord(profile.driverService);
  if (driverService.isOnline !== true) {
    throw new HttpsError("failed-precondition", "The selected driver is no longer online. Choose another driver.");
  }
  const vehicleType = asString(asRecord(driverService.vehicleInfo).type).toLowerCase();
  if (serviceType.toLowerCase() !== "ride" && vehicleType !== serviceType.toLowerCase()) {
    throw new HttpsError("failed-precondition", "The selected driver's vehicle does not match this ride type.");
  }
}

async function getCurrentPricingConfiguration(): Promise<PricingConfiguration> {
  const snapshot = await db.ref("config/pricing/current").get();
  if (!snapshot.exists()) {
    throw new HttpsError(
      "failed-precondition",
      "Administrator pricing is not configured. An administrator must publish config/pricing/current first."
    );
  }
  const config = normalizePricingConfiguration(snapshot.val());
  if (config.version < 1) {
    throw new HttpsError("failed-precondition", "Administrator pricing has not been initialized.");
  }
  return config;
}

function centsFromPhp(value: number, field: string): number {
  if (!Number.isFinite(value) || value < 0 || value > MAX_SAFE_CENTAVOS / 100) {
    throw new HttpsError("failed-precondition", field + " is outside the supported PHP amount range.");
  }
  return Math.round(value * 100);
}

function phpFromCents(centavos: number): number {
  return Number((centavos / 100).toFixed(2));
}

type XenditSettlementResult = {
  transactionId: string;
  settlementStatus: string;
  estimatedSettlementAtTimestamp: number;
  netAmountCentavos: number;
  feeCentavos: number;
};

async function findXenditGcashSettlement(
  payment: Record<string, unknown>,
  secret: string
): Promise<{ transaction: Record<string, unknown>; settled: boolean } | null> {
  const referenceId = asString(payment.referenceId);
  if (!referenceId) throw new HttpsError("data-loss", "The saved GCash payment reference is missing.");

  const url = new URL("https://api.xendit.co/transactions");
  url.searchParams.set("types", "PAYMENT");
  url.searchParams.set("reference_id", referenceId);
  url.searchParams.set("currency", "PHP");
  url.searchParams.set("limit", "10");

  const response = await fetch(url.toString(), {
    method: "GET",
    headers: {
      Authorization: "Basic " + Buffer.from(secret + ":").toString("base64"),
      "Content-Type": "application/json",
    },
  });
  const body = asRecord(await response.json().catch(() => ({})));
  if (!response.ok) {
    logger.warn("Xendit transaction settlement lookup failed", {
      status: response.status,
      referenceId,
    });
    throw new HttpsError("unavailable", "Xendit settlement status is temporarily unavailable.");
  }

  const rows = Array.isArray(body.data) ? body.data.map(asRecord) : [];
  const transaction = rows.find((row) => asString(row.reference_id) === referenceId);
  if (!transaction) return null;

  if (!matchesGcashPaymentTransaction(payment, transaction)) {
    logger.error("Xendit settlement transaction did not match the saved GCash payment", {
      referenceId,
      transactionId: asString(transaction.id),
    });
    throw new HttpsError("data-loss", "Xendit settlement details do not match the saved GCash payment.");
  }

  return {
    transaction,
    settled: isSettledGcashPaymentTransaction(payment, transaction),
  };
}

async function getXenditAvailableBalanceCentavos(secret: string): Promise<number> {
  const response = await fetch("https://api.xendit.co/balance?account_type=CASH&currency=PHP", {
    method: "GET",
    headers: {
      Authorization: "Basic " + Buffer.from(secret + ":").toString("base64"),
      "Content-Type": "application/json",
    },
  });
  const body = asRecord(await response.json().catch(() => ({})));
  if (!response.ok) {
    logger.warn("Xendit PHP available-balance lookup failed", { status: response.status });
    throw new HttpsError("unavailable", "Xendit available balance could not be verified.");
  }
  const balanceCentavos = phpAmountToCentavos(body.balance);
  if (balanceCentavos === null) {
    throw new HttpsError("data-loss", "Xendit returned an invalid PHP cash balance.");
  }
  return balanceCentavos;
}

async function sumWalletAvailableLiabilityCentavos(): Promise<number> {
  const snapshot = await db.ref("wallets").get();
  let total = 0;
  snapshot.forEach((child) => {
    const wallet = asRecord(child.val());
    const available = nonNegativeInteger(wallet.availableCentavos ?? 0, "wallet available balance");
    total += available;
    if (!Number.isSafeInteger(total)) {
      throw new HttpsError("data-loss", "Aggregate wallet liabilities exceed the supported amount range.");
    }
    return false;
  });
  return total;
}

function participantEarningsCentavos(
  entityType: "ORDER" | "TRIP",
  entity: Record<string, unknown>,
  payment: Record<string, unknown>
): number {
  const breakdown = asRecord(payment.breakdown ?? entity.breakdown);
  const amount = nonNegativeInteger(payment.amountCentavos, "payment amount");
  const net = calculateNetParticipantShares({
    grossAmountCentavos: amount,
    merchantCentavos: entityType === "ORDER" ? nonNegativeInteger(breakdown.merchantShare ?? 0, "merchant share") : 0,
    courierCentavos: entityType === "ORDER" ? nonNegativeInteger(breakdown.courierShare ?? 0, "courier share") : 0,
    driverCentavos: entityType === "TRIP" ? nonNegativeInteger(breakdown.driverShare ?? 0, "driver share") : 0,
    platformCentavos: nonNegativeInteger(breakdown.platformShare ?? 0, "platform share"),
    refunds: asRecord(payment.refunds) as Record<string, {
      amountCentavos?: unknown;
      status?: unknown;
      walletSettlementStatus?: unknown;
    }>,
  });
  const total = net.merchantCentavos + net.courierCentavos + net.driverCentavos;
  if (!Number.isSafeInteger(total)) {
    throw new HttpsError("data-loss", "Participant earnings exceed the supported amount range.");
  }
  return total;
}

function roundCentavos(amount: number, increment: number, mode: RoundingMode): number {
  if (!Number.isSafeInteger(amount) || amount < 0 || !Number.isSafeInteger(increment) || increment < 1) {
    throw new HttpsError("failed-precondition", "Pricing amount or rounding increment is outside the supported centavo range.");
  }
  const units = Math.floor(amount / increment);
  const remainder = amount % increment;
  const roundedUnits = mode === "CEIL"
    ? units + (remainder > 0 ? 1 : 0)
    : mode === "FLOOR"
      ? units
      : units + (remainder * 2 >= increment ? 1 : 0);
  const result = roundedUnits * increment;
  if (!Number.isSafeInteger(result)) {
    throw new HttpsError("failed-precondition", "Rounded price exceeds the supported centavo range.");
  }
  return result;
}

function roundPositiveRatio(numerator: bigint, denominator: bigint, field: string): number {
  const rounded = (numerator + denominator / 2n) / denominator;
  const result = Number(rounded);
  if (!Number.isSafeInteger(result)) {
    throw new HttpsError("failed-precondition", field + " exceeds the supported centavo range.");
  }
  return result;
}

function commissionCentavos(amountCentavos: number, basisPoints: number): number {
  return roundPositiveRatio(
    BigInt(amountCentavos) * BigInt(basisPoints),
    10000n,
    "Commission"
  );
}

function calculateDeliveryFeeCentavos(distanceMeters: number, rule: DeliveryPricingRule): number {
  const distanceCharge = roundPositiveRatio(
    BigInt(rule.pricePerKmCentavos) * BigInt(distanceMeters),
    1000n,
    "Delivery distance charge"
  );
  const rounded = roundCentavos(
    rule.baseFeeCentavos + distanceCharge,
    rule.roundingIncrementCentavos,
    rule.roundingMode
  );
  return Math.max(rule.minimumFeeCentavos, rounded);
}

const SUPPORTED_RIDE_TYPES = new Set([
  "ride", "car", "bike", "e_bike", "scooter", "motorcycle", "truck", "van", "other",
]);

function isSupportedRideType(serviceType: string): boolean {
  return SUPPORTED_RIDE_TYPES.has(serviceType.toLowerCase());
}

function resolveRidePricingRule(config: PricingConfiguration, serviceType: string): RidePricingRule | null {
  return config.rides[serviceType.toLowerCase()] ?? config.rides.ride ?? null;
}

function calculateRideFareCentavos(
  distanceMeters: number,
  rule: RidePricingRule,
  sharedRideChargeCentavos: number
): {
  fareCentavos: number;
  optionalChargesCentavos: number;
  baseFareCentavos: number;
  distanceChargeCentavos: number;
  adjustmentCentavos: number;
} {
  const perKm = roundPositiveRatio(
    BigInt(rule.pricePerKmCentavos) * BigInt(distanceMeters),
    1000n,
    "Ride distance charge"
  );
  const optionalChargesValue =
    BigInt(sharedRideChargeCentavos) +
    rule.additionalCharges.reduce((sum, charge) => sum + BigInt(charge.amountCentavos), 0n);
  const optionalChargesCentavos = Number(optionalChargesValue);
  if (!Number.isSafeInteger(optionalChargesCentavos)) {
    throw new HttpsError("failed-precondition", "Ride additional charges exceed the supported centavo range.");
  }
  const unroundedTotal = rule.baseFareCentavos + perKm + optionalChargesCentavos;
  const rounded = roundCentavos(unroundedTotal, rule.roundingIncrementCentavos, rule.roundingMode);
  const fareCentavos = Math.max(rule.minimumFareCentavos, rounded);
  return {
    fareCentavos,
    optionalChargesCentavos,
    baseFareCentavos: rule.baseFareCentavos,
    distanceChargeCentavos: perKm,
    adjustmentCentavos: fareCentavos - unroundedTotal,
  };
}

function pricingSnapshot(
  config: PricingConfiguration,
  category: string,
  route: RouteResult,
  extra: Record<string, unknown> = {}
): Record<string, unknown> {
  return {
    version: config.version,
    currency: config.currency,
    category,
    rules: config,
    route: {
      provider: route.provider,
      distanceMeters: route.distanceMeters,
      distanceKm: Number((route.distanceMeters / 1000).toFixed(3)),
      duration: route.duration,
      travelMode: route.travelMode,
    },
    calculatedAtTimestamp: Date.now(),
    ...extra,
  };
}

async function calculateGoogleRoute(
  originLat: number,
  originLng: number,
  destinationLat: number,
  destinationLng: number,
  serviceType = "car"
): Promise<RouteResult> {
  if (
    (originLat === 0 && originLng === 0) ||
    (destinationLat === 0 && destinationLng === 0)
  ) {
    throw new HttpsError("invalid-argument", "A valid pickup and drop-off location is required.");
  }
  if (closeCoordinate(originLat, destinationLat) && closeCoordinate(originLng, destinationLng)) {
    throw new HttpsError("invalid-argument", "Pickup and drop-off locations must be different.");
  }

  const apiKey = GOOGLE_ROUTES_API_KEY.value();
  if (!apiKey) {
    throw new HttpsError("failed-precondition", "The Google Routes API key is not configured in Firebase Functions secrets.");
  }

  const travelMode = resolveRouteTravelMode(serviceType);
  const routeRequest: Record<string, unknown> = {
    origin: { location: { latLng: { latitude: originLat, longitude: originLng } } },
    destination: { location: { latLng: { latitude: destinationLat, longitude: destinationLng } } },
    travelMode,
    computeAlternativeRoutes: false,
    languageCode: "en",
    units: "METRIC",
  };
  // Routing preference is set only for DRIVE; non-car modes do not accept it.
  if (travelMode === "DRIVE") routeRequest.routingPreference = "TRAFFIC_UNAWARE";

  const response = await fetch("https://routes.googleapis.com/directions/v2:computeRoutes", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Goog-Api-Key": apiKey,
      "X-Goog-FieldMask": "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline",
    },
    body: JSON.stringify(routeRequest),
  });

  const body = asRecord(await response.json().catch(() => ({})));
  if (!response.ok) {
    logger.error("Google Routes API request failed", {
      httpStatus: response.status,
      providerStatus: asRecord(body.error).status,
      message: asRecord(body.error).message,
    });
    throw new HttpsError("unavailable", "The route service could not calculate this trip. Please try again.");
  }

  const routes = Array.isArray(body.routes) ? body.routes : [];
  const firstRoute = asRecord(routes[0]);
  const distanceMeters = Number(firstRoute.distanceMeters);
  const duration = asString(firstRoute.duration);
  const encodedPolyline = asString(asRecord(firstRoute.polyline).encodedPolyline);
  if (!Number.isSafeInteger(distanceMeters) || distanceMeters <= 0 || distanceMeters > 300000) {
    throw new HttpsError("failed-precondition", "The route service did not return a valid route distance.");
  }
  if (!encodedPolyline) {
    throw new HttpsError("failed-precondition", "The route service did not return road-route geometry.");
  }
  return { provider: "GOOGLE_ROUTES_API", distanceMeters, duration, travelMode, encodedPolyline };
}

function closeCoordinate(left: number, right: number): boolean {
  return Math.abs(left - right) <= 0.00005;
}

function resolveRouteTravelMode(serviceType: string): RouteTravelMode {
  switch (serviceType.toLowerCase()) {
    case "bike":
      return "BICYCLE";
    case "e_bike":
    case "scooter":
    case "motorcycle":
      return "TWO_WHEELER";
    // Truck-specific routing needs dimensions/weight not stored on driver profiles yet.
    default:
      return "DRIVE";
  }
}

async function loadOwnedPricingQuote(
  uid: string,
  quoteId: string,
  quoteType: "DELIVERY" | "RIDE",
  pickupLat: number,
  pickupLng: number,
  dropoffLat: number,
  dropoffLng: number,
  serviceType?: string
): Promise<Record<string, unknown>> {
  if (!/^[A-Za-z0-9_-]{12,80}$/.test(quoteId)) {
    throw new HttpsError("invalid-argument", "Invalid pricing quote ID.");
  }
  const snapshot = await db.ref("pricingQuotes/" + quoteId).get();
  if (!snapshot.exists()) {
    throw new HttpsError("not-found", "The pricing quote was not found. Please request a new quote.");
  }
  const quote = asRecord(snapshot.val());
  if (asString(quote.userId) !== uid || asString(quote.quoteType) !== quoteType) {
    throw new HttpsError("permission-denied", "This pricing quote cannot be used for this checkout.");
  }
  if (Number(quote.expiresAtTimestamp) < Date.now()) {
    throw new HttpsError("deadline-exceeded", "The pricing quote expired. Please request a new quote.");
  }
  if (
    !closeCoordinate(Number(quote.pickupLat), pickupLat) ||
    !closeCoordinate(Number(quote.pickupLng), pickupLng) ||
    !closeCoordinate(Number(quote.dropoffLat), dropoffLat) ||
    !closeCoordinate(Number(quote.dropoffLng), dropoffLng)
  ) {
    throw new HttpsError("invalid-argument", "The pickup or drop-off location changed. Please request a new quote.");
  }
  if (serviceType && asString(quote.serviceType).toLowerCase() !== serviceType.toLowerCase()) {
    throw new HttpsError("invalid-argument", "The quote does not match this ride service.");
  }
  return quote;
}

export const getPricingConfiguration = onCall(
  { region: REGION },
  async (request) => {
    requireAdmin(request);
    const snapshot = await db.ref("config/pricing/current").get();
    return {
      configured: snapshot.exists(),
      configuration: snapshot.exists() ? snapshot.val() : null,
    };
  }
);

export const updatePricingConfiguration = onCall(
  { region: REGION },
  async (request) => {
    const uid = requireAdmin(request);
    const data = asRecord(request.data);
    const input = data.configuration ?? data.pricingConfiguration;
    if (!input || typeof input !== "object") {
      throw new HttpsError("invalid-argument", "A complete pricing configuration is required.");
    }
    const normalized = normalizePricingConfiguration(input);
    const currentRef = db.ref("config/pricing/current");
    const transaction = await currentRef.transaction((currentValue) => ({
      ...normalized,
      version: Number(asRecord(currentValue).version ?? 0) + 1,
      updatedAtTimestamp: Date.now(),
      updatedBy: uid,
    }));

    if (!transaction.committed || !transaction.snapshot.exists()) {
      throw new HttpsError("aborted", "Pricing configuration could not be published. Please retry.");
    }
    const published = asRecord(transaction.snapshot.val());
    const version = Number(published.version);
    await db.ref("config/pricing/versions/" + version).set(published);

    logger.info("Administrator published pricing configuration", { uid, version });
    return { success: true, version, updatedAtTimestamp: published.updatedAtTimestamp };
  }
);

/** Backward-compatible, task-specific name for the full administrator pricing update. */
export const adminUpdatePricing = updatePricingConfiguration;

/**
 * Updates only commission rules while preserving all delivery, ride, fee and refund rules.
 * Each update is versioned exactly like a complete pricing configuration publication.
 */
export const adminSetCommissionRules = onCall(
  { region: REGION },
  async (request) => {
    const uid = requireAdmin(request);
    const data = asRecord(request.data);
    const input = asRecord(data.commissions ?? data.commissionRules ?? data);
    const requested: Partial<PricingConfiguration["commissions"]> = {};

    if (input.foodBps !== undefined) requested.foodBps = boundedBasisPoints(input.foodBps, "food commission");
    if (input.goodsBps !== undefined) requested.goodsBps = boundedBasisPoints(input.goodsBps, "goods commission");
    if (input.deliveryBps !== undefined) requested.deliveryBps = boundedBasisPoints(input.deliveryBps, "delivery commission");
    if (input.ridesBps !== undefined) requested.ridesBps = boundedBasisPoints(input.ridesBps, "ride commission");
    if (Object.keys(requested).length === 0) {
      throw new HttpsError("invalid-argument", "Provide at least one commission rate to update.");
    }

    const currentRef = db.ref("config/pricing/current");
    const transaction = await currentRef.transaction((currentValue) => {
      if (!currentValue) return;
      const current = normalizePricingConfiguration(currentValue);
      return {
        ...current,
        commissions: { ...current.commissions, ...requested },
        version: current.version + 1,
        updatedAtTimestamp: Date.now(),
        updatedBy: uid,
      };
    });

    if (!transaction.committed || !transaction.snapshot.exists()) {
      throw new HttpsError("aborted", "Commission rules could not be published. Initialize pricing first, then retry.");
    }
    const published = asRecord(transaction.snapshot.val());
    const version = Number(published.version);
    await db.ref("config/pricing/versions/" + version).set(published);

    logger.info("Administrator updated commission rules", { uid, version, fields: Object.keys(requested) });
    return {
      success: true,
      version,
      commissions: published.commissions,
      updatedAtTimestamp: published.updatedAtTimestamp,
    };
  }
);

async function buildRouteQuote(
  uid: string,
  data: Record<string, unknown>,
  forcedQuoteType?: "DELIVERY" | "RIDE"
): Promise<Record<string, unknown>> {
    const quoteType = forcedQuoteType ?? asString(data.quoteType).toUpperCase();
    if (quoteType !== "DELIVERY" && quoteType !== "RIDE") {
      throw new HttpsError("invalid-argument", "Quote type must be DELIVERY or RIDE.");
    }

    const pickupLat = asNumber(data.pickupLat, "pickup latitude");
    const pickupLng = asNumber(data.pickupLng, "pickup longitude");
    const dropoffLat = asNumber(data.dropoffLat, "drop-off latitude");
    const dropoffLng = asNumber(data.dropoffLng, "drop-off longitude");
    validateCoordinates(pickupLat, pickupLng, "Pickup");
    validateCoordinates(dropoffLat, dropoffLng, "Drop-off");

    const config = await getCurrentPricingConfiguration();
    const serviceType = (asString(data.serviceType) || "ride").toLowerCase();
    const route = await calculateGoogleRoute(
      pickupLat, pickupLng, dropoffLat, dropoffLng,
      quoteType === "RIDE" ? serviceType : "car"
    );
    let amountCentavos: number;
    let feeKind: "deliveryFeeCentavos" | "fareCentavos";
    let additionalChargesCentavos = 0;
    let deliveryFeeCentavos = 0;
    let snapshot: Record<string, unknown>;

    if (quoteType === "DELIVERY") {
      deliveryFeeCentavos = calculateDeliveryFeeCentavos(route.distanceMeters, config.delivery);
      additionalChargesCentavos = config.additionalCharges.deliveryCentavos;
      amountCentavos = deliveryFeeCentavos + additionalChargesCentavos;
      feeKind = "deliveryFeeCentavos";
      snapshot = pricingSnapshot(config, "DELIVERY", route, {
        deliveryFeeCentavos,
        additionalChargesCentavos,
      });
    } else {
      if (!isSupportedRideType(serviceType)) {
        throw new HttpsError("invalid-argument", "Unsupported ride service type.");
      }
      const rule = resolveRidePricingRule(config, serviceType);
      if (!rule) {
        throw new HttpsError("failed-precondition", "Ride pricing is not configured for " + serviceType + ".");
      }
      const fare = calculateRideFareCentavos(route.distanceMeters, rule, config.additionalCharges.ridesCentavos);
      amountCentavos = fare.fareCentavos;
      additionalChargesCentavos = fare.optionalChargesCentavos;
      feeKind = "fareCentavos";
      snapshot = pricingSnapshot(config, "RIDES", route, {
        serviceType,
        optionalChargesCentavos: fare.optionalChargesCentavos,
        fareBreakdown: {
          baseFareCentavos: fare.baseFareCentavos,
          distanceChargeCentavos: fare.distanceChargeCentavos,
          additionalChargesCentavos: fare.optionalChargesCentavos,
          adjustmentCentavos: fare.adjustmentCentavos,
          totalCentavos: fare.fareCentavos,
        },
      });
    }

    if (!Number.isSafeInteger(amountCentavos) || amountCentavos <= 0) {
      throw new HttpsError("failed-precondition", "The server-calculated quote must be greater than zero.");
    }

    const quoteId = db.ref("pricingQuotes").push().key || randomUUID();
    const timestamp = Date.now();
    const quote = {
      id: quoteId,
      userId: uid,
      quoteType,
      serviceType: quoteType === "RIDE" ? serviceType : null,
      pickupLat,
      pickupLng,
      dropoffLat,
      dropoffLng,
      distanceMeters: route.distanceMeters,
      distanceKm: Number((route.distanceMeters / 1000).toFixed(3)),
      routeDuration: route.duration,
      routeTravelMode: route.travelMode,
      routePolyline: route.encodedPolyline,
      fareBreakdown: asRecord(snapshot.fareBreakdown),
      [feeKind]: feeKind === "deliveryFeeCentavos" ? deliveryFeeCentavos : amountCentavos,
      additionalChargesCentavos,
      amountCentavos,
      amount: phpFromCents(amountCentavos),
      pricingVersion: config.version,
      pricingConfiguration: config,
      pricingSnapshot: snapshot,
      createdAtTimestamp: timestamp,
      expiresAtTimestamp: timestamp + QUOTE_LIFETIME_MS,
    };
    await db.ref("pricingQuotes/" + quoteId).set(quote);

    return {
      quoteId,
      quoteType,
      amountCentavos,
      amount: phpFromCents(amountCentavos),
      deliveryFeeCentavos,
      additionalChargesCentavos,
      distanceMeters: route.distanceMeters,
      distanceKm: quote.distanceKm,
      routeDuration: route.duration,
      routeTravelMode: route.travelMode,
      routePolyline: route.encodedPolyline,
      fareBreakdown: asRecord(snapshot.fareBreakdown),
      pricingVersion: config.version,
      expiresAtTimestamp: quote.expiresAtTimestamp,
    };
}

export const createPricingQuote = onCall(
  { region: REGION, secrets: [GOOGLE_ROUTES_API_KEY] },
  async (request) => buildRouteQuote(requireUid(request), asRecord(request.data))
);

export const quoteTrip = onCall(
  { region: REGION, secrets: [GOOGLE_ROUTES_API_KEY] },
  async (request) => buildRouteQuote(requireUid(request), asRecord(request.data), "RIDE")
);

/** Returns a road route only for the signed-in customer of an active delivery order. */
export const getTrackingRoute = onCall(
  { region: REGION, secrets: [GOOGLE_ROUTES_API_KEY] },
  async (request) => {
    const uid = requireUid(request);
    const data = asRecord(request.data);
    const orderId = asString(data.orderId);
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(orderId)) {
      throw new HttpsError("invalid-argument", "A valid order ID is required.");
    }

    const orderSnapshot = await db.ref("orders/" + orderId).get();
    if (!orderSnapshot.exists()) {
      throw new HttpsError("not-found", "The order could not be found.");
    }
    const order = asRecord(orderSnapshot.val());
    const isCustomer = asString(order.clientId) === uid;
    const isAssignedCourier = asString(order.deliveryId) === uid;
    const audience = asString(data.audience).toUpperCase();
    if ((!isCustomer && !isAssignedCourier) || (audience === "COURIER" && !isAssignedCourier)) {
      throw new HttpsError("permission-denied", "You cannot access this delivery route.");
    }
    if (asString(order.status).toUpperCase() !== "OUT_FOR_DELIVERY") {
      throw new HttpsError("failed-precondition", "Live routing is available while the order is out for delivery.");
    }

    const deliveryId = asString(order.deliveryId);
    if (!deliveryId) {
      throw new HttpsError("failed-precondition", "A courier has not been assigned to this order.");
    }
    const courierSnapshot = await db.ref("publicDrivers/" + deliveryId + "/currentLocation").get();
    if (!courierSnapshot.exists()) {
      throw new HttpsError("unavailable", "The courier location is not available yet.");
    }

    const location = asRecord(courierSnapshot.val());
    const originLat = asNumber(location.latitude, "courier latitude");
    const originLng = asNumber(location.longitude, "courier longitude");
    validateCoordinates(originLat, originLng, "Courier");
    const deliveryStage = asString(order.deliveryStage).toUpperCase() || "TO_PICKUP";
    const routeToPickup = audience === "COURIER" && deliveryStage !== "PICKED_UP";
    const destinationLat = asNumber(routeToPickup ? order.pickupLat : order.dropoffLat, routeToPickup ? "pickup latitude" : "drop-off latitude");
    const destinationLng = asNumber(routeToPickup ? order.pickupLng : order.dropoffLng, routeToPickup ? "pickup longitude" : "drop-off longitude");
    validateCoordinates(destinationLat, destinationLng, routeToPickup ? "Pickup" : "Drop-off");

    const route = await calculateGoogleRoute(originLat, originLng, destinationLat, destinationLng, "car");
    let recipientName = "";
    let recipientPhone = "";
    if (audience === "COURIER") {
      const recipient = asRecord((await db.ref("users/" + asString(order.clientId)).get()).val());
      recipientName = asString(recipient.fullName) || asString(order.clientName) || "Recipient";
      recipientPhone = asString(recipient.phoneNumber) || asString(recipient.phone) || asString(order.clientPhone);
    }
    return {
      distanceMeters: route.distanceMeters,
      duration: route.duration,
      encodedPolyline: route.encodedPolyline,
      routeTo: routeToPickup ? "PICKUP" : "DROPOFF",
      deliveryStage,
      recipientName,
      recipientPhone,
    };
  }
);


// -----------------------------------------------------------------------------
// Explicit quoteOrder/createOrder API. The quote stores trusted catalogue lines and
// the pricing snapshot; createOrder accepts the quote ID, rechecks availability and
// approval, then persists the quote's locked price snapshot.
// -----------------------------------------------------------------------------

function orderQuoteBreakdown(
  orderType: OrderType,
  items: CatalogLine[],
  deliveryFeeCentavos: number,
  config: PricingConfiguration
): {
  subtotalCentavos: number;
  additionalChargesCentavos: number;
  totalCentavos: number;
  breakdown: Record<string, number>;
  commissionRatesBps: { merchant: number; delivery: number };
} {
  const subtotalBig = items.reduce(
    (sum, item) => sum + BigInt(centsFromPhp(item.basePrice, "catalog item price")) * BigInt(item.quantity),
    0n
  );
  const subtotalCentavos = Number(subtotalBig);
  if (!Number.isSafeInteger(subtotalCentavos) || subtotalCentavos <= 0) {
    throw new HttpsError("failed-precondition", "The server-calculated item subtotal is invalid.");
  }

  const categoryChargeCentavos = orderType === "FOOD"
    ? config.additionalCharges.foodCentavos
    : config.additionalCharges.goodsCentavos;
  const additionalChargesCentavos = categoryChargeCentavos + config.additionalCharges.deliveryCentavos;
  const totalBig = BigInt(subtotalCentavos) + BigInt(deliveryFeeCentavos) + BigInt(additionalChargesCentavos);
  const totalCentavos = Number(totalBig);
  if (!Number.isSafeInteger(totalCentavos) || totalCentavos <= 0) {
    throw new HttpsError("failed-precondition", "The server-calculated order total is invalid.");
  }

  const merchantRate = orderType === "FOOD" ? config.commissions.foodBps : config.commissions.goodsBps;
  const merchantCommission = commissionCentavos(subtotalCentavos, merchantRate);
  const deliveryCommission = commissionCentavos(deliveryFeeCentavos, config.commissions.deliveryBps);
  const merchantShare = subtotalCentavos - merchantCommission;
  const courierShare = deliveryFeeCentavos - deliveryCommission;
  const platformShare = merchantCommission + deliveryCommission + additionalChargesCentavos;

  return {
    subtotalCentavos,
    additionalChargesCentavos,
    totalCentavos,
    commissionRatesBps: { merchant: merchantRate, delivery: config.commissions.deliveryBps },
    breakdown: {
      subtotal: subtotalCentavos,
      deliveryFee: deliveryFeeCentavos,
      serviceFee: additionalChargesCentavos,
      discount: 0,
      tax: 0,
      total: totalCentavos,
      merchantShare,
      courierShare,
      driverShare: 0,
      platformShare,
      gatewayFee: 0,
      merchantCommission: merchantCommission,
      deliveryCommission: deliveryCommission,
      rideCommission: 0,
    },
  };
}

export const quoteOrder = onCall(
  { region: REGION, secrets: [GOOGLE_ROUTES_API_KEY] },
  async (request) => {
    const uid = requireUid(request);
    const data = asRecord(request.data);
    const orderTypeValue = asString(data.orderType).toUpperCase();
    if (orderTypeValue !== "FOOD" && orderTypeValue !== "RETAIL") {
      throw new HttpsError("invalid-argument", "Order type must be FOOD or RETAIL.");
    }
    const orderType = orderTypeValue as OrderType;
    const sourceId = asString(data.sourceId);
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(sourceId)) {
      throw new HttpsError("invalid-argument", "A valid merchant or shop ID is required.");
    }
    const requestedItems = normalizeCatalogItems(data.items);
    const dropoffLocation = asString(data.dropoffLocation);
    const dropoffLat = asNumber(data.dropoffLat, "drop-off latitude");
    const dropoffLng = asNumber(data.dropoffLng, "drop-off longitude");
    validateCoordinates(dropoffLat, dropoffLng, "Drop-off");
    if (!dropoffLocation) {
      throw new HttpsError("invalid-argument", "A drop-off address is required.");
    }

    const catalog = await loadOrderCatalog(uid, orderType, sourceId, requestedItems);
    validateCoordinates(catalog.pickupLat, catalog.pickupLng, "Merchant pickup");
    const route = await calculateGoogleRoute(catalog.pickupLat, catalog.pickupLng, dropoffLat, dropoffLng);
    const config = await getCurrentPricingConfiguration();
    const deliveryFeeCentavos = calculateDeliveryFeeCentavos(route.distanceMeters, config.delivery);
    const calculated = orderQuoteBreakdown(orderType, catalog.items, deliveryFeeCentavos, config);

    const snapshot = {
      ...pricingSnapshot(config, orderType === "FOOD" ? "FOOD_DELIVERY" : "GOODS_DELIVERY", route),
      commissionRatesBps: calculated.commissionRatesBps,
      additionalChargesCentavos: calculated.additionalChargesCentavos,
      cancellationFees: config.cancellationFees,
      refundPolicy: config.refundPolicy,
      catalog: {
        sourceId,
        sourceName: catalog.sourceName,
        merchantId: catalog.merchantId,
        items: catalog.items.map(item => ({
          id: item.id,
          name: item.name,
          basePriceCentavos: centsFromPhp(item.basePrice, "catalog item price"),
          quantity: item.quantity,
          specialInstructions: item.specialInstructions,
        })),
      },
    };

    const quoteId = db.ref("orderQuotes").push().key || randomUUID();
    const timestamp = Date.now();
    const quote = {
      id: quoteId,
      userId: uid,
      quoteType: "ORDER",
      orderType,
      sourceId,
      catalog: {
        sourceId,
        sourceName: catalog.sourceName,
        merchantId: catalog.merchantId,
        pickupLocation: catalog.pickupLocation,
        pickupLat: catalog.pickupLat,
        pickupLng: catalog.pickupLng,
        items: catalog.items.map(item => ({
          id: item.id,
          name: item.name,
          basePriceCentavos: centsFromPhp(item.basePrice, "catalog item price"),
          quantity: item.quantity,
          specialInstructions: item.specialInstructions,
        })),
      },
      requestedItems,
      dropoffLocation,
      dropoffLat,
      dropoffLng,
      pickupLocation: catalog.pickupLocation,
      pickupLat: catalog.pickupLat,
      pickupLng: catalog.pickupLng,
      distanceMeters: route.distanceMeters,
      distanceKm: Number((route.distanceMeters / 1000).toFixed(3)),
      routeDuration: route.duration,
      routeTravelMode: route.travelMode,
      routePolyline: route.encodedPolyline,
      deliveryFeeCentavos,
      subtotalCentavos: calculated.subtotalCentavos,
      additionalChargesCentavos: calculated.additionalChargesCentavos,
      totalCentavos: calculated.totalCentavos,
      amount: phpFromCents(calculated.totalCentavos),
      breakdown: calculated.breakdown,
      commissionRatesBps: calculated.commissionRatesBps,
      pricingVersion: config.version,
      pricingConfiguration: config,
      pricingSnapshot: snapshot,
      createdAtTimestamp: timestamp,
      expiresAtTimestamp: timestamp + QUOTE_LIFETIME_MS,
      status: "QUOTED",
    };
    await db.ref("orderQuotes/" + quoteId).set(quote);

    return {
      quoteId,
      quoteType: "ORDER",
      orderType,
      items: catalog.items.map(item => ({
        id: item.id,
        name: item.name,
        unitPriceCentavos: centsFromPhp(item.basePrice, "catalog item price"),
        quantity: item.quantity,
        specialInstructions: item.specialInstructions,
      })),
      subtotalCentavos: calculated.subtotalCentavos,
      deliveryFeeCentavos,
      additionalChargesCentavos: calculated.additionalChargesCentavos,
      totalCentavos: calculated.totalCentavos,
      amount: phpFromCents(calculated.totalCentavos),
      distanceMeters: route.distanceMeters,
      distanceKm: Number((route.distanceMeters / 1000).toFixed(3)),
      routeDuration: route.duration,
      routeTravelMode: route.travelMode,
      routePolyline: route.encodedPolyline,
      pricingVersion: config.version,
      breakdown: calculated.breakdown,
      expiresAtTimestamp: quote.expiresAtTimestamp,
    };
  }
);

export const createOrder = onCall(
  { region: REGION },
  async (request) => {
    const uid = requireUid(request);
    const data = asRecord(request.data);
    const paymentMethod = parsePaymentMethod(data.paymentMethod);
    const quoteId = asString(data.quoteId);
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(quoteId)) {
      throw new HttpsError("invalid-argument", "A valid order quote ID is required.");
    }
    const orderId = parseIdempotencyKey(data.idempotencyKey);
    const existingSnapshot = await db.ref("orders/" + orderId).get();
    if (existingSnapshot.exists()) {
      const existing = asRecord(existingSnapshot.val());
      if (asString(existing.clientId) !== uid) {
        throw new HttpsError("already-exists", "This idempotency key has already been used.");
      }
      if (asString(existing.paymentMethod).toUpperCase() !== paymentMethod) {
        throw new HttpsError("failed-precondition", "This checkout key was already used with a different payment method.");
      }
      const existingPayment = asRecord((await db.ref("payments/" + orderId).get()).val());
      return {
        orderId,
        paymentId: asString(existingPayment.providerReference) || asString(existing.paymentId),
        paymentStatus: asString(existing.paymentStatus) || "UNPAID",
        amountCentavos: Number(existing.totalPriceCentavos ?? 0),
        totalCentavos: Number(existing.totalPriceCentavos ?? 0),
        amount: Number(existing.totalPrice ?? 0),
        pricingVersion: Number(existing.pricingVersion ?? 0),
        distanceMeters: Number(existing.distanceMeters ?? 0),
        breakdown: existing.breakdown ?? null,
        checkoutUrl: asString(existingPayment.checkoutUrl) || null,
        reused: true,
      };
    }

    const quoteRef = db.ref("orderQuotes/" + quoteId);
    const quoteSnapshot = await quoteRef.get();
    if (!quoteSnapshot.exists()) {
      throw new HttpsError("not-found", "The order quote was not found. Request a new quote.");
    }
    const quote = asRecord(quoteSnapshot.val());
    if (asString(quote.userId) !== uid || asString(quote.quoteType) !== "ORDER") {
      throw new HttpsError("permission-denied", "This order quote does not belong to this account.");
    }
    if (Number(quote.expiresAtTimestamp) < Date.now()) {
      throw new HttpsError("deadline-exceeded", "The order quote expired. Request a new quote.");
    }
    if (asString(quote.status) !== "QUOTED" && asString(quote.createdOrderId) !== orderId) {
      throw new HttpsError("already-exists", "This order quote has already been used.");
    }

    const orderTypeValue = asString(quote.orderType).toUpperCase();
    if (orderTypeValue !== "FOOD" && orderTypeValue !== "RETAIL") {
      throw new HttpsError("failed-precondition", "The order quote contains an invalid order type.");
    }
    const orderType = orderTypeValue as OrderType;
    const sourceId = asString(quote.sourceId);
    const requestedItems = normalizeCatalogItems(quote.requestedItems);
    const currentCatalog = await loadOrderCatalog(uid, orderType, sourceId, requestedItems);

    // Recheck current availability/approval/stock, but preserve the quoted unit prices and merchant
    // details so a catalogue or admin rate change cannot silently modify this accepted quote.
    const quotedCatalog = asRecord(quote.catalog);
    const quotedItems = Array.isArray(quotedCatalog.items) ? quotedCatalog.items : [];
    const items: CatalogLine[] = quotedItems.map((raw) => {
      const item = asRecord(raw);
      const id = asString(item.id);
      const current = currentCatalog.items.find(line => line.id === id);
      if (!current || current.quantity !== Number(item.quantity)) {
        throw new HttpsError("failed-precondition", "A quoted item is no longer available in the requested quantity.");
      }
      return {
        id,
        name: asString(item.name),
        basePrice: phpFromCents(nonNegativeInteger(item.basePriceCentavos, "quoted unit price")),
        quantity: Number(item.quantity),
        specialInstructions: asString(item.specialInstructions),
      };
    });
    if (items.length !== requestedItems.length) {
      throw new HttpsError("failed-precondition", "The quoted catalogue snapshot is incomplete.");
    }

    const orderRef = db.ref("orders/" + orderId);
    const lock = await quoteRef.transaction((currentValue) => {
      const current = asRecord(currentValue);
      if (
        asString(current.userId) !== uid ||
        asString(current.status) !== "QUOTED" && asString(current.createdOrderId) !== orderId ||
        Number(current.expiresAtTimestamp) < Date.now()
      ) {
        return;
      }
      return {
        ...current,
        status: "USED",
        createdOrderId: orderId,
        usedAtTimestamp: Date.now(),
      };
    });
    if (!lock.committed) {
      const refreshedOrder = await orderRef.get();
      if (refreshedOrder.exists() && asString(asRecord(refreshedOrder.val()).clientId) === uid) {
        const value = asRecord(refreshedOrder.val());
        return {
          orderId,
          paymentId: asString(asRecord((await db.ref("payments/" + orderId).get()).val()).providerReference) || asString(value.paymentId),
          paymentStatus: asString(value.paymentStatus) || "UNPAID",
          amountCentavos: Number(value.totalPriceCentavos ?? 0),
          totalCentavos: Number(value.totalPriceCentavos ?? 0),
          amount: Number(value.totalPrice ?? 0),
          pricingVersion: Number(value.pricingVersion ?? 0),
          breakdown: value.breakdown ?? null,
          reused: true,
        };
      }
      throw new HttpsError("already-exists", "This order quote is already being used.");
    }

    const pricingConfig = normalizePricingConfiguration(quote.pricingConfiguration);
    const timestamp = Date.now();
    const subtotalCentavos = nonNegativeInteger(quote.subtotalCentavos, "quoted subtotal");
    const deliveryFeeCentavos = nonNegativeInteger(quote.deliveryFeeCentavos, "quoted delivery fee");
    const additionalChargesCentavos = nonNegativeInteger(quote.additionalChargesCentavos, "quoted additional charges");
    const totalCentavos = nonNegativeInteger(quote.totalCentavos, "quoted order total");
    const expectedTotal = subtotalCentavos + deliveryFeeCentavos + additionalChargesCentavos;
    if (expectedTotal !== totalCentavos || !Number.isSafeInteger(expectedTotal) || totalCentavos <= 0) {
      throw new HttpsError("failed-precondition", "The stored quote breakdown is inconsistent.");
    }
    const snapshot = asRecord(quote.pricingSnapshot);
    const order = {
      id: orderId,
      clientId: uid,
      orderType,
      sourceId,
      sourceName: asString(quotedCatalog.sourceName),
      merchantId: asString(quotedCatalog.merchantId) || currentCatalog.merchantId,
      items,
      subtotal: phpFromCents(subtotalCentavos),
      deliveryFee: phpFromCents(deliveryFeeCentavos),
      totalPrice: phpFromCents(totalCentavos),
      subtotalCentavos,
      deliveryFeeCentavos,
      totalPriceCentavos: totalCentavos,
      distanceMeters: nonNegativeInteger(quote.distanceMeters, "quoted route distance"),
      distanceKm: Number(quote.distanceKm),
      routeDuration: asString(quote.routeDuration),
      pricingVersion: nonNegativeInteger(quote.pricingVersion, "pricing version"),
      pricingQuoteId: quoteId,
      pricingSnapshot: snapshot,
      breakdown: quote.breakdown,
      // GCash orders remain hidden until capture; cash orders are immediately available to merchants.
      status: paymentMethod === "CASH" ? "PLACED" : "AWAITING_PAYMENT",
      deliveryId: "",
      deliveryName: "",
      deliveryPhone: "",
      pickupLocation: asString(quotedCatalog.pickupLocation) || asString(quote.pickupLocation),
      pickupLat: Number(quote.pickupLat),
      pickupLng: Number(quote.pickupLng),
      dropoffLocation: asString(quote.dropoffLocation),
      dropoffLat: Number(quote.dropoffLat),
      dropoffLng: Number(quote.dropoffLng),
      createdAtTimestamp: timestamp,
      paymentMethod,
      paymentStatus: "UNPAID",
      paymentId: paymentMethod === "CASH" ? "cash_" + orderId : "",
    };
    const payment = {
      entityType: "ORDER",
      entityId: orderId,
      clientId: uid,
      merchantId: order.merchantId,
      provider: paymentMethod === "GCASH" ? "XENDIT" : "CASH",
      providerReference: paymentMethod === "CASH" ? "cash_" + orderId : "",
      channelCode: paymentMethod,
      status: "UNPAID",
      amount: phpFromCents(totalCentavos),
      amountCentavos: totalCentavos,
      breakdown: quote.breakdown,
      pricingVersion: pricingConfig.version,
      currency: "PHP",
      checkoutUrl: "",
      createdAtTimestamp: timestamp,
      updatedAtTimestamp: timestamp,
    };
    const updates: Record<string, unknown> = {};
    updates["orders/" + orderId] = order;
    updates["payments/" + orderId] = payment;
    await db.ref().update(updates);

    logger.info("Server created order from locked pricing quote", { orderId, quoteId, uid, pricingVersion: pricingConfig.version, paymentMethod });
    return {
      orderId,
      paymentId: asString(payment.providerReference),
      paymentStatus: "UNPAID",
      totalCentavos,
      amountCentavos: totalCentavos,
      amount: phpFromCents(totalCentavos),
      distanceMeters: nonNegativeInteger(quote.distanceMeters, "quoted route distance"),
      pricingVersion: pricingConfig.version,
      breakdown: quote.breakdown,
      checkoutUrl: null,
      reused: false,
    };
  }
);

/**
 * Creates a trip from a server-issued quote. The caller cannot submit or alter the fare,
 * billable distance, commission, or pricing snapshot.
 */
export const createTrip = onCall(
  { region: REGION },
  async (request) => {
    const uid = requireUid(request);
    const data = asRecord(request.data);
    const paymentMethod = parsePaymentMethod(data.paymentMethod);
    const quoteId = asString(data.quoteId);
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(quoteId)) {
      throw new HttpsError("invalid-argument", "A valid ride quote ID is required.");
    }
    const tripId = parseIdempotencyKey(data.idempotencyKey);
    const existingSnapshot = await db.ref("trips/" + tripId).get();
    if (existingSnapshot.exists()) {
      const existing = asRecord(existingSnapshot.val());
      if (asString(existing.passengerId) !== uid) {
        throw new HttpsError("already-exists", "This idempotency key has already been used.");
      }
      if (asString(existing.paymentMethod).toUpperCase() !== paymentMethod) {
        throw new HttpsError("failed-precondition", "This checkout key was already used with a different payment method.");
      }
      const existingPayment = asRecord((await db.ref("payments/" + tripId).get()).val());
      return {
        tripId,
        paymentId: asString(existingPayment.providerReference) || asString(existing.paymentId),
        paymentStatus: asString(existing.paymentStatus) || "UNPAID",
        fareCentavos: Number(existing.fareCentavos ?? 0),
        amountCentavos: Number(existing.fareCentavos ?? 0),
        fare: Number(existing.fare ?? 0),
        amount: Number(existing.fare ?? 0),
        pricingVersion: Number(existing.pricingVersion ?? 0),
        distanceMeters: Number(existing.distanceMeters ?? 0),
        breakdown: existing.breakdown ?? null,
        checkoutUrl: asString(existingPayment.checkoutUrl) || null,
        reused: true,
      };
    }

    const quoteRef = db.ref("pricingQuotes/" + quoteId);
    const quoteSnapshot = await quoteRef.get();
    if (!quoteSnapshot.exists()) {
      throw new HttpsError("not-found", "The ride quote was not found. Request a new quote.");
    }
    const quote = asRecord(quoteSnapshot.val());
    if (asString(quote.userId) !== uid || asString(quote.quoteType) !== "RIDE") {
      throw new HttpsError("permission-denied", "This ride quote does not belong to this account.");
    }
    if (Number(quote.expiresAtTimestamp) < Date.now()) {
      throw new HttpsError("deadline-exceeded", "The ride quote expired. Request a new quote.");
    }
    const serviceType = asString(quote.serviceType).toLowerCase();
    if (!isSupportedRideType(serviceType) || (asString(data.serviceType) && asString(data.serviceType).toLowerCase() !== serviceType)) {
      throw new HttpsError("invalid-argument", "The requested ride service does not match the quote.");
    }
    const targetDriverId = asString(data.targetDriverId);
    await validateTargetDriverForRide(targetDriverId, serviceType);

    const config = normalizePricingConfiguration(quote.pricingConfiguration);
    const rideRule = resolveRidePricingRule(config, serviceType);
    if (!rideRule) {
      throw new HttpsError("failed-precondition", "The quoted ride service no longer has a valid pricing snapshot.");
    }
    const fareCentavos = nonNegativeInteger(quote.fareCentavos, "quoted ride fare");
    const route = {
      provider: "GOOGLE_ROUTES_API" as const,
      distanceMeters: nonNegativeInteger(quote.distanceMeters, "quoted route distance"),
      duration: asString(quote.routeDuration),
      travelMode: resolveRouteTravelMode(serviceType),
      encodedPolyline: asString(quote.routePolyline),
    };
    const pricingSnapshotValue = asRecord(quote.pricingSnapshot);
    const optionalChargesCentavos = nonNegativeInteger(
      pricingSnapshotValue.optionalChargesCentavos ?? quote.additionalChargesCentavos ?? 0,
      "quoted optional ride charges"
    );
    const rideCommissionCentavos = commissionCentavos(fareCentavos, config.commissions.ridesBps);
    const rideBreakdown = {
      subtotal: fareCentavos - optionalChargesCentavos,
      deliveryFee: 0,
      serviceFee: optionalChargesCentavos,
      discount: 0,
      tax: 0,
      total: fareCentavos,
      merchantShare: 0,
      courierShare: 0,
      driverShare: fareCentavos - rideCommissionCentavos,
      platformShare: rideCommissionCentavos,
      gatewayFee: 0,
      merchantCommission: 0,
      deliveryCommission: 0,
      rideCommission: rideCommissionCentavos,
    };
    const timestamp = Date.now();

    const lock = await quoteRef.transaction((currentValue) => {
      const current = asRecord(currentValue);
      if (
        asString(current.userId) !== uid ||
        asString(current.quoteType) !== "RIDE" ||
        Number(current.expiresAtTimestamp) < Date.now() ||
        (asString(current.status) === "USED" && asString(current.createdTripId) !== tripId)
      ) {
        return;
      }
      return {
        ...current,
        status: "USED",
        createdTripId: tripId,
        usedAtTimestamp: Date.now(),
      };
    });
    if (!lock.committed) {
      const refreshed = await db.ref("trips/" + tripId).get();
      if (refreshed.exists() && asString(asRecord(refreshed.val()).passengerId) === uid) {
        const saved = asRecord(refreshed.val());
        return {
          tripId,
          paymentId: asString(asRecord((await db.ref("payments/" + tripId).get()).val()).providerReference) || asString(saved.paymentId),
          paymentStatus: asString(saved.paymentStatus) || "UNPAID",
          fareCentavos: Number(saved.fareCentavos ?? 0),
          amountCentavos: Number(saved.fareCentavos ?? 0),
          fare: Number(saved.fare ?? 0),
          amount: Number(saved.fare ?? 0),
          pricingVersion: Number(saved.pricingVersion ?? 0),
          distanceMeters: Number(saved.distanceMeters ?? 0),
          breakdown: saved.breakdown ?? null,
          checkoutUrl: null,
          reused: true,
        };
      }
      throw new HttpsError("already-exists", "This ride quote is already being used.");
    }

    const pickupLat = asNumber(quote.pickupLat, "quoted pickup latitude");
    const pickupLng = asNumber(quote.pickupLng, "quoted pickup longitude");
    const dropoffLat = asNumber(quote.dropoffLat, "quoted drop-off latitude");
    const dropoffLng = asNumber(quote.dropoffLng, "quoted drop-off longitude");
    const fare = phpFromCents(fareCentavos);
    const trip = {
      id: tripId,
      passengerId: uid,
      passengerName: asString(data.passengerName) || "Passenger",
      passengerPhone: asString(data.passengerPhone),
      pickupLocation: asString(data.pickupLocation),
      dropoffLocation: asString(data.dropoffLocation),
      pickupLat,
      pickupLng,
      dropoffLat,
      dropoffLng,
      fare,
      fareCentavos,
      distanceMeters: route.distanceMeters,
      distanceKm: Number((route.distanceMeters / 1000).toFixed(3)),
      routeDuration: route.duration,
      routeTravelMode: route.travelMode,
      routePolyline: route.encodedPolyline,
      pricingVersion: config.version,
      pricingQuoteId: quoteId,
      pricingSnapshot: pricingSnapshotValue,
      breakdown: rideBreakdown,
      serviceType: serviceType.toUpperCase(),
      driverId: targetDriverId,
      // GCash trips remain hidden until capture; cash trips can be dispatched immediately.
      status: paymentMethod === "CASH" ? "REQUESTED" : "AWAITING_PAYMENT",
      paymentMethod,
      paymentStatus: "UNPAID",
      paymentId: paymentMethod === "CASH" ? "cash_" + tripId : "",
      createdAtTimestamp: timestamp,
    };
    const payment = {
      entityType: "TRIP",
      entityId: tripId,
      clientId: uid,
      provider: paymentMethod === "GCASH" ? "XENDIT" : "CASH",
      providerReference: paymentMethod === "CASH" ? "cash_" + tripId : "",
      channelCode: paymentMethod,
      status: "UNPAID",
      amount: fare,
      amountCentavos: fareCentavos,
      breakdown: rideBreakdown,
      pricingVersion: config.version,
      currency: "PHP",
      checkoutUrl: "",
      createdAtTimestamp: timestamp,
      updatedAtTimestamp: timestamp,
    };
    const updates: Record<string, unknown> = {};
    updates["trips/" + tripId] = trip;
    updates["payments/" + tripId] = payment;
    await db.ref().update(updates);

    logger.info("Server created trip from locked pricing quote", { tripId, quoteId, uid, pricingVersion: config.version, paymentMethod });
    return {
      tripId,
      paymentId: asString(payment.providerReference),
      paymentStatus: "UNPAID",
      fareCentavos,
      amountCentavos: fareCentavos,
      fare,
      amount: fare,
      distanceMeters: route.distanceMeters,
      distanceKm: Number((route.distanceMeters / 1000).toFixed(3)),
      pricingVersion: config.version,
      breakdown: rideBreakdown,
      checkoutUrl: null,
      reused: false,
    };
  }
);


export const submitShopApplication = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  const data = asRecord(request.data);
  const name = asString(data.name).slice(0, 100);
  const description = asString(data.description).slice(0, 1000);
  const category = asString(data.category).slice(0, 80);
  const phone = asString(data.phone).slice(0, 40);
  const address = asString(data.address).slice(0, 300);
  const imageUrl = asString(data.imageUrl).slice(0, 2048);
  const latitude = asNumber(data.latitude, "Shop pickup latitude");
  const longitude = asNumber(data.longitude, "Shop pickup longitude");
  validateCoordinates(latitude, longitude, "Shop pickup");
  if (!name || !category || !phone || !address) throw new HttpsError("invalid-argument", "Shop name, category, phone and address are required.");
  const existing = await db.ref("shops").orderByChild("ownerUid").equalTo(uid).get();
  const existingShops: DataSnapshot[] = [];
  existing.forEach((child) => { existingShops.push(child); return false; });
  const pendingOrApproved = existingShops.find((child) => ["PENDING", "APPROVED", "ACTIVE"].includes(asString(asRecord(child.val()).status).toUpperCase()));
  if (pendingOrApproved) {
    const current = asRecord(pendingOrApproved.val());
    if (["APPROVED", "ACTIVE"].includes(asString(current.status).toUpperCase())) throw new HttpsError("already-exists", "Your shop is already approved. Edit its details from your Shop Workplace.");
    throw new HttpsError("already-exists", "Your shop application is already pending review.");
  }
  const rejected = existingShops.find((child) => asString(asRecord(child.val()).status).toUpperCase() === "REJECTED");
  const shopId = rejected?.key ?? db.ref("shops").push().key;
  if (!shopId) throw new HttpsError("internal", "A shop application ID could not be generated.");
  const now = Date.now();
  const ref = db.ref("shops/" + shopId);
  const tx = await ref.transaction((currentValue) => {
    const current = asRecord(currentValue);
    if (currentValue !== null && currentValue !== undefined && asString(current.ownerUid) !== uid) return;
    return { ...current, id: shopId, ownerUid: uid, name, description, category, phone, address, imageUrl, latitude, longitude, status: "PENDING", createdAtTimestamp: Number(current.createdAtTimestamp ?? now) || now, isOnline: false };
  });
  if (!tx.committed) throw new HttpsError("aborted", "Your shop application could not be saved. Please retry.");
  await db.ref("users/" + uid + "/shopStatus").set("PENDING");
  return { shopId, status: "PENDING" };
});

export const claimDeliveryOrder = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  await requireApprovedCourier(uid);
  const orderId = asString(asRecord(request.data).orderId);
  if (!/^[A-Za-z0-9_-]{12,80}$/.test(orderId)) throw new HttpsError("invalid-argument", "A valid order ID is required.");
  const profile = await getUserProfileRecord(uid);
  const name = asString(profile.fullName) || "Delivery";
  const phone = asString(profile.phoneNumber) || asString(profile.phone);
  const paymentSnapshot = await db.ref("payments/" + orderId).get();
  const serverPaymentStatus = asString(asRecord(paymentSnapshot.val()).status).toUpperCase();
  const ref = db.ref("orders/" + orderId);
  const tx = await ref.transaction((currentValue) => {
    if (!currentValue || typeof currentValue !== "object") return;
    const order = asRecord(currentValue);
    const assigned = asString(order.deliveryId);
    const paymentMethod = asString(order.paymentMethod).toUpperCase();
    if (
      asString(order.status).toUpperCase() !== "OUT_FOR_DELIVERY" ||
      (assigned && assigned !== uid) ||
      !["CASH", "GCASH"].includes(paymentMethod) ||
      paymentMethod === "GCASH" &&
        (asString(order.paymentStatus).toUpperCase() !== "PAID" || serverPaymentStatus !== "PAID")
    ) return;
    return { ...order, deliveryId: uid, deliveryName: name, deliveryPhone: phone, deliveryStage: asString(order.deliveryStage) || "TO_PICKUP" };
  });
  if (!tx.committed) throw new HttpsError("failed-precondition", "This order is no longer available for delivery.");
  return { orderId, deliveryId: uid, status: "ASSIGNED" };
});

export const markDeliveryPickedUp = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  await requireApprovedCourier(uid);
  const orderId = asString(asRecord(request.data).orderId);
  if (!/^[A-Za-z0-9_-]{12,80}$/.test(orderId)) {
    throw new HttpsError("invalid-argument", "A valid order ID is required.");
  }
  const ref = db.ref("orders/" + orderId);
  let failure = "";
  const tx = await ref.transaction((currentValue) => {
    if (!currentValue || typeof currentValue !== "object") return;
    const order = asRecord(currentValue);
    if (asString(order.deliveryId) !== uid || asString(order.status).toUpperCase() !== "OUT_FOR_DELIVERY") {
      failure = "This order is not assigned to you or is no longer active.";
      return;
    }
    if (asString(order.deliveryStage).toUpperCase() === "PICKED_UP") return order;
    return { ...order, deliveryStage: "PICKED_UP", pickedUpAtTimestamp: Date.now() };
  });
  if (!tx.committed) throw new HttpsError("failed-precondition", failure || "The pickup could not be confirmed.");
  return { orderId, deliveryStage: "PICKED_UP" };
});

export const releaseDeliveryOrder = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  await requireApprovedCourier(uid);
  const orderId = asString(asRecord(request.data).orderId);
  if (!/^[A-Za-z0-9_-]{12,80}$/.test(orderId)) throw new HttpsError("invalid-argument", "A valid order ID is required.");
  const ref = db.ref("orders/" + orderId);
  const tx = await ref.transaction((currentValue) => {
    if (!currentValue || typeof currentValue !== "object") return;
    const order = asRecord(currentValue);
    if (asString(order.deliveryId) !== uid || asString(order.status).toUpperCase() !== "OUT_FOR_DELIVERY" || asString(order.cashCollectionStatus).toUpperCase() === "COLLECTED" || asString(order.deliveryStage).toUpperCase() === "PICKED_UP") return;
    return { ...order, deliveryId: "", deliveryName: "", deliveryPhone: "", deliveryStage: "" };
  });
  if (!tx.committed) throw new HttpsError("failed-precondition", "This delivery cannot be released.");
  return { orderId, status: "RELEASED" };
});

async function reserveShopOrderStock(orderId: string, order: Record<string, unknown>): Promise<void> {
  if (asString(order.orderType).toUpperCase() !== "RETAIL") return;
  const sourceId = asString(order.sourceId);
  if (!sourceId) throw new HttpsError("failed-precondition", "The shop for this order is missing.");

  const quantities = new Map<string, number>();
  const rawItems = Array.isArray(order.items) ? order.items : [];
  for (const raw of rawItems) {
    const item = asRecord(raw);
    const productId = asString(item.id);
    const quantity = nonNegativeInteger(item.quantity, "ordered product quantity");
    if (!productId || quantity <= 0) throw new HttpsError("failed-precondition", "The order contains an invalid product quantity.");
    quantities.set(productId, (quantities.get(productId) ?? 0) + quantity);
  }

  const reservedProductIds: string[] = [];
  try {
    for (const [productId, quantity] of quantities) {
      const productRef = db.ref("shops/" + sourceId + "/products/" + productId);
      let failure = "";
      const transaction = await productRef.transaction((rawValue) => {
        if (!rawValue || typeof rawValue !== "object") {
          failure = "A shop product in this order no longer exists.";
          return;
        }
        const product = asRecord(rawValue);
        const reservations = asRecord(product.stockReservations);
        const existingReservation = Number(reservations[orderId] ?? 0);
        if (existingReservation > 0) {
          if (existingReservation !== quantity) failure = "This order already has a different stock reservation.";
          return product;
        }

        // A missing/negative stockQuantity means the merchant tracks this item as unlimited.
        if (product.stockQuantity === undefined || product.stockQuantity === null) return product;
        const stock = Number(product.stockQuantity);
        if (!Number.isFinite(stock) || stock < 0) return product;
        if (!Number.isSafeInteger(stock) || stock < quantity) {
          failure = asString(product.name) + " no longer has enough stock.";
          return;
        }
        return {
          ...product,
          stockQuantity: stock - quantity,
          stockReservations: { ...reservations, [orderId]: quantity },
        };
      });

      if (failure) throw new HttpsError("failed-precondition", failure);
      if (!transaction.committed) {
        throw new HttpsError("failed-precondition", "A product's stock changed before it could be reserved. Refresh the order and try again.");
      }
      const savedProduct = asRecord(transaction.snapshot.val());
      if (Number(asRecord(savedProduct.stockReservations)[orderId] ?? 0) === quantity) {
        reservedProductIds.push(productId);
      }
    }
  } catch (error) {
    await releaseShopOrderStock(orderId, order, reservedProductIds);
    throw error;
  }
}

async function releaseShopOrderStock(
  orderId: string,
  order: Record<string, unknown>,
  productIds?: string[]
): Promise<void> {
  if (asString(order.orderType).toUpperCase() !== "RETAIL") return;
  const sourceId = asString(order.sourceId);
  if (!sourceId) return;
  const quantities = new Map<string, number>();
  const rawItems = Array.isArray(order.items) ? order.items : [];
  for (const raw of rawItems) {
    const item = asRecord(raw);
    const productId = asString(item.id);
    const quantity = Number(item.quantity);
    if (productId && Number.isSafeInteger(quantity) && quantity > 0) quantities.set(productId, (quantities.get(productId) ?? 0) + quantity);
  }

  for (const [productId] of quantities) {
    if (productIds && !productIds.includes(productId)) continue;
    const productRef = db.ref("shops/" + sourceId + "/products/" + productId);
    await productRef.transaction((rawValue) => {
      if (!rawValue || typeof rawValue !== "object") return;
      const product = asRecord(rawValue);
      const reservations = asRecord(product.stockReservations);
      const reservedQuantity = Number(reservations[orderId] ?? 0);
      if (!Number.isSafeInteger(reservedQuantity) || reservedQuantity <= 0) return;
      const nextReservations = { ...reservations };
      delete nextReservations[orderId];
      const nextProduct: Record<string, unknown> = {
        ...product,
        stockQuantity: Number(product.stockQuantity) + reservedQuantity,
      };
      if (Object.keys(nextReservations).length > 0) nextProduct.stockReservations = nextReservations;
      else delete nextProduct.stockReservations;
      return nextProduct;
    });
  }
}

export const transitionOrderStatus = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  const data = asRecord(request.data);
  const orderId = asString(data.orderId);
  const next = asString(data.nextStatus).toUpperCase();
  if (!/^[A-Za-z0-9_-]{12,80}$/.test(orderId)) throw new HttpsError("invalid-argument", "A valid order ID is required.");
  const ref = db.ref("orders/" + orderId);
  const snap = await ref.get();
  if (!snap.exists()) throw new HttpsError("not-found", "The order was not found.");
  const order = asRecord(snap.val());
  const current = asString(order.status).toUpperCase();
  const method = asString(order.paymentMethod).toUpperCase();
  const paymentStatus = asString(order.paymentStatus).toUpperCase();
  let from: string[] = [];

  if (next === "CANCELLED") {
    if (asString(order.clientId) === uid) from = ["PLACED", "ACCEPTED"];
    else if (asString(order.sourceId) === uid || asString(order.merchantId) === uid) {
      await requireApprovedMerchant(uid, order);
      from = ["PLACED", "ACCEPTED", "PREPARING"];
    } else throw new HttpsError("permission-denied", "Only the customer or assigned merchant can cancel this order.");
    if (method === "GCASH" && paymentStatus === "PAID") throw new HttpsError("failed-precondition", "A paid GCash order must go through the administrator refund process before cancellation.");
  } else if (["ACCEPTED", "PREPARING", "OUT_FOR_DELIVERY"].includes(next)) {
    if (asString(order.sourceId) !== uid && asString(order.merchantId) !== uid) throw new HttpsError("permission-denied", "Only this order's merchant can update its preparation status.");
    await requireApprovedMerchant(uid, order);
    if (next === "ACCEPTED") from = ["PLACED", "ACCEPTING"];
    if (next === "PREPARING") from = ["ACCEPTED"];
    if (next === "OUT_FOR_DELIVERY") from = ["ACCEPTED", "PREPARING"];
    if (method === "GCASH") {
      const payment = asRecord((await db.ref("payments/" + orderId).get()).val());
      if (paymentStatus !== "PAID" || asString(payment.status).toUpperCase() !== "PAID") {
        throw new HttpsError("failed-precondition", "The customer must complete GCash payment before the merchant can accept or dispatch this order.");
      }
    }
  } else if (next === "DELIVERED") {
    if (asString(order.deliveryId) !== uid) throw new HttpsError("permission-denied", "Only the assigned courier can complete this delivery.");
    await requireApprovedCourier(uid);
    from = ["OUT_FOR_DELIVERY"];
    if (method === "CASH") throw new HttpsError("failed-precondition", "Cash delivery completion must use confirmCashCollected.");
    const payment = asRecord((await db.ref("payments/" + orderId).get()).val());
    if (paymentStatus !== "PAID" || asString(payment.status).toUpperCase() !== "PAID") throw new HttpsError("failed-precondition", "The server has not confirmed this order's payment.");
  } else throw new HttpsError("invalid-argument", "This order status transition is not supported.");

  if (!from.includes(current)) throw new HttpsError("failed-precondition", "This order cannot move from " + current + " to " + next + ".");
  if (next === "ACCEPTED") {
    // Claim the order before touching inventory so concurrent merchant actions cannot
    // reserve the same stock twice. ACCEPTING is recoverable if the function is retried.
    const claimToken = Date.now().toString(36) + "_" + uid;
    const claim = await ref.transaction((rawValue) => {
      if (!rawValue || typeof rawValue !== "object") return;
      const latest = asRecord(rawValue);
      const latestStatus = asString(latest.status).toUpperCase();
      const leaseStartedAt = Number(latest.stockReservationStartedAt ?? 0);
      const staleAcceptingLease = latestStatus === "ACCEPTING" &&
        leaseStartedAt > 0 && Date.now() - leaseStartedAt > 5 * 60 * 1000;
      if (latestStatus !== "PLACED" && !staleAcceptingLease) return;
      return {
        ...latest,
        status: "ACCEPTING",
        stockReservationStartedAt: Date.now(),
        stockReservationToken: claimToken,
      };
    });
    if (!claim.committed) throw new HttpsError("aborted", "This order is already being accepted. Refresh and try again.");
    try {
      await reserveShopOrderStock(orderId, order);
    } catch (error) {
      await ref.transaction((rawValue) => {
        if (!rawValue || typeof rawValue !== "object") return;
        const latest = asRecord(rawValue);
        if (asString(latest.stockReservationToken) !== claimToken) return;
        const restored: Record<string, unknown> = { ...latest, status: "PLACED" };
        delete restored.stockReservationStartedAt;
        delete restored.stockReservationToken;
        return restored;
      });
      throw error;
    }
    const accepted = await ref.transaction((rawValue) => {
      if (!rawValue || typeof rawValue !== "object") return;
      const latest = asRecord(rawValue);
      if (
        asString(latest.status).toUpperCase() !== "ACCEPTING" ||
        asString(latest.stockReservationToken) !== claimToken
      ) return;
      const saved: Record<string, unknown> = { ...latest, status: "ACCEPTED" };
      delete saved.stockReservationStartedAt;
      delete saved.stockReservationToken;
      return saved;
    });
    if (!accepted.committed) {
      throw new HttpsError("aborted", "The order changed while stock was being reserved. Refresh before retrying.");
    }
    return { orderId, previousStatus: current, status: "ACCEPTED" };
  }

  const tx = await ref.child("status").transaction((value) => from.includes(asString(value).toUpperCase()) ? next : undefined);
  if (!tx.committed) throw new HttpsError("aborted", "The order changed before this action could be saved. Refresh and retry.");
  if (next === "CANCELLED") await releaseShopOrderStock(orderId, order);
  return { orderId, previousStatus: current, status: next };
});

export const claimTrip = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  const profile = await requireApprovedDriver(uid);
  const driver = asRecord(profile.driverService);
  if (driver.isOnline !== true) throw new HttpsError("failed-precondition", "Go online before accepting a ride request.");
  const tripId = asString(asRecord(request.data).tripId);
  if (!/^[A-Za-z0-9_-]{12,80}$/.test(tripId)) throw new HttpsError("invalid-argument", "A valid trip ID is required.");
  const paymentSnapshot = await db.ref("payments/" + tripId).get();
  const serverPaymentStatus = asString(asRecord(paymentSnapshot.val()).status).toUpperCase();
  const vehicle = asRecord(driver.vehicleInfo);
  const driverVehicleType = asString(vehicle.type).toUpperCase();
  const vehicleName = [asString(vehicle.color), asString(vehicle.make), asString(vehicle.model)].filter(Boolean).join(" ");
  const plate = asString(vehicle.licensePlate);
  const ref = db.ref("trips/" + tripId);
  const tx = await ref.transaction((currentValue) => {
    if (!currentValue || typeof currentValue !== "object") return;
    const trip = asRecord(currentValue);
    const assigned = asString(trip.driverId);
    const requestedServiceType = asString(trip.serviceType).toUpperCase();
    if (
      asString(trip.status).toUpperCase() !== "REQUESTED" ||
      (assigned && assigned !== uid) ||
      !["CASH", "GCASH"].includes(asString(trip.paymentMethod).toUpperCase()) ||
      asString(trip.paymentMethod).toUpperCase() === "GCASH" &&
        (asString(trip.paymentStatus).toUpperCase() !== "PAID" || serverPaymentStatus !== "PAID") ||
      (requestedServiceType && requestedServiceType !== "RIDE" && requestedServiceType !== driverVehicleType)
    ) return;
    return { ...trip, driverId: uid, driverName: asString(profile.fullName) || "Driver", driverPhone: asString(profile.phoneNumber) || asString(profile.phone), vehicleDetails: [vehicleName, plate ? "(" + plate + ")" : ""].filter(Boolean).join(" "), status: "ACCEPTED" };
  });
  if (!tx.committed) throw new HttpsError("failed-precondition", "This ride request has already been accepted or is unavailable.");
  return { tripId, driverId: uid, status: "ACCEPTED" };
});

export const transitionTripStatus = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  const data = asRecord(request.data);
  const tripId = asString(data.tripId);
  const next = asString(data.nextStatus).toUpperCase();
  if (!/^[A-Za-z0-9_-]{12,80}$/.test(tripId)) throw new HttpsError("invalid-argument", "A valid trip ID is required.");
  const ref = db.ref("trips/" + tripId);
  const snap = await ref.get();
  if (!snap.exists()) throw new HttpsError("not-found", "The trip was not found.");
  const trip = asRecord(snap.val());
  const current = asString(trip.status).toUpperCase();
  let from: string[] = [];
  if (next === "CANCELLED") {
    if (asString(trip.passengerId) !== uid) throw new HttpsError("permission-denied", "Only the passenger can cancel this ride.");
    from = ["REQUESTED", "ACCEPTED"];
    if (asString(trip.paymentStatus).toUpperCase() === "PAID") throw new HttpsError("failed-precondition", "A paid GCash ride must go through the administrator refund process before cancellation.");
  } else if (next === "ARRIVED" || next === "IN_PROGRESS") {
    if (asString(trip.driverId) !== uid) throw new HttpsError("permission-denied", "Only the assigned driver can update this ride.");
    await requireApprovedDriver(uid);
    from = next === "ARRIVED" ? ["ACCEPTED"] : ["ACCEPTED", "ARRIVED"];
  } else if (next === "COMPLETED") {
    if (asString(trip.driverId) !== uid) throw new HttpsError("permission-denied", "Only the assigned driver can complete this ride.");
    await requireApprovedDriver(uid);
    from = ["IN_PROGRESS"];
    if (asString(trip.paymentMethod).toUpperCase() === "CASH") throw new HttpsError("failed-precondition", "Cash ride completion must use confirmCashCollected.");
    const payment = asRecord((await db.ref("payments/" + tripId).get()).val());
    if (asString(trip.paymentStatus).toUpperCase() !== "PAID" || asString(payment.status).toUpperCase() !== "PAID") throw new HttpsError("failed-precondition", "The server has not confirmed this ride's payment.");
  } else throw new HttpsError("invalid-argument", "This trip status transition is not supported.");
  if (!from.includes(current)) throw new HttpsError("failed-precondition", "This ride cannot move from " + current + " to " + next + ".");
  const tx = await ref.child("status").transaction((value) => from.includes(asString(value).toUpperCase()) ? next : undefined);
  if (!tx.committed) throw new HttpsError("aborted", "The ride changed before this action could be saved. Refresh and retry.");
  return { tripId, previousStatus: current, status: next };
});

function validCallbackToken(received: string, expected: string): boolean {
  if (!received || !expected) return false;
  const receivedHash = createHash("sha256").update(received).digest();
  const expectedHash = createHash("sha256").update(expected).digest();
  return timingSafeEqual(receivedHash, expectedHash);
}

// -----------------------------------------------------------------------------
// Xendit GCash lifecycle. Collection is confirmed only by the verified webhook
// or server-to-server reconciliation; the Android return URL is never proof.
// -----------------------------------------------------------------------------

type GcashEntityType = "ORDER" | "TRIP";
type GcashTerminalStatus = "PAID" | "FAILED" | "CANCELLED" | "EXPIRED";

function parseGcashEntityType(value: unknown): GcashEntityType {
  const type = asString(value).toUpperCase();
  if (type === "ORDER" || type === "TRIP") return type;
  throw new HttpsError("invalid-argument", "entityType must be ORDER or TRIP.");
}

function paymentEntityPath(entityType: GcashEntityType): "orders" | "trips" {
  return entityType === "ORDER" ? "orders" : "trips";
}

function paymentOwnerId(entityType: GcashEntityType, entity: Record<string, unknown>): string {
  return asString(entityType === "ORDER" ? entity.clientId : entity.passengerId);
}

function paymentAmountCentavos(entityType: GcashEntityType, entity: Record<string, unknown>): number {
  const rawCentavos = entityType === "ORDER" ? entity.totalPriceCentavos : entity.fareCentavos;
  const amount = rawCentavos !== undefined && rawCentavos !== null
    ? nonNegativeInteger(rawCentavos, "server-calculated payment amount")
    : centsFromPhp(
      asNumber(entityType === "ORDER" ? entity.totalPrice : entity.fare, "server-calculated payment amount"),
      "server-calculated payment amount"
    );
  if (!Number.isSafeInteger(amount) || amount <= 0) {
    throw new HttpsError("failed-precondition", "The server-calculated payment amount is invalid.");
  }
  // Xendit's published GCash limits are PHP 1.00 through PHP 100,000.00.
  if (amount < 100 || amount > 10_000_000) {
    throw new HttpsError("failed-precondition", "GCash payments must be between PHP 1.00 and PHP 100,000.00.");
  }
  return amount;
}

function toGcashPaymentResponse(
  entityType: GcashEntityType,
  entityId: string,
  payment: Record<string, unknown>
): Record<string, unknown> {
  return {
    entityId,
    [entityType === "ORDER" ? "orderId" : "tripId"]: entityId,
    paymentId: asString(payment.providerReference),
    providerPaymentId: asString(payment.providerPaymentId) || null,
    paymentStatus: asString(payment.status) || "PENDING",
    amount: Number(payment.amount ?? 0),
    amountCentavos: Number(payment.amountCentavos ?? 0),
    checkoutUrl: asString(payment.checkoutUrl) || null,
    webCheckoutUrl: asString(payment.webCheckoutUrl) || null,
    checkoutActionDescriptor: asString(payment.checkoutActionDescriptor) || null,
    expiresAtTimestamp: Number(payment.expiresAtTimestamp ?? 0),
  };
}

function mapProviderPaymentStatus(statusValue: unknown): GcashTerminalStatus | null {
  const status = asString(statusValue).toUpperCase();
  if (["SUCCEEDED", "COMPLETED", "PAID"].includes(status)) return "PAID";
  if (status === "FAILED") return "FAILED";
  if (status === "EXPIRED") return "EXPIRED";
  if (["CANCELED", "CANCELLED"].includes(status)) return "CANCELLED";
  return null;
}

function centsFromProviderAmount(value: unknown): number | null {
  if (value === undefined || value === null || value === "") return null;
  const amount = Number(value);
  if (!Number.isFinite(amount) || amount < 0) return null;
  const centavos = Math.round(amount * 100);
  return Number.isSafeInteger(centavos) ? centavos : null;
}

function xenditHeaders(secret: string, idempotencyKey?: string): Record<string, string> {
  const headers: Record<string, string> = {
    Authorization: "Basic " + Buffer.from(secret + ":").toString("base64"),
    "Content-Type": "application/json",
    "api-version": "2024-11-11",
  };
  if (idempotencyKey) headers["Idempotency-key"] = idempotencyKey;
  return headers;
}

async function applyXenditPaymentStatus(args: {
  entityType: GcashEntityType;
  entityId: string;
  paymentRequestId: string;
  referenceId: string;
  status: GcashTerminalStatus;
  providerPaymentId?: string;
  providerUpdatedAt?: string;
  failureCode?: string;
}): Promise<boolean> {
  const paymentRef = db.ref("payments/" + args.entityId);
  const transaction = await paymentRef.transaction((rawValue) => {
    const current = asRecord(rawValue);
    if (
      asString(current.provider) !== "XENDIT" ||
      asString(current.providerReference) !== args.paymentRequestId ||
      asString(current.referenceId) !== args.referenceId
    ) {
      return;
    }

    const incomingTime = Date.parse(args.providerUpdatedAt ?? "");
    const previousTime = Date.parse(asString(current.providerUpdatedAt));
    if (
      Number.isFinite(incomingTime) &&
      Number.isFinite(previousTime) &&
      incomingTime < previousTime
    ) {
      return;
    }

    const previousStatus = asString(current.status).toUpperCase();
    // A delayed failure/expiry callback must never reverse a confirmed capture.
    if (
      ["PAID", "PARTIALLY_REFUNDED", "REFUNDED"].includes(previousStatus) &&
      args.status !== "PAID"
    ) {
      return current;
    }

    const activeAttemptKey = asString(current.activeAttemptKey);
    const attempts = asRecord(current.attempts);
    const activeAttempt = asRecord(attempts[activeAttemptKey]);
    const nextAttempt = activeAttemptKey
      ? {
          ...attempts,
          [activeAttemptKey]: {
            ...activeAttempt,
            status: args.status,
            providerPaymentId: args.providerPaymentId || asString(activeAttempt.providerPaymentId),
            providerUpdatedAt: args.providerUpdatedAt || asString(activeAttempt.providerUpdatedAt),
            updatedAtTimestamp: Date.now(),
          },
        }
      : attempts;

    return {
      ...current,
      status: args.status,
      providerPaymentId: args.providerPaymentId || asString(current.providerPaymentId),
      providerUpdatedAt: args.providerUpdatedAt || asString(current.providerUpdatedAt),
      paidAtTimestamp: args.status === "PAID"
        ? Number(current.paidAtTimestamp ?? 0) || (Date.parse(args.providerUpdatedAt ?? "") || Date.now())
        : Number(current.paidAtTimestamp ?? 0),
      failureCode: args.status === "FAILED" ? args.failureCode || asString(current.failureCode) : "",
      attempts: nextAttempt,
      updatedAtTimestamp: Date.now(),
    };
  });

  if (!transaction.committed || !transaction.snapshot.exists()) return false;

  const saved = asRecord(transaction.snapshot.val());
  const entityPath = paymentEntityPath(args.entityType);
  const savedStatus = asString(saved.status).toUpperCase();
  const entityRef = db.ref(entityPath + "/" + args.entityId);
  await entityRef.transaction((rawValue) => {
    if (!rawValue || typeof rawValue !== "object") return;
    const entity = asRecord(rawValue);
    const currentStatus = asString(entity.status).toUpperCase();
    const nextEntity: Record<string, unknown> = {
      ...entity,
      paymentStatus: savedStatus,
      paymentId: asString(saved.providerReference),
    };

    if (savedStatus === "PAID") {
      // Do not rewind a ride/order that has already legitimately progressed.
      // Legacy REQUESTED/PLACED GCash records are normalized only when unassigned.
      const unassigned = args.entityType === "TRIP"
        ? !asString(entity.driverId)
        : !asString(entity.deliveryId);
      if (
        currentStatus === "AWAITING_PAYMENT" ||
        unassigned && (currentStatus === "REQUESTED" || currentStatus === "PLACED")
      ) {
        nextEntity.status = args.entityType === "ORDER" ? "PLACED" : "REQUESTED";
      }
    } else if (["FAILED", "CANCELLED", "EXPIRED"].includes(savedStatus)) {
      if ([
        "AWAITING_PAYMENT", "REQUESTED", "PLACED", "ACCEPTED", "PREPARING", "OUT_FOR_DELIVERY",
      ].includes(currentStatus)) {
        nextEntity.status = "CANCELLED";
        if (args.entityType === "TRIP") {
          nextEntity.driverId = "";
          nextEntity.driverName = "";
          nextEntity.driverPhone = "";
        } else {
          nextEntity.deliveryId = "";
          nextEntity.deliveryName = "";
          nextEntity.deliveryPhone = "";
        }
      }
    }
    return nextEntity;
  });

  if (["PAID", "FAILED", "CANCELLED", "EXPIRED"].includes(savedStatus)) {
    await db.ref("paymentReconciliationQueue/" + args.entityId).remove();
  }
  return true;
}

async function applyXenditRefundStatus(args: {
  entityId: string;
  refundAttemptId: string;
  referenceId: string;
  providerRefundId: string;
  providerPaymentId: string;
  status: "SUCCEEDED" | "FAILED" | "PENDING";
  amountCentavos: number;
  providerUpdatedAt?: string;
  failureCode?: string;
}): Promise<boolean> {
  const paymentRef = db.ref("payments/" + args.entityId);
  const transaction = await paymentRef.transaction((rawValue) => {
    const current = asRecord(rawValue);
    const refunds = asRecord(current.refunds);
    const refund = asRecord(refunds[args.refundAttemptId]);
    if (
      asString(current.provider) !== "XENDIT" ||
      asString(current.providerPaymentId) !== args.providerPaymentId ||
      asString(refund.referenceId) !== args.referenceId ||
      Number(refund.amountCentavos) !== args.amountCentavos ||
      (asString(refund.providerRefundId) &&
        asString(refund.providerRefundId) !== args.providerRefundId)
    ) {
      return;
    }

    const incomingTime = Date.parse(args.providerUpdatedAt ?? "");
    const previousTime = Date.parse(asString(refund.providerUpdatedAt));
    if (
      Number.isFinite(incomingTime) &&
      Number.isFinite(previousTime) &&
      incomingTime < previousTime
    ) {
      return;
    }
    const previousStatus = asString(refund.status).toUpperCase();
    if (["SUCCEEDED", "FAILED"].includes(previousStatus)) {
      if (previousStatus !== args.status || refund.balanceApplied === true) return current;
    }

    const totalCentavos = nonNegativeInteger(current.amountCentavos, "stored payment amount");
    const refundedCentavos = nonNegativeInteger(current.refundedCentavos ?? 0, "refunded amount");
    const pendingCentavos = nonNegativeInteger(current.pendingRefundCentavos ?? 0, "pending refund amount");
    const nextPending = Math.max(0, pendingCentavos - args.amountCentavos);
    const nextRefunded = args.status === "SUCCEEDED"
      ? refundedCentavos + args.amountCentavos
      : refundedCentavos;
    const refundRecord = {
      ...refund,
      providerRefundId: args.providerRefundId,
      status: args.status,
      providerUpdatedAt: args.providerUpdatedAt || asString(refund.providerUpdatedAt),
      failureCode: args.status === "FAILED" ? args.failureCode || "" : "",
      balanceApplied: args.status === "SUCCEEDED" || args.status === "FAILED",
      updatedAtTimestamp: Date.now(),
    };
    const nextPaymentStatus = args.status === "SUCCEEDED"
      ? nextRefunded >= totalCentavos ? "REFUNDED" : "PARTIALLY_REFUNDED"
      : asString(current.status);

    return {
      ...current,
      refunds: { ...refunds, [args.refundAttemptId]: refundRecord },
      pendingRefundCentavos: nextPending,
      refundedCentavos: nextRefunded,
      refundStatus: nextPending > 0 ? "PENDING" : args.status,
      status: nextPaymentStatus,
      updatedAtTimestamp: Date.now(),
    };
  });

  if (!transaction.committed || !transaction.snapshot.exists()) {
    const latest = asRecord((await paymentRef.get()).val());
    const latestRefund = asRecord(asRecord(latest.refunds)[args.refundAttemptId]);
    if (args.status !== "SUCCEEDED" || asString(latestRefund.status).toUpperCase() !== "SUCCEEDED") return false;
    await reconcileRefundedPaymentEarnings(args.entityId, args.refundAttemptId);
    return true;
  }
  const saved = asRecord(transaction.snapshot.val());
  const refundQueueKey = args.entityId + "_" + args.refundAttemptId;
  await db.ref("refundReconciliationQueue/" + refundQueueKey).remove();
  if (["REFUNDED", "PARTIALLY_REFUNDED"].includes(asString(saved.status))) {
    const entityType = asString(saved.entityType).toUpperCase() === "TRIP" ? "TRIP" : "ORDER";
    await db.ref(paymentEntityPath(entityType as GcashEntityType) + "/" + args.entityId + "/paymentStatus")
      .set(asString(saved.status));
  }
  if (args.status === "SUCCEEDED") {
    await reconcileRefundedPaymentEarnings(args.entityId, args.refundAttemptId);
  }
  return true;
}

export const createGcashPayment = onCall(
  { region: REGION, secrets: [XENDIT_SECRET_KEY] },
  async (request) => {
    const uid = requireUid(request);
    const data = asRecord(request.data);
    const entityType = parseGcashEntityType(data.entityType);
    const entityId = asString(data.entityId);
    if (!/^[A-Za-z0-9_-]{12,80}$/.test(entityId)) {
      throw new HttpsError("invalid-argument", "A valid order or trip ID is required.");
    }
    const clientIdempotencyKey = parseIdempotencyKey(data.idempotencyKey);
    const attemptKey = createHash("sha256")
      .update(uid + "|" + entityType + "|" + entityId + "|" + clientIdempotencyKey)
      .digest("hex")
      .slice(0, 32);

    const entityPath = paymentEntityPath(entityType);
    const entitySnapshot = await db.ref(entityPath + "/" + entityId).get();
    if (!entitySnapshot.exists()) {
      throw new HttpsError("not-found", "The order or trip was not found.");
    }
    const entity = asRecord(entitySnapshot.val());
    if (paymentOwnerId(entityType, entity) !== uid) {
      throw new HttpsError("permission-denied", "This payment does not belong to the signed-in customer.");
    }
    if (asString(entity.paymentMethod).toUpperCase() !== "GCASH") {
      throw new HttpsError("failed-precondition", "This order or trip was not created for GCash checkout.");
    }
    const amountCentavos = paymentAmountCentavos(entityType, entity);
    const amount = phpFromCents(amountCentavos);
    const paymentRef = db.ref("payments/" + entityId);
    const existingSnapshot = await paymentRef.get();
    const existingPayment = asRecord(existingSnapshot.val());

    if (asString(entity.paymentStatus).toUpperCase() === "PAID" ||
      asString(existingPayment.status).toUpperCase() === "PAID") {
      return Object.keys(existingPayment).length > 0
        ? toGcashPaymentResponse(entityType, entityId, existingPayment)
        : {
            entityId,
            [entityType === "ORDER" ? "orderId" : "tripId"]: entityId,
            paymentId: asString(entity.paymentId),
            paymentStatus: "PAID",
            amount,
            amountCentavos,
            checkoutUrl: null,
            webCheckoutUrl: null,
            checkoutActionDescriptor: null,
            expiresAtTimestamp: 0,
          };
    }
    if (
      asString(existingPayment.status).toUpperCase() === "PENDING" &&
      asString(existingPayment.checkoutUrl) &&
      Number(existingPayment.expiresAtTimestamp) > Date.now()
    ) {
      return toGcashPaymentResponse(entityType, entityId, existingPayment);
    }
    if (
      ["CREATING", "INITIATION_FAILED"].includes(asString(existingPayment.status).toUpperCase()) &&
      asString(existingPayment.activeAttemptKey) !== attemptKey
    ) {
      throw new HttpsError("failed-precondition", "A GCash payment request is already being created. Retry with the same checkout request key.");
    }

    const secret = XENDIT_SECRET_KEY.value();
    if (!secret) {
      throw new HttpsError("failed-precondition", "The Xendit secret key is not configured.");
    }

    const referenceId = "labmda_" + entityType.toLowerCase() + "_" + entityId + "_" + attemptKey;
    const providerIdempotencyKey = "labmda_gcash_" + entityType.toLowerCase() + "_" + entityId + "_" + attemptKey;
    const now = Date.now();
    const reservation = await paymentRef.transaction((rawValue) => {
      const current = asRecord(rawValue);
      const status = asString(current.status).toUpperCase();
      if (status === "PAID" || status === "PENDING" && Number(current.expiresAtTimestamp) > now) {
        return;
      }
      if (
        ["CREATING", "INITIATION_FAILED"].includes(status) &&
        asString(current.activeAttemptKey) !== attemptKey
      ) {
        return;
      }
      const attempts = asRecord(current.attempts);
      const currentAttempt = asRecord(attempts[attemptKey]);
      return {
        ...current,
        entityType,
        entityId,
        clientId: uid,
        provider: "XENDIT",
        channelCode: "GCASH",
        referenceId,
        status: "CREATING",
        activeAttemptKey: attemptKey,
        amount,
        amountCentavos,
        currency: "PHP",
        breakdown: entity.breakdown ?? current.breakdown ?? null,
        pricingVersion: Number(entity.pricingVersion ?? current.pricingVersion ?? 0),
        createdAtTimestamp: Number(current.createdAtTimestamp ?? now),
        updatedAtTimestamp: now,
        attempts: {
          ...attempts,
          [attemptKey]: {
            ...currentAttempt,
            attemptKey,
            status: "CREATING",
            referenceId,
            idempotencyKey: providerIdempotencyKey,
            amount,
            amountCentavos,
            createdAtTimestamp: Number(currentAttempt.createdAtTimestamp ?? now),
            updatedAtTimestamp: now,
          },
        },
      };
    });

    if (!reservation.committed) {
      const latestSnapshot = await paymentRef.get();
      const latest = asRecord(latestSnapshot.val());
      if (asString(latest.status).toUpperCase() === "PAID") {
        return toGcashPaymentResponse(entityType, entityId, latest);
      }
      if (
        asString(latest.status).toUpperCase() === "PENDING" &&
        asString(latest.checkoutUrl)
      ) {
        return toGcashPaymentResponse(entityType, entityId, latest);
      }
      throw new HttpsError("failed-precondition", "This payment already has an active GCash checkout. Reuse its original idempotency key.");
    }

    // Close the visibility/claim window before making the external Xendit request.
    // If the provider call fails, the catch block below cancels the unpaid entity.
    const entityPendingUpdates: Record<string, unknown> = {};
    entityPendingUpdates[entityPath + "/" + entityId + "/paymentMethod"] = "GCASH";
    entityPendingUpdates[entityPath + "/" + entityId + "/paymentStatus"] = "CREATING";
    entityPendingUpdates[entityPath + "/" + entityId + "/status"] = "AWAITING_PAYMENT";
    await db.ref().update(entityPendingUpdates);

    try {
      const provider = await createXenditGcashRequest({
        referenceId,
        amount,
        description: (entityType === "ORDER" ? "Labmda order " : "Labmda ride ") + entityId,
        entityType,
        entityId,
        clientId: uid,
        idempotencyKey: providerIdempotencyKey,
      });
      const timestamp = Date.now();
      const status = mapProviderPaymentStatus(provider.status) ?? "PENDING";
      const expiresAtTimestamp = timestamp + 15 * 60 * 1000;
      const merchantId = entityType === "ORDER" ? asString(entity.merchantId) : "";
      const payment = {
        entityType,
        entityId,
        clientId: uid,
        merchantId,
        provider: "XENDIT",
        channelCode: "GCASH",
        providerReference: provider.paymentId,
        providerPaymentId: "",
        referenceId,
        status,
        amount,
        amountCentavos,
        currency: "PHP",
        breakdown: entity.breakdown ?? null,
        pricingVersion: Number(entity.pricingVersion ?? 0),
        checkoutUrl: provider.checkoutUrl,
        webCheckoutUrl: provider.webCheckoutUrl,
        checkoutActionDescriptor: provider.checkoutActionDescriptor,
        expiresAtTimestamp,
        activeAttemptKey: attemptKey,
        createdAtTimestamp: Number(asRecord(reservation.snapshot.val()).createdAtTimestamp ?? timestamp),
        updatedAtTimestamp: timestamp,
        attempts: {
          ...asRecord(asRecord(reservation.snapshot.val()).attempts),
          [attemptKey]: {
            attemptKey,
            referenceId,
            providerReference: provider.paymentId,
            providerPaymentId: "",
            status,
            amount,
            amountCentavos,
            checkoutUrl: provider.checkoutUrl,
            webCheckoutUrl: provider.webCheckoutUrl,
            checkoutActionDescriptor: provider.checkoutActionDescriptor,
            expiresAtTimestamp,
            createdAtTimestamp: timestamp,
            updatedAtTimestamp: timestamp,
          },
        },
      };

      const updates: Record<string, unknown> = {};
      updates["payments/" + entityId] = payment;
      updates[entityPath + "/" + entityId + "/paymentMethod"] = "GCASH";
      updates[entityPath + "/" + entityId + "/paymentStatus"] = status;
      updates[entityPath + "/" + entityId + "/paymentId"] = provider.paymentId;
      updates[entityPath + "/" + entityId + "/status"] =
        status === "PAID"
          ? entityType === "ORDER" ? "PLACED" : "REQUESTED"
          : ["FAILED", "CANCELLED", "EXPIRED"].includes(status)
            ? "CANCELLED"
            : "AWAITING_PAYMENT";
      if (["PENDING", "CREATING"].includes(status)) {
        updates["paymentReconciliationQueue/" + entityId] = {
          dueAtTimestamp: timestamp + 2 * 60 * 1000,
          createdAtTimestamp: timestamp,
          attempts: 0,
        };
      }
      await db.ref().update(updates);
      logger.info("Created Xendit GCash payment request", {
        entityType,
        entityId,
        paymentRequestId: provider.paymentId,
        amountCentavos,
      });
      return toGcashPaymentResponse(entityType, entityId, payment);
    } catch (error) {
      const failureTimestamp = Date.now();
      const failure = {
        status: "INITIATION_FAILED",
        failureStage: "PAYMENT_REQUEST_CREATION",
        updatedAtTimestamp: failureTimestamp,
      };
      const updates: Record<string, unknown> = {};
      updates["payments/" + entityId + "/status"] = failure.status;
      updates["payments/" + entityId + "/failureStage"] = failure.failureStage;
      updates["payments/" + entityId + "/updatedAtTimestamp"] = failureTimestamp;
      updates["payments/" + entityId + "/attempts/" + attemptKey + "/status"] = failure.status;
      updates["payments/" + entityId + "/attempts/" + attemptKey + "/updatedAtTimestamp"] = failureTimestamp;
      updates[entityPath + "/" + entityId + "/paymentStatus"] = failure.status;
      updates[entityPath + "/" + entityId + "/status"] = "CANCELLED";
      await db.ref().update(updates);
      logger.error("Could not create Xendit GCash payment request", { entityType, entityId, error });
      if (error instanceof HttpsError) throw error;
      throw new HttpsError("unavailable", "GCash checkout could not be started. Retry the same checkout request.");
    }
  }
);

export const refundPayment = onCall(
  { region: REGION, secrets: [XENDIT_SECRET_KEY] },
  async (request) => {
    const adminUid = requireAdmin(request);
    const data = asRecord(request.data);
    const entityType = parseGcashEntityType(data.entityType);
    const entityId = asString(data.entityId);
    if (!/^[A-Za-z0-9_-]{12,80}$/.test(entityId)) {
      throw new HttpsError("invalid-argument", "A valid order or trip ID is required.");
    }
    const idempotencyKey = parseIdempotencyKey(data.idempotencyKey);
    const refundAttemptId = createHash("sha256")
      .update(adminUid + "|" + entityType + "|" + entityId + "|" + idempotencyKey)
      .digest("hex")
      .slice(0, 32);
    const reasonValue = asString(data.reason).toUpperCase() || "REQUESTED_BY_CUSTOMER";
    const validReasons = ["FRAUDULENT", "DUPLICATE", "REQUESTED_BY_CUSTOMER", "CANCELLATION", "OTHERS"];
    if (!validReasons.includes(reasonValue)) {
      throw new HttpsError("invalid-argument", "Unsupported refund reason.");
    }
    const requestedAmount = data.amountCentavos === undefined || data.amountCentavos === null
      ? null
      : nonNegativeInteger(data.amountCentavos, "refund amount in centavos");

    const paymentRef = db.ref("payments/" + entityId);
    const existingSnapshot = await paymentRef.get();
    if (!existingSnapshot.exists()) {
      throw new HttpsError("not-found", "The payment record was not found.");
    }
    const existingPayment = asRecord(existingSnapshot.val());
    const existingRefund = asRecord(asRecord(existingPayment.refunds)[refundAttemptId]);
    if (
      existingRefund.providerRefundId &&
      ["PENDING", "SUCCEEDED", "FAILED"].includes(asString(existingRefund.status))
    ) {
      return {
        entityId,
        refundId: asString(existingRefund.providerRefundId),
        refundStatus: asString(existingRefund.status),
        amountCentavos: Number(existingRefund.amountCentavos ?? 0),
      };
    }
    if (
      asString(existingPayment.provider) !== "XENDIT" ||
      !asString(existingPayment.providerPaymentId)
    ) {
      throw new HttpsError("failed-precondition", "Refunds require a provider-confirmed Xendit payment ID.");
    }
    const paidAtTimestamp = Number(existingPayment.paidAtTimestamp ?? 0);
    const gcashRefundWindowMs = 180 * 24 * 60 * 60 * 1000;
    if (paidAtTimestamp > 0 && Date.now() - paidAtTimestamp > gcashRefundWindowMs) {
      throw new HttpsError("failed-precondition", "The Xendit GCash refund window of 180 days has elapsed.");
    }

    const secret = XENDIT_SECRET_KEY.value();
    if (!secret) {
      throw new HttpsError("failed-precondition", "The Xendit secret key is not configured.");
    }

    let reservedAmountCentavos = 0;
    let reservationError = "";
    const referenceId = "refund_" + entityId + "_" + refundAttemptId;
    const reservation = await paymentRef.transaction((rawValue) => {
      const current = asRecord(rawValue);
      if (asString(current.provider) !== "XENDIT" || !asString(current.providerPaymentId)) {
        reservationError = "Refunds require a provider-confirmed Xendit payment ID.";
        return;
      }
      if (!["PAID", "PARTIALLY_REFUNDED"].includes(asString(current.status).toUpperCase())) {
        reservationError = "Only a paid, not-fully-refunded payment can be refunded.";
        return;
      }

      const refunds = asRecord(current.refunds);
      const saved = asRecord(refunds[refundAttemptId]);
      if (Object.keys(saved).length > 0) {
        reservedAmountCentavos = Number(saved.amountCentavos ?? 0);
        return current;
      }

      const total = nonNegativeInteger(current.amountCentavos, "payment amount");
      const refunded = nonNegativeInteger(current.refundedCentavos ?? 0, "already-refunded amount");
      const pending = nonNegativeInteger(current.pendingRefundCentavos ?? 0, "pending refund amount");
      const available = total - refunded - pending;
      reservedAmountCentavos = requestedAmount ?? available;
      if (reservedAmountCentavos <= 0 || reservedAmountCentavos > available) {
        reservationError = "The requested refund is greater than the remaining refundable balance.";
        return;
      }

      const timestamp = Date.now();
      return {
        ...current,
        pendingRefundCentavos: pending + reservedAmountCentavos,
        refundStatus: "PENDING",
        refunds: {
          ...refunds,
          [refundAttemptId]: {
            refundAttemptId,
            referenceId,
            providerPaymentId: asString(current.providerPaymentId),
            status: "CREATING",
            amountCentavos: reservedAmountCentavos,
            amount: phpFromCents(reservedAmountCentavos),
            currency: "PHP",
            reason: reasonValue,
            createdBy: adminUid,
            createdAtTimestamp: timestamp,
            updatedAtTimestamp: timestamp,
          },
        },
        updatedAtTimestamp: timestamp,
      };
    });

    if (!reservation.committed || reservationError) {
      throw new HttpsError("failed-precondition", reservationError || "The refund could not be reserved.");
    }

    const savedAfterReservation = asRecord(reservation.snapshot.val());
    const savedRefund = asRecord(asRecord(savedAfterReservation.refunds)[refundAttemptId]);
    if (asString(savedRefund.providerRefundId)) {
      return {
        entityId,
        refundId: asString(savedRefund.providerRefundId),
        refundStatus: asString(savedRefund.status),
        amountCentavos: Number(savedRefund.amountCentavos ?? 0),
      };
    }

    const providerPaymentId = asString(savedAfterReservation.providerPaymentId);
    const providerIdempotencyKey = "labmda_refund_" + entityId + "_" + refundAttemptId;
    const refundAmount = phpFromCents(reservedAmountCentavos);

    try {
      const response = await fetch("https://api.xendit.co/v3/refunds", {
        method: "POST",
        headers: xenditHeaders(secret, providerIdempotencyKey),
        body: JSON.stringify({
          reference_id: referenceId,
          payment_id: providerPaymentId,
          amount: refundAmount,
          reason: reasonValue,
          metadata: { entity_type: entityType, entity_id: entityId },
        }),
      });
      const providerBody = asRecord(await response.json().catch(() => ({})));
      if (!response.ok) {
        logger.error("Xendit refund request failed", { entityId, status: response.status, response: providerBody });
        // An explicit provider rejection is terminal. Release the reservation so the
        // administrator's remaining refundable balance is not stranded.
        await applyXenditRefundStatus({
          entityId,
          refundAttemptId,
          referenceId,
          providerRefundId: "",
          providerPaymentId,
          status: "FAILED",
          amountCentavos: reservedAmountCentavos,
          providerUpdatedAt: new Date().toISOString(),
          failureCode: asString(providerBody.error_code) || "REFUND_REQUEST_REJECTED",
        });
        throw new HttpsError("unavailable", "Xendit rejected the refund. Review the reason and retry with a new refund request key.");
      }

      const providerRefundId = asString(providerBody.refund_id) || asString(providerBody.id);
      const providerStatus = asString(providerBody.status).toUpperCase();
      if (!providerRefundId) {
        throw new HttpsError("unavailable", "Xendit did not return a refund ID.");
      }
      const refundStatus: "SUCCEEDED" | "FAILED" | "PENDING" =
        providerStatus === "SUCCEEDED" ? "SUCCEEDED" :
          providerStatus === "FAILED" ? "FAILED" : "PENDING";
      // Store the provider ID first, but do not mark terminal status before the
      // transactional balance update below. The webhook can race this response.
      await paymentRef.child("refunds").child(refundAttemptId).update({
        providerRefundId,
        updatedAtTimestamp: Date.now(),
      });

      if (refundStatus === "PENDING") {
        await paymentRef.child("refunds").child(refundAttemptId).transaction((rawValue) => {
          const current = asRecord(rawValue);
          if (["SUCCEEDED", "FAILED"].includes(asString(current.status).toUpperCase())) {
            return current;
          }
          return {
            ...current,
            status: "PENDING",
            updatedAtTimestamp: Date.now(),
          };
        });
        const latestRefund = asRecord((await paymentRef.child("refunds").child(refundAttemptId).get()).val());
        if (!["SUCCEEDED", "FAILED"].includes(asString(latestRefund.status).toUpperCase())) {
          await db.ref("refundReconciliationQueue/" + entityId + "_" + refundAttemptId).set({
            entityId,
            refundAttemptId,
            providerRefundId,
            referenceId,
            dueAtTimestamp: Date.now() + 2 * 60 * 1000,
            attempts: 0,
          });
        }
      } else {
        await applyXenditRefundStatus({
          entityId,
          refundAttemptId,
          referenceId,
          providerRefundId,
          providerPaymentId,
          status: refundStatus,
          amountCentavos: reservedAmountCentavos,
          providerUpdatedAt: asString(providerBody.updated),
          failureCode: asString(providerBody.failure_code),
        });
      }

      return {
        entityId,
        refundId: providerRefundId,
        refundStatus,
        amountCentavos: reservedAmountCentavos,
      };
    } catch (error) {
      logger.error("Could not create Xendit refund", { entityId, refundAttemptId, error });
      if (error instanceof HttpsError) throw error;
      throw new HttpsError("unavailable", "The refund request could not be completed. Retry using the same refund request key.");
    }
  }
);


type WebhookReservation = { key: string; status: "ACQUIRED" | "DUPLICATE" | "BUSY" };
async function reserveWebhookEvent(provider: string, event: string, body: Record<string, unknown>): Promise<WebhookReservation> {
  const data = asRecord(body.data);
  const eventId = asString(body.id) || asString(body.event_id) || asString(body.webhook_id) || asString(data.id) || asString(data.event_id);
  const key = createHash("sha256").update(provider + "|" + event + "|" + (eventId || JSON.stringify(body))).digest("hex");
  const ref = db.ref("processedWebhookEvents/" + key);
  const now = Date.now();
  const tx = await ref.transaction((currentValue) => {
    const current = asRecord(currentValue);
    if (asString(current.status) === "PROCESSED") return;
    if (asString(current.status) === "PROCESSING" && Number(current.leaseUntilTimestamp ?? 0) > now) return;
    return { provider, event, status: "PROCESSING", attempts: Number(current.attempts ?? 0) + 1, receivedAtTimestamp: Number(current.receivedAtTimestamp ?? now) || now, updatedAtTimestamp: now, leaseUntilTimestamp: now + 120000 };
  });
  if (tx.committed) return { key, status: "ACQUIRED" };
  const saved = asRecord(tx.snapshot.val());
  return { key, status: asString(saved.status) === "PROCESSED" ? "DUPLICATE" : "BUSY" };
}
async function markWebhookEventProcessed(key: string): Promise<void> {
  const now = Date.now();
  await db.ref("processedWebhookEvents/" + key).update({ status: "PROCESSED", processedAtTimestamp: now, updatedAtTimestamp: now, leaseUntilTimestamp: 0 });
}



export const xenditPaymentWebhook = onRequest(
  { region: REGION, secrets: [XENDIT_WEBHOOK_TOKEN] },
  async (request, response) => {
    if (request.method !== "POST") {
      response.status(405).send("Method not allowed");
      return;
    }
    if (!validCallbackToken(asString(request.get("x-callback-token")), XENDIT_WEBHOOK_TOKEN.value())) {
      response.status(401).send("Invalid callback token");
      return;
    }

    const body = asRecord(request.body);
    const data = asRecord(body.data);
    const event = asString(body.event).toLowerCase();
    const reservation = await reserveWebhookEvent("XENDIT_PAYMENT", event, body);
    if (reservation.status === "DUPLICATE") { response.status(200).send("Duplicate event"); return; }
    if (reservation.status === "BUSY") { response.status(503).send("Webhook event is being processed"); return; }
    const webhookEventKey = reservation.key;
    const referenceId = asString(data.reference_id);

    if (event === "refund.succeeded" || event === "refund.failed") {
      const match = /^refund_(.+)_([a-f0-9]{32})$/i.exec(referenceId);
      if (!match) {
        await markWebhookEventProcessed(webhookEventKey);
      response.status(200).send("Ignored unrelated refund event");
        return;
      }
      const entityId = match[1];
      const refundAttemptId = match[2];
      const paymentSnapshot = await db.ref("payments/" + entityId).get();
      if (!paymentSnapshot.exists()) {
        response.status(503).send("Refund payment record not ready");
        return;
      }
      const payment = asRecord(paymentSnapshot.val());
      const refund = asRecord(asRecord(payment.refunds)[refundAttemptId]);
      const providerAmount = centsFromProviderAmount(data.amount);
      if (
        asString(data.currency).toUpperCase() !== "PHP" ||
        providerAmount === null ||
        providerAmount !== Number(refund.amountCentavos) ||
        asString(data.payment_id) !== asString(payment.providerPaymentId) ||
        (asString(refund.providerRefundId) &&
          asString(data.refund_id) !== asString(refund.providerRefundId))
      ) {
        logger.error("Xendit refund webhook did not match the stored refund", { referenceId });
        response.status(400).send("Refund details mismatch");
        return;
      }
      const applied = await applyXenditRefundStatus({
        entityId,
        refundAttemptId,
        referenceId,
        providerRefundId: asString(data.refund_id),
        providerPaymentId: asString(data.payment_id),
        status: event === "refund.succeeded" ? "SUCCEEDED" : "FAILED",
        amountCentavos: providerAmount,
        providerUpdatedAt: asString(data.updated) || asString(body.created),
        failureCode: asString(data.failure_code),
      });
      if (!applied) {
        response.status(503).send("Refund update could not be applied");
        return;
      }
      await markWebhookEventProcessed(webhookEventKey);
      response.status(200).send("OK");
      return;
    }

    const metadata = asRecord(data.metadata);
    let entityTypeValue = asString(metadata.entity_type).toUpperCase();
    let entityId = asString(metadata.entity_id);
    if (!["ORDER", "TRIP"].includes(entityTypeValue)) {
      const match = /^labmda_(order|trip)_(.+)_([a-f0-9]{32})$/i.exec(referenceId);
      if (match) {
        entityTypeValue = match[1].toUpperCase();
        entityId = match[2];
      }
    }
    if (!["ORDER", "TRIP"].includes(entityTypeValue) || !/^[A-Za-z0-9_-]{12,80}$/.test(entityId)) {
      await markWebhookEventProcessed(webhookEventKey);
      response.status(200).send("Ignored unrelated payment event");
      return;
    }
    const entityType = entityTypeValue as GcashEntityType;
    const paymentRequestId = asString(data.payment_request_id);
    const paymentSnapshot = await db.ref("payments/" + entityId).get();
    if (!paymentSnapshot.exists()) {
      logger.warn("Xendit payment webhook arrived before the payment record existed", { referenceId, event });
      response.status(503).send("Payment record not ready");
      return;
    }
    const payment = asRecord(paymentSnapshot.val());
    if (
      asString(payment.provider) !== "XENDIT" ||
      asString(payment.referenceId) !== referenceId ||
      asString(payment.channelCode).toUpperCase() !== "GCASH"
    ) {
      response.status(400).send("Payment reference mismatch");
      return;
    }
    // Xendit may emit the event before the callable has saved the response ID.
    // Return a retryable response while the reserved attempt is finalized.
    if (!asString(payment.providerReference)) {
      response.status(503).send("Payment request is still being finalized");
      return;
    }
    if (asString(payment.providerReference) !== paymentRequestId) {
      response.status(400).send("Payment request ID mismatch");
      return;
    }

    const amount = centsFromProviderAmount(data.request_amount);
    const currency = asString(data.currency).toUpperCase();
    const country = asString(data.country).toUpperCase();
    const channelCode = asString(data.channel_code).toUpperCase();
    if (
      (event === "payment.capture" &&
        (amount === null || currency !== "PHP" || country !== "PH" || channelCode !== "GCASH")) ||
      (amount !== null && amount !== Number(payment.amountCentavos)) ||
      (currency && currency !== "PHP") ||
      (country && country !== "PH") ||
      (channelCode && channelCode !== "GCASH")
    ) {
      logger.error("Xendit payment webhook amount or channel did not match the stored payment", { referenceId });
      response.status(400).send("Payment details mismatch");
      return;
    }

    let nextStatus: GcashTerminalStatus | null = null;
    if (event === "payment.capture") nextStatus = "PAID";
    else if (event === "payment.failure") nextStatus = mapProviderPaymentStatus(data.status) ?? "FAILED";
    else if (event === "payment_request.expiry") nextStatus = "EXPIRED";
    else nextStatus = mapProviderPaymentStatus(data.status);
    if (!nextStatus) {
      await markWebhookEventProcessed(webhookEventKey);
      response.status(200).send("Non-terminal payment event acknowledged");
      return;
    }

    const applied = await applyXenditPaymentStatus({
      entityType,
      entityId,
      paymentRequestId,
      referenceId,
      status: nextStatus,
      providerPaymentId: asString(data.payment_id),
      providerUpdatedAt: asString(data.updated) || asString(body.created),
      failureCode: asString(data.failure_code),
    });
    if (!applied) {
      response.status(503).send("Payment update could not be applied");
      return;
    }
    await markWebhookEventProcessed(webhookEventKey);
    response.status(200).send("OK");
  }
);

export const reconcilePendingPayments = onSchedule(
  {
    schedule: "every 5 minutes",
    timeZone: "Asia/Manila",
    region: REGION,
    secrets: [XENDIT_SECRET_KEY],
  },
  async () => {
    const secret = XENDIT_SECRET_KEY.value();
    if (!secret) {
      logger.error("Payment reconciliation skipped because Xendit secret is not configured");
      return;
    }
    const now = Date.now();

    const pendingPayments = await db.ref("paymentReconciliationQueue")
      .orderByChild("dueAtTimestamp")
      .endAt(now)
      .limitToFirst(100)
      .get();
    const paymentEntries: Array<{ key: string; value: Record<string, unknown> }> = [];
    pendingPayments.forEach((snapshot) => {
      if (snapshot.key) paymentEntries.push({ key: snapshot.key, value: asRecord(snapshot.val()) });
      return false;
    });

    for (const child of paymentEntries) {
      const entityId = child.key;
      const queueRef = db.ref("paymentReconciliationQueue/" + entityId);
      const paymentSnapshot = await db.ref("payments/" + entityId).get();
      if (!paymentSnapshot.exists()) {
        await queueRef.remove();
        continue;
      }
      const payment = asRecord(paymentSnapshot.val());
      if (
        asString(payment.provider) !== "XENDIT" ||
        asString(payment.channelCode).toUpperCase() !== "GCASH"
      ) {
        await queueRef.remove();
        continue;
      }
      const storedPaymentStatus = mapProviderPaymentStatus(payment.status);
      if (storedPaymentStatus) {
        const entityType = asString(payment.entityType).toUpperCase() === "TRIP" ? "TRIP" : "ORDER";
        await applyXenditPaymentStatus({
          entityType,
          entityId,
          paymentRequestId: asString(payment.providerReference),
          referenceId: asString(payment.referenceId),
          status: storedPaymentStatus,
          providerPaymentId: asString(payment.providerPaymentId),
          providerUpdatedAt: asString(payment.providerUpdatedAt),
          failureCode: asString(payment.failureCode),
        });
        continue;
      }
      if (asString(payment.status).toUpperCase() !== "PENDING") {
        await queueRef.remove();
        continue;
      }

      try {
        const paymentRequestId = asString(payment.providerReference);
        const response = await fetch(
          "https://api.xendit.co/v3/payment_requests/" + encodeURIComponent(paymentRequestId),
          { method: "GET", headers: xenditHeaders(secret) }
        );
        const provider = asRecord(await response.json().catch(() => ({})));
        if (!response.ok) {
          throw new Error("Xendit status lookup returned HTTP " + response.status);
        }
        const amount = centsFromProviderAmount(provider.request_amount);
        if (
          asString(provider.payment_request_id) !== paymentRequestId ||
          asString(provider.reference_id) !== asString(payment.referenceId) ||
          amount !== Number(payment.amountCentavos) ||
          asString(provider.currency).toUpperCase() !== "PHP" ||
          asString(provider.channel_code).toUpperCase() !== "GCASH"
        ) {
          throw new Error("Xendit status lookup did not match the stored payment.");
        }

        const status = mapProviderPaymentStatus(provider.status);
        if (status) {
          const entityType = asString(payment.entityType).toUpperCase() === "TRIP" ? "TRIP" : "ORDER";
          await applyXenditPaymentStatus({
            entityType,
            entityId,
            paymentRequestId,
            referenceId: asString(payment.referenceId),
            status,
            providerPaymentId: asString(provider.latest_payment_id),
            providerUpdatedAt: asString(provider.updated),
            failureCode: asString(provider.failure_code),
          });
        } else {
          const attempts = Number(child.value.attempts ?? 0) + 1;
          await queueRef.update({
            attempts,
            lastCheckedAtTimestamp: now,
            dueAtTimestamp: now + 5 * 60 * 1000,
          });
        }
      } catch (error) {
        logger.warn("Payment reconciliation lookup failed", { entityId, error });
        const queue = child.value;
        const attempts = Number(queue.attempts ?? 0) + 1;
        const backoffMinutes = Math.min(30, 5 * Math.pow(2, Math.min(attempts, 2)));
        await queueRef.update({
          attempts,
          lastCheckedAtTimestamp: now,
          dueAtTimestamp: now + backoffMinutes * 60 * 1000,
        });
      }
    }

    const pendingRefunds = await db.ref("refundReconciliationQueue")
      .orderByChild("dueAtTimestamp")
      .endAt(now)
      .limitToFirst(100)
      .get();
    const refundEntries: Array<{ key: string; value: Record<string, unknown> }> = [];
    pendingRefunds.forEach((snapshot) => {
      if (snapshot.key) refundEntries.push({ key: snapshot.key, value: asRecord(snapshot.val()) });
      return false;
    });

    for (const child of refundEntries) {
      const childQueueRef = db.ref("refundReconciliationQueue/" + child.key);
      const queue = child.value;
      const entityId = asString(queue.entityId);
      const refundAttemptId = asString(queue.refundAttemptId);
      const providerRefundId = asString(queue.providerRefundId);
      const referenceId = asString(queue.referenceId);
      if (!entityId || !refundAttemptId || !providerRefundId) {
        await childQueueRef.remove();
        continue;
      }
      const paymentSnapshot = await db.ref("payments/" + entityId).get();
      if (!paymentSnapshot.exists()) {
        await childQueueRef.remove();
        continue;
      }
      const payment = asRecord(paymentSnapshot.val());
      const refund = asRecord(asRecord(payment.refunds)[refundAttemptId]);
      const storedRefundStatus = asString(refund.status).toUpperCase();
      if (storedRefundStatus === "SUCCEEDED" || storedRefundStatus === "FAILED") {
        await applyXenditRefundStatus({
          entityId,
          refundAttemptId,
          referenceId,
          providerRefundId: asString(refund.providerRefundId) || providerRefundId,
          providerPaymentId: asString(payment.providerPaymentId),
          status: storedRefundStatus,
          amountCentavos: nonNegativeInteger(refund.amountCentavos, "stored refund amount"),
          providerUpdatedAt: asString(refund.providerUpdatedAt),
          failureCode: asString(refund.failureCode),
        });
        continue;
      }

      try {
        const response = await fetch(
          "https://api.xendit.co/v3/refunds/" + encodeURIComponent(providerRefundId),
          { method: "GET", headers: xenditHeaders(secret) }
        );
        const provider = asRecord(await response.json().catch(() => ({})));
        if (!response.ok) {
          throw new Error("Xendit refund lookup returned HTTP " + response.status);
        }
        const amount = centsFromProviderAmount(provider.amount);
        if (
          asString(provider.refund_id) !== providerRefundId ||
          asString(provider.reference_id) !== referenceId ||
          asString(provider.payment_id) !== asString(payment.providerPaymentId) ||
          amount !== Number(refund.amountCentavos) ||
          asString(provider.currency).toUpperCase() !== "PHP"
        ) {
          throw new Error("Xendit refund lookup did not match the stored refund.");
        }
        const statusValue = asString(provider.status).toUpperCase();
        if (statusValue === "SUCCEEDED" || statusValue === "FAILED") {
          await applyXenditRefundStatus({
            entityId,
            refundAttemptId,
            referenceId,
            providerRefundId,
            providerPaymentId: asString(provider.payment_id),
            status: statusValue,
            amountCentavos: amount,
            providerUpdatedAt: asString(provider.updated),
            failureCode: asString(provider.failure_code),
          });
        } else {
          const attempts = Number(queue.attempts ?? 0) + 1;
          await childQueueRef.update({
            attempts,
            lastCheckedAtTimestamp: now,
            dueAtTimestamp: now + 5 * 60 * 1000,
          });
        }
      } catch (error) {
        logger.warn("Refund reconciliation lookup failed", { entityId, providerRefundId, error });
        const attempts = Number(queue.attempts ?? 0) + 1;
        const backoffMinutes = Math.min(30, 5 * Math.pow(2, Math.min(attempts, 2)));
        await childQueueRef.update({
          attempts,
          lastCheckedAtTimestamp: now,
          dueAtTimestamp: now + backoffMinutes * 60 * 1000,
        });
      }
    }

    logger.info("Xendit payment reconciliation finished", {
      paymentCandidates: pendingPayments.numChildren(),
      refundCandidates: pendingRefunds.numChildren(),
    });
  }
);



// -----------------------------------------------------------------------------
// Cash collection, custody, settlement accounting and immutable double-entry
// journal. Cash is not processed through Xendit.
// -----------------------------------------------------------------------------

type CashEntityType = "ORDER" | "TRIP";
type CashLedgerLine = {
  accountId: string;
  accountType: string;
  ownerId: string;
  normalSide: "DEBIT" | "CREDIT";
  debitCentavos: number;
  creditCentavos: number;
};

function parseCashEntityType(value: unknown): CashEntityType {
  const type = asString(value).toUpperCase();
  if (type === "ORDER" || type === "TRIP") return type;
  throw new HttpsError("invalid-argument", "entityType must be ORDER or TRIP.");
}

function cashEntityPath(type: CashEntityType): "orders" | "trips" {
  return type === "ORDER" ? "orders" : "trips";
}

function cashCollectionKey(type: CashEntityType, entityId: string): string {
  return type + "_" + entityId;
}

function cashLedgerAccountId(type: string, ownerId = "PLATFORM"): string {
  const owner = ownerId || "UNASSIGNED";
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(owner)) {
    throw new HttpsError("failed-precondition", "A ledger account owner ID is invalid.");
  }
  return type + "_" + owner;
}

function cashAmountCentavos(entityType: CashEntityType, entity: Record<string, unknown>): number {
  const raw = entityType === "ORDER" ? entity.totalPriceCentavos : entity.fareCentavos;
  const amount = raw !== undefined && raw !== null
    ? nonNegativeInteger(raw, "server-calculated cash amount")
    : centsFromPhp(
      asNumber(entityType === "ORDER" ? entity.totalPrice : entity.fare, "server-calculated cash amount"),
      "server-calculated cash amount"
    );
  if (!Number.isSafeInteger(amount) || amount <= 0) {
    throw new HttpsError("failed-precondition", "The server-calculated cash amount is invalid.");
  }
  return amount;
}

function cashShareCentavos(
  entityType: CashEntityType,
  entity: Record<string, unknown>,
  amountCentavos: number
): { merchantOwedCentavos: number; platformOwedCentavos: number; collectorEarningsCentavos: number; merchantId: string } {
  const breakdown = asRecord(entity.breakdown);
  let merchantOwedCentavos = 0;
  let platformOwedCentavos = nonNegativeInteger(
    breakdown.platformShare ?? 0,
    "platform share"
  );
  let collectorEarningsCentavos = entityType === "ORDER"
    ? nonNegativeInteger(breakdown.courierShare ?? 0, "courier share")
    : nonNegativeInteger(breakdown.driverShare ?? 0, "driver share");
  let merchantId = "";

  if (entityType === "ORDER") {
    merchantOwedCentavos = nonNegativeInteger(breakdown.merchantShare ?? 0, "merchant share");
    merchantId = asString(entity.merchantId) || asString(entity.sourceId);
  }

  const splitTotal = merchantOwedCentavos + platformOwedCentavos + collectorEarningsCentavos;
  if (
    !Number.isSafeInteger(splitTotal) ||
    splitTotal !== amountCentavos ||
    (entityType === "ORDER" && merchantOwedCentavos > 0 && !merchantId)
  ) {
    throw new HttpsError(
      "failed-precondition",
      "The stored cash breakdown does not reconcile to the server-calculated total. Do not collect until pricing is corrected."
    );
  }

  return {
    merchantOwedCentavos,
    platformOwedCentavos,
    collectorEarningsCentavos,
    merchantId,
  };
}

function cashLedgerEntryId(type: "COLLECTION" | "REMITTANCE", referenceId: string): string {
  return "cash_" + type.toLowerCase() + "_" + referenceId;
}

async function ensureImmutableCashLedgerEntry(
  entryId: string,
  entry: Record<string, unknown>
): Promise<Record<string, unknown>> {
  const entryRef = db.ref("cashLedgerEntries/" + entryId);
  const payloadHash = createHash("sha256").update(JSON.stringify(entry)).digest("hex");
  const immutableEntry = { ...entry, payloadHash };
  const transaction = await entryRef.transaction((currentValue) => {
    if (currentValue !== null && currentValue !== undefined) return;
    return immutableEntry;
  });
  if (transaction.committed && transaction.snapshot.exists()) {
    return asRecord(transaction.snapshot.val());
  }
  const existingSnapshot = await entryRef.get();
  const existing = asRecord(existingSnapshot.val());
  if (
    !existingSnapshot.exists() ||
    asString(existing.operationId) !== asString(entry.operationId) ||
    Number(existing.totalCentavos) !== Number(entry.totalCentavos) ||
    asString(existing.payloadHash) !== payloadHash
  ) {
    throw new HttpsError("data-loss", "An immutable cash ledger entry ID already exists with different accounting data.");
  }
  return existing;
}

async function postImmutableCashLedgerEntry(entryId: string): Promise<void> {
  const entrySnapshot = await db.ref("cashLedgerEntries/" + entryId).get();
  if (!entrySnapshot.exists()) {
    throw new HttpsError("not-found", "The immutable cash ledger entry is missing.");
  }
  const entry = asRecord(entrySnapshot.val());
  const lines = Array.isArray(entry.lines) ? entry.lines : [];
  if (lines.length < 2) {
    throw new HttpsError("failed-precondition", "A cash journal entry must contain at least two ledger lines.");
  }

  const normalizedLines: Array<{
    line: Record<string, unknown>;
    accountId: string;
    debit: number;
    credit: number;
  }> = [];
  let debitTotal = 0;
  let creditTotal = 0;

  // Validate the complete journal before creating any account posting.
  for (const rawLine of lines) {
    const line = asRecord(rawLine);
    const debit = nonNegativeInteger(line.debitCentavos ?? 0, "ledger debit");
    const credit = nonNegativeInteger(line.creditCentavos ?? 0, "ledger credit");
    const accountId = asString(line.accountId);
    if ((debit > 0 && credit > 0) || debit + credit <= 0) {
      throw new HttpsError("failed-precondition", "Each ledger line must contain one positive debit or credit.");
    }
    if (!/^[A-Za-z0-9_-]{1,160}$/.test(accountId)) {
      throw new HttpsError("failed-precondition", "A cash ledger account ID is invalid.");
    }
    debitTotal += debit;
    creditTotal += credit;
    normalizedLines.push({ line, accountId, debit, credit });
  }

  if (
    !Number.isSafeInteger(debitTotal) ||
    !Number.isSafeInteger(creditTotal) ||
    debitTotal <= 0 ||
    debitTotal !== creditTotal ||
    debitTotal !== Number(entry.totalCentavos)
  ) {
    throw new HttpsError("failed-precondition", "The cash ledger journal entry is not balanced.");
  }

  for (const normalized of normalizedLines) {
    const line = normalized.line;
    const postingId = entryId;
    const postingRef = db.ref("cashLedgerAccounts/" + normalized.accountId + "/" + postingId);
    const posting = {
      entryId,
      operationId: asString(entry.operationId),
      accountId: normalized.accountId,
      accountType: asString(line.accountType),
      ownerId: asString(line.ownerId),
      normalSide: asString(line.normalSide),
      debitCentavos: normalized.debit,
      creditCentavos: normalized.credit,
      currency: "PHP",
      createdAtTimestamp: Number(entry.createdAtTimestamp ?? Date.now()),
    };
    const transaction = await postingRef.transaction((currentValue) => {
      if (currentValue !== null && currentValue !== undefined) return;
      return posting;
    });
    if (!transaction.committed) {
      const existingSnapshot = await postingRef.get();
      const existing = asRecord(existingSnapshot.val());
      if (
        !existingSnapshot.exists() ||
        asString(existing.entryId) !== entryId ||
        asString(existing.operationId) !== asString(entry.operationId) ||
        Number(existing.debitCentavos) !== normalized.debit ||
        Number(existing.creditCentavos) !== normalized.credit
      ) {
        throw new HttpsError("data-loss", "A cash account posting conflicts with an existing immutable posting.");
      }
    }
  }

  await db.ref("cashLedgerPostingStatus/" + entryId).set({
    status: "POSTED",
    debitTotalCentavos: debitTotal,
    creditTotalCentavos: creditTotal,
    postedAtTimestamp: Date.now(),
  });
}

function buildCashCollectionLedgerEntry(collection: Record<string, unknown>): Record<string, unknown> {
  const entityType = asString(collection.entityType).toUpperCase() as CashEntityType;
  const entityId = asString(collection.entityId);
  const collectionId = asString(collection.collectionId);
  const collectorId = asString(collection.collectorId);
  const total = nonNegativeInteger(collection.collectedAmountCentavos, "collected cash amount");
  const merchantOwed = nonNegativeInteger(collection.merchantOwedCentavos ?? 0, "merchant cash payable");
  const platformOwed = nonNegativeInteger(collection.platformOwedCentavos, "platform cash amount");
  const collectorEarnings = nonNegativeInteger(collection.collectorEarningsCentavos, "collector cash earnings");
  const lines: CashLedgerLine[] = [
    {
      accountId: cashLedgerAccountId("CASH_CUSTODY", collectorId),
      accountType: "CASH_CUSTODY",
      ownerId: collectorId,
      normalSide: "DEBIT",
      debitCentavos: total,
      creditCentavos: 0,
    },
  ];
  if (entityType === "ORDER" && merchantOwed > 0) {
    lines.push({
      accountId: cashLedgerAccountId("MERCHANT_PAYABLE", asString(collection.merchantId)),
      accountType: "MERCHANT_PAYABLE",
      ownerId: asString(collection.merchantId),
      normalSide: "CREDIT",
      debitCentavos: 0,
      creditCentavos: merchantOwed,
    });
  }
  if (collectorEarnings > 0) {
    lines.push({
      accountId: cashLedgerAccountId(entityType === "ORDER" ? "COURIER_EARNINGS" : "DRIVER_EARNINGS", collectorId),
      accountType: entityType === "ORDER" ? "COURIER_EARNINGS" : "DRIVER_EARNINGS",
      ownerId: collectorId,
      normalSide: "CREDIT",
      debitCentavos: 0,
      creditCentavos: collectorEarnings,
    });
  }
  if (platformOwed > 0) {
    lines.push({
      accountId: cashLedgerAccountId("PLATFORM_COMMISSION"),
      accountType: "PLATFORM_COMMISSION",
      ownerId: "PLATFORM",
      normalSide: "CREDIT",
      debitCentavos: 0,
      creditCentavos: platformOwed,
    });
  }

  return {
    entryId: cashLedgerEntryId("COLLECTION", collectionId),
    operationId: "CASH_COLLECTION:" + collectionId,
    entryType: "CASH_COLLECTION",
    entityType,
    entityId,
    collectionId,
    collectorId,
    collectorRole: asString(collection.collectorRole),
    amountCentavos: total,
    totalCentavos: total,
    currency: "PHP",
    lines,
    memo: entityType === "ORDER"
      ? "Cash on delivery collected by assigned courier"
      : "Cash ride fare collected by assigned driver",
    createdAtTimestamp: Number(collection.collectedAtTimestamp ?? Date.now()),
  };
}

function buildCashRemittanceLedgerEntry(
  remittance: Record<string, unknown>
): Record<string, unknown> {
  const amount = nonNegativeInteger(remittance.amountCentavos, "cash remittance amount");
  const collectorId = asString(remittance.collectorId);
  const adminUid = asString(remittance.receivedBy);
  const remittanceId = asString(remittance.remittanceId);
  const lines: CashLedgerLine[] = [
    {
      accountId: cashLedgerAccountId("PLATFORM_CASH_CUSTODY"),
      accountType: "PLATFORM_CASH_CUSTODY",
      ownerId: "PLATFORM",
      normalSide: "DEBIT",
      debitCentavos: amount,
      creditCentavos: 0,
    },
    {
      accountId: cashLedgerAccountId("CASH_CUSTODY", collectorId),
      accountType: "CASH_CUSTODY",
      ownerId: collectorId,
      normalSide: "DEBIT",
      debitCentavos: 0,
      creditCentavos: amount,
    },
  ];
  return {
    entryId: cashLedgerEntryId("REMITTANCE", remittanceId),
    operationId: "CASH_REMITTANCE:" + remittanceId,
    entryType: "CASH_REMITTANCE",
    entityType: asString(remittance.entityType),
    entityId: asString(remittance.entityId),
    collectionId: asString(remittance.collectionId),
    remittanceId,
    collectorId,
    receivedBy: adminUid,
    amountCentavos: amount,
    totalCentavos: amount,
    currency: "PHP",
    lines,
    memo: "Cash remitted by collector and received by platform administrator",
    createdAtTimestamp: Number(remittance.createdAtTimestamp ?? Date.now()),
  };
}

async function finalizeCashRemittance(
  collection: Record<string, unknown>,
  remittance: Record<string, unknown>
): Promise<void> {
  const remittanceId = asString(remittance.remittanceId);
  if (!remittanceId) throw new HttpsError("failed-precondition", "A cash remittance ID is missing.");
  const ledgerEntry = buildCashRemittanceLedgerEntry(remittance);
  await ensureImmutableCashLedgerEntry(asString(ledgerEntry.entryId), ledgerEntry);
  await postImmutableCashLedgerEntry(asString(ledgerEntry.entryId));

  const remittanceRecord = {
    ...remittance,
    ledgerEntryId: asString(ledgerEntry.entryId),
    reconciliationStatus: "POSTED",
    postedAtTimestamp: Date.now(),
  };
  const remittanceRef = db.ref("cashRemittances/" + remittanceId);
  const transaction = await remittanceRef.transaction((currentValue) => {
    if (currentValue !== null && currentValue !== undefined) {
      const current = asRecord(currentValue);
      if (
        asString(current.operationId) !== asString(remittance.operationId) ||
        Number(current.amountCentavos) !== Number(remittance.amountCentavos) ||
        asString(current.collectionId) !== asString(remittance.collectionId)
      ) return;
      return {
        ...current,
        ...remittanceRecord,
      };
    }
    return {
      ...remittanceRecord,
      operationId: asString(remittance.operationId),
    };
  });
  if (!transaction.committed) {
    const existing = asRecord(transaction.snapshot.val());
    if (
      !transaction.snapshot.exists() ||
      asString(existing.operationId) !== asString(remittance.operationId) ||
      Number(existing.amountCentavos) !== Number(remittance.amountCentavos) ||
      asString(existing.collectionId) !== asString(remittance.collectionId)
    ) {
      throw new HttpsError("data-loss", "The cash remittance record conflicts with an earlier operation.");
    }
  }

  const entityPath = cashEntityPath(asString(collection.entityType).toUpperCase() as CashEntityType);
  const entityId = asString(collection.entityId);
  const updates: Record<string, unknown> = {};
  updates[entityPath + "/" + entityId + "/cashRemittedCentavos"] =
    nonNegativeInteger(collection.remittedCentavos ?? 0, "remitted cash amount");
  updates[entityPath + "/" + entityId + "/cashOutstandingRemittanceCentavos"] =
    nonNegativeInteger(collection.remittableCentavos, "cash remittance due") -
    nonNegativeInteger(collection.remittedCentavos ?? 0, "remitted cash amount");
  updates[entityPath + "/" + entityId + "/cashRemittanceStatus"] =
    Number(collection.remittedCentavos ?? 0) >= Number(collection.remittableCentavos ?? 0)
      ? "REMITTED"
      : "PARTIALLY_REMITTED";
  await db.ref().update(updates);
  await applyCashRemittanceWalletEffects(collection, remittance);
}

async function applyCashCollectionMirror(collection: Record<string, unknown>): Promise<void> {
  const entityType = asString(collection.entityType).toUpperCase() as CashEntityType;
  const entityId = asString(collection.entityId);
  const collectorId = asString(collection.collectorId);
  const entityPath = cashEntityPath(entityType);
  const collectedAmountCentavos = nonNegativeInteger(collection.collectedAmountCentavos, "collected cash amount");
  const remittedCentavos = nonNegativeInteger(collection.remittedCentavos ?? 0, "remitted cash amount");
  const remittableCentavos = nonNegativeInteger(collection.remittableCentavos ?? 0, "cash remittance due");
  const outstandingRemittanceCentavos = Math.max(0, remittableCentavos - remittedCentavos);
  const updates: Record<string, unknown> = {};
  updates[entityPath + "/" + entityId + "/status"] =
    entityType === "ORDER" ? "DELIVERED" : "COMPLETED";
  updates[entityPath + "/" + entityId + "/paymentStatus"] = "COLLECTED";
  updates[entityPath + "/" + entityId + "/cashCollectionStatus"] = "COLLECTED";
  updates[entityPath + "/" + entityId + "/cashCollectionId"] = asString(collection.collectionId);
  updates[entityPath + "/" + entityId + "/cashCollectedBy"] = collectorId;
  updates[entityPath + "/" + entityId + "/cashCollectedByRole"] = asString(collection.collectorRole);
  updates[entityPath + "/" + entityId + "/cashCollectedAmountCentavos"] = collectedAmountCentavos;
  updates[entityPath + "/" + entityId + "/cashCollectedAtTimestamp"] = Number(collection.collectedAtTimestamp ?? 0);
  updates[entityPath + "/" + entityId + "/cashMerchantOwedCentavos"] =
    nonNegativeInteger(collection.merchantOwedCentavos ?? 0, "merchant cash payable");
  updates[entityPath + "/" + entityId + "/cashPlatformOwedCentavos"] =
    nonNegativeInteger(collection.platformOwedCentavos ?? 0, "platform cash amount");
  updates[entityPath + "/" + entityId + "/cashCollectorEarningsCentavos"] =
    nonNegativeInteger(collection.collectorEarningsCentavos ?? 0, "collector cash earnings");
  updates[entityPath + "/" + entityId + "/cashRemittedCentavos"] = remittedCentavos;
  updates[entityPath + "/" + entityId + "/cashOutstandingRemittanceCentavos"] = outstandingRemittanceCentavos;
  updates[entityPath + "/" + entityId + "/cashRemittanceStatus"] =
    remittableCentavos === 0 ? "NOT_DUE" :
      outstandingRemittanceCentavos === 0 ? "REMITTED" :
        remittedCentavos > 0 ? "PARTIALLY_REMITTED" : "DUE";
  updates["payments/" + entityId + "/status"] = "COLLECTED";
  updates["payments/" + entityId + "/cashCollectedBy"] = collectorId;
  updates["payments/" + entityId + "/cashCollectedByRole"] = asString(collection.collectorRole);
  updates["payments/" + entityId + "/cashCollectedAmountCentavos"] = collectedAmountCentavos;
  updates["payments/" + entityId + "/cashCollectedAtTimestamp"] = Number(collection.collectedAtTimestamp ?? 0);
  updates["payments/" + entityId + "/cashRemittedCentavos"] = remittedCentavos;
  updates["payments/" + entityId + "/cashOutstandingRemittanceCentavos"] = outstandingRemittanceCentavos;
  await db.ref().update(updates);
}

async function finalizeCashCollection(
  collection: Record<string, unknown>
): Promise<void> {
  const collectionId = asString(collection.collectionId);
  if (!collectionId) throw new HttpsError("failed-precondition", "A cash collection ID is missing.");
  await applyCashCollectionMirror(collection);

  const collectionLedger = buildCashCollectionLedgerEntry(collection);
  await ensureImmutableCashLedgerEntry(asString(collectionLedger.entryId), collectionLedger);
  await postImmutableCashLedgerEntry(asString(collectionLedger.entryId));
  await applyCashCollectionWalletEffects(collection);

  const collectionRef = db.ref("cashCollections/" + collectionId);
  const events = asRecord(collection.remittanceEvents);
  for (const [remittanceId, raw] of Object.entries(events)) {
    const remittance = {
      ...asRecord(raw),
      remittanceId,
      collectionId,
      entityType: asString(collection.entityType),
      entityId: asString(collection.entityId),
      collectorId: asString(collection.collectorId),
    };
    await finalizeCashRemittance(collection, remittance);
    await collectionRef.child("remittanceEvents").child(remittanceId).update({
      reconciliationStatus: "POSTED",
      postedAtTimestamp: Date.now(),
    });
  }

  // Re-read transactionally so a concurrent remittance cannot be hidden by a stale
  // finalizer setting the parent collection back to POSTED.
  await collectionRef.transaction((currentValue) => {
    const current = asRecord(currentValue);
    const latestEvents = asRecord(current.remittanceEvents);
    const hasPendingRemittances = Object.values(latestEvents).some((rawEvent) =>
      asString(asRecord(rawEvent).reconciliationStatus).toUpperCase() !== "POSTED"
    );
    return {
      ...current,
      ledgerEntryId: asString(collectionLedger.entryId),
      reconciliationStatus: hasPendingRemittances ? "PENDING" : "POSTED",
      postedAtTimestamp: hasPendingRemittances ? Number(current.postedAtTimestamp ?? 0) : Date.now(),
    };
  });
}

export const confirmCashCollected = onCall(
  { region: REGION },
  async (request) => {
    const uid = requireUid(request);
    const data = asRecord(request.data);
    const entityType = parseCashEntityType(data.entityType);
    const entityId = asString(data.entityId);
    if (!/^[A-Za-z0-9_-]{12,80}$/.test(entityId)) {
      throw new HttpsError("invalid-argument", "A valid order or trip ID is required.");
    }

    const collectionId = cashCollectionKey(entityType, entityId);
    const collectionRef = db.ref("cashCollections/" + collectionId);
    const existingSnapshot = await collectionRef.get();
    if (existingSnapshot.exists()) {
      const existing = asRecord(existingSnapshot.val());
      if (asString(existing.collectorId) !== uid) {
        throw new HttpsError("permission-denied", "This cash collection was confirmed by another collector.");
      }
      await finalizeCashCollection(existing);
      return {
        collectionId,
        entityId,
        collectionStatus: "COLLECTED",
        collectedAmountCentavos: Number(existing.collectedAmountCentavos ?? 0),
        merchantOwedCentavos: Number(existing.merchantOwedCentavos ?? 0),
        platformOwedCentavos: Number(existing.platformOwedCentavos ?? 0),
        collectorEarningsCentavos: Number(existing.collectorEarningsCentavos ?? 0),
        remittableCentavos: Number(existing.remittableCentavos ?? 0),
        remittedCentavos: Number(existing.remittedCentavos ?? 0),
        outstandingRemittanceCentavos:
          Number(existing.remittableCentavos ?? 0) - Number(existing.remittedCentavos ?? 0),
        reused: true,
      };
    }

    const entityPath = cashEntityPath(entityType);
    const entitySnapshot = await db.ref(entityPath + "/" + entityId).get();
    if (!entitySnapshot.exists()) throw new HttpsError("not-found", "The order or trip was not found.");
    const entity = asRecord(entitySnapshot.val());

    const assignedCollector = entityType === "ORDER"
      ? asString(entity.deliveryId)
      : asString(entity.driverId);
    if (assignedCollector !== uid) {
      throw new HttpsError("permission-denied", "Only the assigned courier or ride driver can confirm this cash collection.");
    }
    if (entityType === "ORDER") await requireApprovedCourier(uid);
    else await requireApprovedDriver(uid);
    const expectedStatus = entityType === "ORDER"
      ? ["OUT_FOR_DELIVERY", "DELIVERED"]
      : ["IN_PROGRESS", "COMPLETED"];
    if (!expectedStatus.includes(asString(entity.status).toUpperCase())) {
      throw new HttpsError("failed-precondition", "The order or trip is not at the cash collection stage.");
    }
    if (asString(entity.paymentMethod).toUpperCase() !== "CASH") {
      throw new HttpsError("failed-precondition", "This order or trip was not created with Cash.");
    }

    const paymentSnapshot = await db.ref("payments/" + entityId).get();
    if (!paymentSnapshot.exists()) {
      throw new HttpsError("failed-precondition", "The server-created cash payment record is missing.");
    }
    const payment = asRecord(paymentSnapshot.val());
    if (
      asString(payment.provider).toUpperCase() !== "CASH" ||
      asString(payment.channelCode).toUpperCase() !== "CASH"
    ) {
      throw new HttpsError("failed-precondition", "Only a server-created Cash payment can be confirmed through the cash collection flow.");
    }
    if (["PAID", "COLLECTED", "REFUNDED", "PARTIALLY_REFUNDED"].includes(asString(payment.status).toUpperCase())) {
      throw new HttpsError("failed-precondition", "This payment is already settled or refunded.");
    }

    const amountCentavos = cashAmountCentavos(entityType, entity);
    const shares = cashShareCentavos(entityType, entity, amountCentavos);
    const collectorRole = entityType === "ORDER" ? "COURIER" : "DRIVER";
    const timestamp = Date.now();
    const remittableCentavos = shares.merchantOwedCentavos + shares.platformOwedCentavos;
    const collection = {
      collectionId,
      entityType,
      entityId,
      collectorId: uid,
      collectorRole,
      clientId: entityType === "ORDER" ? asString(entity.clientId) : asString(entity.passengerId),
      merchantId: shares.merchantId,
      collectedAmountCentavos: amountCentavos,
      merchantOwedCentavos: shares.merchantOwedCentavos,
      platformOwedCentavos: shares.platformOwedCentavos,
      collectorEarningsCentavos: shares.collectorEarningsCentavos,
      remittableCentavos,
      remittedCentavos: 0,
      outstandingRemittanceCentavos: remittableCentavos,
      remittanceEvents: {},
      currency: "PHP",
      status: "COLLECTED",
      confirmedBy: uid,
      collectedAtTimestamp: timestamp,
      reconciliationStatus: "PENDING",
      ledgerEntryId: cashLedgerEntryId("COLLECTION", collectionId),
    };

    const reservation = await collectionRef.transaction((currentValue) => {
      if (currentValue !== null && currentValue !== undefined) return;
      return collection;
    });
    if (!reservation.committed) {
      const latestSnapshot = await collectionRef.get();
      if (!latestSnapshot.exists()) {
        throw new HttpsError("aborted", "Cash collection could not be reserved. Retry.");
      }
      const latest = asRecord(latestSnapshot.val());
      if (asString(latest.collectorId) !== uid) {
        throw new HttpsError("permission-denied", "This cash collection was already confirmed by another collector.");
      }
      await finalizeCashCollection(latest);
      return {
        collectionId,
        entityId,
        collectionStatus: "COLLECTED",
        collectedAmountCentavos: Number(latest.collectedAmountCentavos ?? 0),
        merchantOwedCentavos: Number(latest.merchantOwedCentavos ?? 0),
        platformOwedCentavos: Number(latest.platformOwedCentavos ?? 0),
        collectorEarningsCentavos: Number(latest.collectorEarningsCentavos ?? 0),
        remittableCentavos: Number(latest.remittableCentavos ?? 0),
        remittedCentavos: Number(latest.remittedCentavos ?? 0),
        outstandingRemittanceCentavos:
          Number(latest.remittableCentavos ?? 0) - Number(latest.remittedCentavos ?? 0),
        reused: true,
      };
    }

    const updates: Record<string, unknown> = {};
    updates[entityPath + "/" + entityId + "/status"] =
      entityType === "ORDER" ? "DELIVERED" : "COMPLETED";
    updates[entityPath + "/" + entityId + "/paymentStatus"] = "COLLECTED";
    updates[entityPath + "/" + entityId + "/cashCollectionStatus"] = "COLLECTED";
    updates[entityPath + "/" + entityId + "/cashCollectionId"] = collectionId;
    updates[entityPath + "/" + entityId + "/cashCollectedBy"] = uid;
    updates[entityPath + "/" + entityId + "/cashCollectedByRole"] = collectorRole;
    updates[entityPath + "/" + entityId + "/cashCollectedAmountCentavos"] = amountCentavos;
    updates[entityPath + "/" + entityId + "/cashCollectedAtTimestamp"] = timestamp;
    updates[entityPath + "/" + entityId + "/cashMerchantOwedCentavos"] = shares.merchantOwedCentavos;
    updates[entityPath + "/" + entityId + "/cashPlatformOwedCentavos"] = shares.platformOwedCentavos;
    updates[entityPath + "/" + entityId + "/cashCollectorEarningsCentavos"] = shares.collectorEarningsCentavos;
    updates[entityPath + "/" + entityId + "/cashRemittedCentavos"] = 0;
    updates[entityPath + "/" + entityId + "/cashOutstandingRemittanceCentavos"] = remittableCentavos;
    updates[entityPath + "/" + entityId + "/cashRemittanceStatus"] = remittableCentavos > 0 ? "DUE" : "NOT_DUE";
    updates["payments/" + entityId + "/status"] = "COLLECTED";
    updates["payments/" + entityId + "/cashCollectedBy"] = uid;
    updates["payments/" + entityId + "/cashCollectedByRole"] = collectorRole;
    updates["payments/" + entityId + "/cashCollectedAmountCentavos"] = amountCentavos;
    updates["payments/" + entityId + "/cashCollectedAtTimestamp"] = timestamp;
    await db.ref().update(updates);

    await finalizeCashCollection(collection);
    return {
      collectionId,
      entityId,
      collectionStatus: "COLLECTED",
      collectedAmountCentavos: amountCentavos,
      merchantOwedCentavos: shares.merchantOwedCentavos,
      platformOwedCentavos: shares.platformOwedCentavos,
      collectorEarningsCentavos: shares.collectorEarningsCentavos,
      remittableCentavos,
      remittedCentavos: 0,
      outstandingRemittanceCentavos: remittableCentavos,
      reused: false,
    };
  }
);

export const recordCashRemittance = onCall(
  { region: REGION },
  async (request) => {
    const adminUid = requireAdmin(request);
    const data = asRecord(request.data);
    const entityType = parseCashEntityType(data.entityType);
    const entityId = asString(data.entityId);
    if (!/^[A-Za-z0-9_-]{12,80}$/.test(entityId)) {
      throw new HttpsError("invalid-argument", "A valid order or trip ID is required.");
    }
    const idempotencyKey = parseIdempotencyKey(data.idempotencyKey);
    const amountCentavos = nonNegativeInteger(data.amountCentavos, "cash remittance amount");
    if (amountCentavos <= 0) {
      throw new HttpsError("invalid-argument", "Cash remittance amount must be greater than zero.");
    }
    const collectionId = cashCollectionKey(entityType, entityId);
    const collectionRef = db.ref("cashCollections/" + collectionId);
    const collectionSnapshot = await collectionRef.get();
    if (!collectionSnapshot.exists()) {
      throw new HttpsError("not-found", "The cash collection does not exist.");
    }
    const remittanceId = createHash("sha256")
      .update(adminUid + "|" + collectionId + "|" + idempotencyKey)
      .digest("hex")
      .slice(0, 32);

    let reservationError = "";
    let remittance: Record<string, unknown> = {};
    const transaction = await collectionRef.transaction((rawValue) => {
      const current = asRecord(rawValue);
      const events = asRecord(current.remittanceEvents);
      const existing = asRecord(events[remittanceId]);
      if (Object.keys(existing).length > 0) {
        if (Number(existing.amountCentavos) !== amountCentavos || asString(existing.receivedBy) !== adminUid) {
          reservationError = "This remittance request key was already used with different details.";
          return;
        }
        remittance = existing;
        return current;
      }

      const remittable = nonNegativeInteger(current.remittableCentavos, "cash remittance due");
      const alreadyRemitted = nonNegativeInteger(current.remittedCentavos ?? 0, "cash already remitted");
      const outstanding = remittable - alreadyRemitted;
      if (amountCentavos > outstanding) {
        reservationError = "The remittance exceeds the remaining cash owed by this collector.";
        return;
      }

      const timestamp = Date.now();
      remittance = {
        remittanceId,
        operationId: "CASH_REMITTANCE:" + remittanceId,
        collectionId,
        entityType,
        entityId,
        collectorId: asString(current.collectorId),
        receivedBy: adminUid,
        amountCentavos,
        currency: "PHP",
        createdAtTimestamp: timestamp,
        reconciliationStatus: "PENDING",
      };
      return {
        ...current,
        remittedCentavos: alreadyRemitted + amountCentavos,
        outstandingRemittanceCentavos: outstanding - amountCentavos,
        remittanceEvents: {
          ...events,
          [remittanceId]: remittance,
        },
        cashRemittanceStatus: outstanding - amountCentavos === 0 ? "REMITTED" : "PARTIALLY_REMITTED",
        reconciliationStatus: "PENDING",
        updatedAtTimestamp: timestamp,
      };
    });

    if (!transaction.committed && !transaction.snapshot.exists() || reservationError) {
      throw new HttpsError("failed-precondition", reservationError || "The remittance could not be recorded.");
    }
    const latestCollection = asRecord(transaction.snapshot.val() ?? (await collectionRef.get()).val());
    if (Object.keys(remittance).length === 0) {
      remittance = asRecord(asRecord(latestCollection.remittanceEvents)[remittanceId]);
    }
    await finalizeCashCollection(latestCollection);
    const updatedCollection = asRecord((await collectionRef.get()).val());

    return {
      remittanceId,
      entityId,
      amountCentavos,
      remittedCentavos: Number(updatedCollection.remittedCentavos ?? 0),
      outstandingRemittanceCentavos: Number(updatedCollection.outstandingRemittanceCentavos ?? 0),
      status: asString(updatedCollection.cashRemittanceStatus) || "DUE",
      reused: !transaction.committed,
    };
  }
);

export const reconcileCash = onSchedule(
  {
    schedule: "every 10 minutes",
    timeZone: "Asia/Manila",
    region: REGION,
  },
  async () => {
    const pending = await db.ref("cashCollections")
      .orderByChild("reconciliationStatus")
      .equalTo("PENDING")
      .limitToFirst(100)
      .get();
    const collections: Record<string, unknown>[] = [];
    pending.forEach((snapshot) => {
      if (snapshot.exists()) collections.push(asRecord(snapshot.val()));
      return false;
    });

    let reconciled = 0;
    let failed = 0;
    for (const collection of collections) {
      try {
        await finalizeCashCollection(collection);
        reconciled++;
      } catch (error) {
        failed++;
        logger.error("Cash ledger reconciliation failed", {
          collectionId: asString(collection.collectionId),
          error,
        });
      }
    }

    logger.info("Cash ledger reconciliation finished", {
      pendingCollections: collections.length,
      reconciled,
      failed,
    });
  }
);


// -----------------------------------------------------------------------------
// Marketplace wallets, immutable accounting ledger and Xendit Payouts v3.
// All balances and destination credentials are server-owned. Android uses only
// the callable APIs below and never writes wallet, ledger or payout state.
// -----------------------------------------------------------------------------

type WalletDeltaField =
  | "availableCentavos"
  | "pendingCentavos"
  | "cashOwedCentavos"
  | "cashEarningsCentavos"
  | "pendingPayoutCentavos"
  | "paidOutCentavos"
  | "refundDebtCentavos";

type WalletDeltas = Partial<Record<WalletDeltaField, number>>;

const MANUAL_PAYOUT_MINIMUM_CENTAVOS = 10000; // PHP 100.00
const PAYOUT_RECONCILIATION_MS = 5 * 60 * 1000;

function payoutEncryptionKey(): Buffer {
  const encoded = PAYOUT_ACCOUNT_ENCRYPTION_KEY.value().trim();
  const key = Buffer.from(encoded, "hex");
  if (key.length !== 32) {
    throw new HttpsError(
      "failed-precondition",
      "PAYOUT_ACCOUNT_ENCRYPTION_KEY must be configured as 64 hexadecimal characters."
    );
  }
  return key;
}

function encryptPayoutAccountNumber(value: string): {
  ciphertext: string;
  iv: string;
  tag: string;
} {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", payoutEncryptionKey(), iv);
  const ciphertext = Buffer.concat([
    cipher.update(value, "utf8"),
    cipher.final(),
  ]);
  return {
    ciphertext: ciphertext.toString("base64"),
    iv: iv.toString("base64"),
    tag: cipher.getAuthTag().toString("base64"),
  };
}

function decryptPayoutAccountNumber(account: Record<string, unknown>): string {
  const decipher = createDecipheriv(
    "aes-256-gcm",
    payoutEncryptionKey(),
    Buffer.from(asString(account.accountNumberIv), "base64")
  );
  decipher.setAuthTag(Buffer.from(asString(account.accountNumberTag), "base64"));
  return Buffer.concat([
    decipher.update(Buffer.from(asString(account.accountNumberCiphertext), "base64")),
    decipher.final(),
  ]).toString("utf8");
}

function maskAccountNumber(value: string): string {
  const digits = value.replace(/\D/g, "");
  return digits.length <= 4 ? "••••" : "•••• •••• " + digits.slice(-4);
}

function walletNumber(record: Record<string, unknown>, key: WalletDeltaField): number {
  return nonNegativeInteger(record[key] ?? 0, "wallet " + key);
}

async function applyWalletDeltas(
  uid: string,
  eventId: string,
  deltas: WalletDeltas
): Promise<void> {
  if (!uid || !/^[A-Za-z0-9_-]{1,128}$/.test(uid)) {
    throw new HttpsError("failed-precondition", "A wallet owner ID is invalid.");
  }
  if (!/^[A-Za-z0-9_-]{1,180}$/.test(eventId)) {
    throw new HttpsError("failed-precondition", "A wallet event ID is invalid.");
  }
  const walletRef = db.ref("wallets/" + uid);
  let invalidBalance = "";
  const transaction = await walletRef.transaction((rawValue) => {
    const current = asRecord(rawValue);
    const applied = asRecord(current.appliedLedgerEntries);
    if (applied[eventId] === true) return;
    const next: Record<string, unknown> = {
      ...current,
      currency: "PHP",
      updatedAtTimestamp: Date.now(),
    };
    for (const [rawField, rawDelta] of Object.entries(deltas)) {
      const field = rawField as WalletDeltaField;
      const delta = Number(rawDelta ?? 0);
      if (!Number.isSafeInteger(delta)) {
        invalidBalance = "A wallet movement is not an integer number of centavos.";
        return;
      }
      let appliedDelta = delta;
      if (field === "availableCentavos" && delta > 0) {
        const refundDebt = walletNumber(current, "refundDebtCentavos");
        const recovered = Math.min(refundDebt, delta);
        if (recovered > 0) next.refundDebtCentavos = refundDebt - recovered;
        appliedDelta -= recovered;
      }
      const amount = walletNumber(current, field) + appliedDelta;
      if (!Number.isSafeInteger(amount) || amount < 0) {
        invalidBalance = "A wallet movement would create an invalid or negative balance.";
        return;
      }
      next[field] = amount;
    }
    next.appliedLedgerEntries = {
      ...applied,
      [eventId]: true,
    };
    return next;
  });
  if (invalidBalance) throw new HttpsError("data-loss", invalidBalance);
  if (!transaction.committed) {
    const saved = asRecord((await walletRef.get()).val());
    if (asRecord(saved.appliedLedgerEntries)[eventId] === true) return;
    throw new HttpsError("aborted", "Wallet balance update could not be committed.");
  }
}

async function applyPayoutWalletTerminal(
  uid: string,
  payoutId: string,
  amountCentavos: number,
  terminal: "SUCCEEDED" | "FAILED" | "REVERSED"
): Promise<{ refundRecoveredCentavos: number }> {
  const walletRef = db.ref("wallets/" + uid);
  const eventId = "payout_" + terminal.toLowerCase() + "_" + payoutId;
  let errorMessage = "";
  let refundRecoveredCentavos = 0;
  const transaction = await walletRef.transaction((rawValue) => {
    const current = asRecord(rawValue);
    const applied = asRecord(current.appliedLedgerEntries);
    const terminalEvents = asRecord(current.payoutTerminalEvents);
    const priorEvent = asRecord(terminalEvents[eventId]);
    const next: Record<string, unknown> = { ...current, currency: "PHP", updatedAtTimestamp: Date.now() };
    if (applied[eventId] === true || Object.keys(priorEvent).length > 0) {
      refundRecoveredCentavos = Number(priorEvent.refundRecoveredCentavos ?? 0);
      return current;
    }
    let available = walletNumber(current, "availableCentavos");
    let pending = walletNumber(current, "pendingPayoutCentavos");
    let paidOut = walletNumber(current, "paidOutCentavos");
    if (terminal === "SUCCEEDED") {
      if (pending < amountCentavos) {
        errorMessage = "Payout pending balance is smaller than the confirmed provider payout.";
        return;
      }
      pending -= amountCentavos;
      paidOut += amountCentavos;
    } else if (terminal === "FAILED") {
      if (pending < amountCentavos) {
        errorMessage = "Payout pending balance is smaller than the failed provider payout.";
        return;
      }
      pending -= amountCentavos;
      const refundDebt = walletNumber(current, "refundDebtCentavos");
      refundRecoveredCentavos = Math.min(refundDebt, amountCentavos);
      available += amountCentavos - refundRecoveredCentavos;
      next.refundDebtCentavos = refundDebt - refundRecoveredCentavos;
    } else {
      if (paidOut < amountCentavos) {
        errorMessage = "Paid-out balance is smaller than the reversed provider payout.";
        return;
      }
      paidOut -= amountCentavos;
      const refundDebt = walletNumber(current, "refundDebtCentavos");
      refundRecoveredCentavos = Math.min(refundDebt, amountCentavos);
      available += amountCentavos - refundRecoveredCentavos;
      next.refundDebtCentavos = refundDebt - refundRecoveredCentavos;
    }
    if (![available, pending, paidOut].every(Number.isSafeInteger)) {
      errorMessage = "Payout reconciliation exceeded the supported wallet balance.";
      return;
    }
    next.availableCentavos = available;
    next.pendingPayoutCentavos = pending;
    next.paidOutCentavos = paidOut;
    next.appliedLedgerEntries = { ...applied, [eventId]: true };
    next.payoutTerminalEvents = {
      ...terminalEvents,
      [eventId]: { refundRecoveredCentavos, amountCentavos, terminal },
    };
    if (asString(current.activePayoutId) === payoutId) next.activePayoutId = null;
    return next;
  });
  if (errorMessage) throw new HttpsError("data-loss", errorMessage);
  if (!transaction.committed) {
    const current = asRecord((await walletRef.get()).val());
    const terminalEvent = asRecord(asRecord(current.payoutTerminalEvents)[eventId]);
    if (Object.keys(terminalEvent).length === 0) {
      throw new HttpsError("aborted", "Payout wallet reconciliation could not be committed.");
    }
    refundRecoveredCentavos = Number(terminalEvent.refundRecoveredCentavos ?? 0);
  }
  return { refundRecoveredCentavos };
}

type LedgerLine = {
  accountId: string;
  accountType: string;
  ownerId: string;
  normalSide: "DEBIT" | "CREDIT";
  debitCentavos: number;
  creditCentavos: number;
};

function ledgerLine(
  accountType: string,
  ownerId: string,
  side: "DEBIT" | "CREDIT",
  amountCentavos: number
): LedgerLine {
  const safeOwner = ownerId || "PLATFORM";
  const accountId = accountType + "_" + safeOwner;
  return {
    accountId,
    accountType,
    ownerId: safeOwner,
    normalSide: side,
    debitCentavos: side === "DEBIT" ? amountCentavos : 0,
    creditCentavos: side === "CREDIT" ? amountCentavos : 0,
  };
}

async function ensureMarketplaceLedgerEntry(
  entryId: string,
  entry: Record<string, unknown>
): Promise<void> {
  const lines = Array.isArray(entry.lines) ? entry.lines.map(asRecord) : [];
  let debit = 0;
  let credit = 0;
  for (const line of lines) {
    const accountId = asString(line.accountId);
    const debitAmount = nonNegativeInteger(line.debitCentavos ?? 0, "ledger debit");
    const creditAmount = nonNegativeInteger(line.creditCentavos ?? 0, "ledger credit");
    if (!accountId || (debitAmount > 0 && creditAmount > 0) || debitAmount + creditAmount <= 0) {
      throw new HttpsError("failed-precondition", "A marketplace ledger line is invalid.");
    }
    debit += debitAmount;
    credit += creditAmount;
  }
  if (
    lines.length < 2 ||
    !Number.isSafeInteger(debit) ||
    debit <= 0 ||
    debit !== credit ||
    debit !== Number(entry.totalCentavos)
  ) {
    throw new HttpsError("failed-precondition", "The marketplace ledger entry is not balanced.");
  }
  const payload = { ...entry, entryId, currency: "PHP" };
  const payloadHash = createHash("sha256").update(JSON.stringify(payload)).digest("hex");
  const entryRef = db.ref("ledger/" + entryId);
  const transaction = await entryRef.transaction((currentValue) => {
    if (currentValue !== null && currentValue !== undefined) return;
    return { ...payload, payloadHash };
  });
  if (!transaction.committed) {
    const existing = asRecord((await entryRef.get()).val());
    if (
      asString(existing.payloadHash) !== payloadHash ||
      asString(existing.operationId) !== asString(entry.operationId)
    ) {
      throw new HttpsError("data-loss", "An immutable marketplace ledger entry conflicts with an earlier entry.");
    }
  }

  // Per-account postings are immutable and deterministic, so the admin ledger can
  // be reconciled without trusting mutable wallet summaries.
  for (const rawLine of lines) {
    const accountId = asString(rawLine.accountId);
    const postingRef = db.ref("ledgerAccounts/" + accountId + "/" + entryId);
    const posting = {
      entryId,
      operationId: asString(entry.operationId),
      accountId,
      accountType: asString(rawLine.accountType),
      ownerId: asString(rawLine.ownerId),
      debitCentavos: Number(rawLine.debitCentavos ?? 0),
      creditCentavos: Number(rawLine.creditCentavos ?? 0),
      createdAtTimestamp: Number(entry.createdAtTimestamp ?? Date.now()),
      currency: "PHP",
    };
    const postingTransaction = await postingRef.transaction((currentValue) => {
      if (currentValue !== null && currentValue !== undefined) return;
      return posting;
    });
    if (!postingTransaction.committed) {
      const existing = asRecord((await postingRef.get()).val());
      if (
        !postingTransaction.snapshot.exists() ||
        asString(existing.entryId) !== entryId ||
        Number(existing.debitCentavos) !== posting.debitCentavos ||
        Number(existing.creditCentavos) !== posting.creditCentavos
      ) {
        throw new HttpsError("data-loss", "An immutable ledger account posting conflicts with an earlier posting.");
      }
    }
  }
  await db.ref("ledgerPostingStatus/" + entryId).set({
    status: "POSTED",
    debitTotalCentavos: debit,
    creditTotalCentavos: credit,
    postedAtTimestamp: Date.now(),
  });
}

function payoutAccountSummary(account: Record<string, unknown>): Record<string, unknown> {
  return {
    status: asString(account.status) || "NOT_CONFIGURED",
    accountType: asString(account.accountType),
    channelCode: asString(account.channelCode),
    accountHolderName: asString(account.accountHolderName),
    maskedAccountNumber: asString(account.maskedAccountNumber),
    submittedAtTimestamp: Number(account.submittedAtTimestamp ?? 0),
    reviewedAtTimestamp: Number(account.reviewedAtTimestamp ?? 0),
    reviewNote: asString(account.reviewNote),
  };
}

function normalizePhilippineMobile(value: string): string {
  const digits = value.replace(/\D/g, "");
  if (/^09\d{9}$/.test(digits)) return "+63" + digits.slice(1);
  if (/^639\d{9}$/.test(digits)) return "+" + digits;
  if (/^9\d{9}$/.test(digits)) return "+63" + digits;
  throw new HttpsError("invalid-argument", "Enter a valid Philippine mobile number.");
}

async function requireApprovedEarningsRole(uid: string): Promise<void> {
  const snapshot = await db.ref("users/" + uid).get();
  if (!snapshot.exists()) throw new HttpsError("failed-precondition", "Your Labmda profile is missing.");
  const profile = asRecord(snapshot.val());
  const delivery = asRecord(profile.deliveryServices);
  const driver = asRecord(profile.driverService);
  const approved = [
    asString(profile.foodStatus),
    asString(profile.shopStatus),
    asString(delivery.status),
    asString(driver.status),
  ].some((status) => ["APPROVED", "ACTIVE"].includes(status.toUpperCase()));
  if (!approved) {
    throw new HttpsError("failed-precondition", "Wallet payouts are available only to approved merchants, couriers and drivers.");
  }
}

async function applyCashCollectionWalletEffects(collection: Record<string, unknown>): Promise<void> {
  const collectionId = asString(collection.collectionId);
  const collectorId = asString(collection.collectorId);
  const merchantId = asString(collection.merchantId);
  const merchantOwed = nonNegativeInteger(collection.merchantOwedCentavos ?? 0, "merchant cash payable");
  const platformOwed = nonNegativeInteger(collection.platformOwedCentavos ?? 0, "platform cash payable");
  const collectorEarnings = nonNegativeInteger(collection.collectorEarningsCentavos ?? 0, "collector cash earnings");
  const remittable = merchantOwed + platformOwed;
  if (collectorId) {
    await applyWalletDeltas(collectorId, "cash_collection_" + collectionId, {
      cashOwedCentavos: remittable,
      cashEarningsCentavos: collectorEarnings,
    });
  }
  if (merchantId && merchantOwed > 0) {
    await applyWalletDeltas(merchantId, "cash_merchant_pending_" + collectionId, {
      pendingCentavos: merchantOwed,
    });
  }
}

async function applyCashRemittanceWalletEffects(
  collection: Record<string, unknown>,
  remittance: Record<string, unknown>
): Promise<void> {
  const collectionId = asString(collection.collectionId);
  const remittanceId = asString(remittance.remittanceId);
  const collectorId = asString(collection.collectorId);
  const amount = nonNegativeInteger(remittance.amountCentavos, "cash remittance amount");
  if (collectorId && amount > 0) {
    await applyWalletDeltas(collectorId, "cash_remittance_" + remittanceId, {
      cashOwedCentavos: -amount,
    });
  }
  const outstanding = nonNegativeInteger(
    collection.outstandingRemittanceCentavos ?? 0,
    "cash remittance outstanding amount"
  );
  const merchantId = asString(collection.merchantId);
  const merchantOwed = nonNegativeInteger(collection.merchantOwedCentavos ?? 0, "merchant cash payable");
  if (outstanding === 0 && merchantId && merchantOwed > 0) {
    const walletRelease = await applyWalletEarningCredit(
      merchantId,
      "cash_merchant_release_" + collectionId + "_" + merchantId,
      merchantOwed,
      true
    );
    const availableCredit = merchantOwed - walletRelease.recoveredCentavos;
    const releaseEntryId = "cash_merchant_release_" + collectionId;
    const releaseEntry = {
      operationId: "CASH_MERCHANT_RELEASE:" + collectionId,
      entryType: "CASH_MERCHANT_RELEASE",
      collectionId,
      merchantId,
      totalCentavos: merchantOwed,
      lines: [
        ledgerLine("MERCHANT_PENDING_PAYABLE", merchantId, "DEBIT", merchantOwed),
        ...(availableCredit > 0 ? [ledgerLine("MERCHANT_AVAILABLE_PAYABLE", merchantId, "CREDIT", availableCredit)] : []),
        ...(walletRelease.recoveredCentavos > 0 ? [ledgerLine("WALLET_REFUND_RECEIVABLE", merchantId, "CREDIT", walletRelease.recoveredCentavos)] : []),
      ],
      memo: "Release merchant cash earnings after collector remittance is complete",
      createdAtTimestamp: Number(collection.createdAtTimestamp ?? 1),
    };
    await ensureMarketplaceLedgerEntry(releaseEntryId, releaseEntry);
    await db.ref("cashCollections/" + collectionId).update({
      merchantSettlementStatus: "RELEASED",
      merchantSettlementLedgerEntryId: releaseEntryId,
      merchantSettledAtTimestamp: Date.now(),
    });
  }
}

async function applyWalletEarningCredit(
  uid: string,
  eventId: string,
  amountCentavos: number,
  releasePending: boolean
): Promise<{ recoveredCentavos: number }> {
  const walletRef = db.ref("wallets/" + uid);
  let recoveredCentavos = 0;
  let priorEvent = false;
  const transaction = await walletRef.transaction((rawValue) => {
    const current = asRecord(rawValue);
    const applied = asRecord(current.appliedLedgerEntries);
    const creditEvents = asRecord(current.earningCreditEvents);
    const previous = asRecord(creditEvents[eventId]);
    if (applied[eventId] === true || Object.keys(previous).length > 0) {
      priorEvent = true;
      recoveredCentavos = Number(previous.recoveredCentavos ?? 0);
      return current;
    }
    const pending = walletNumber(current, "pendingCentavos");
    if (releasePending && pending < amountCentavos) {
      throw new HttpsError("data-loss", "Pending earnings are smaller than the amount being released.");
    }
    const debt = walletNumber(current, "refundDebtCentavos");
    recoveredCentavos = Math.min(debt, amountCentavos);
    const availableCredit = amountCentavos - recoveredCentavos;
    const next: Record<string, unknown> = {
      ...current,
      availableCentavos: walletNumber(current, "availableCentavos") + availableCredit,
      refundDebtCentavos: debt - recoveredCentavos,
      currency: "PHP",
      updatedAtTimestamp: Date.now(),
      appliedLedgerEntries: { ...applied, [eventId]: true },
      earningCreditEvents: {
        ...creditEvents,
        [eventId]: {
          amountCentavos,
          recoveredCentavos,
          availableCreditCentavos: availableCredit,
          releasedPending: releasePending,
        },
      },
    };
    if (releasePending) next.pendingCentavos = pending - amountCentavos;
    return next;
  });
  if (!transaction.committed && !priorEvent) {
    const current = asRecord((await walletRef.get()).val());
    const saved = asRecord(asRecord(current.earningCreditEvents)[eventId]);
    if (Object.keys(saved).length === 0) {
      throw new HttpsError("aborted", "Wallet earnings release could not be committed.");
    }
    recoveredCentavos = Number(saved.recoveredCentavos ?? 0);
  }
  return { recoveredCentavos };
}

async function reconcileCompletedPaymentEarnings(
  entityType: "ORDER" | "TRIP",
  entityId: string,
  entity: Record<string, unknown>,
  payment: Record<string, unknown>
): Promise<boolean> {
  if (
    asString(payment.provider).toUpperCase() !== "XENDIT" ||
    !["PAID", "PARTIALLY_REFUNDED", "REFUNDED"].includes(asString(payment.status).toUpperCase()) ||
    asString(entity.paymentMethod).toUpperCase() !== "GCASH"
  ) return false;

  const amount = nonNegativeInteger(payment.amountCentavos, "settled payment amount");
  const breakdown = asRecord(payment.breakdown ?? entity.breakdown);
  const merchantShare = entityType === "ORDER" ? nonNegativeInteger(breakdown.merchantShare ?? 0, "merchant share") : 0;
  const courierShare = entityType === "ORDER" ? nonNegativeInteger(breakdown.courierShare ?? 0, "courier share") : 0;
  const driverShare = entityType === "TRIP" ? nonNegativeInteger(breakdown.driverShare ?? 0, "driver share") : 0;
  const platformShare = nonNegativeInteger(breakdown.platformShare ?? 0, "platform share");
  const totalShares = merchantShare + courierShare + driverShare + platformShare;
  if (!Number.isSafeInteger(totalShares) || totalShares !== amount) {
    throw new HttpsError("failed-precondition", "The paid transaction breakdown does not reconcile to its received amount.");
  }

  const netShares = calculateNetParticipantShares({
    grossAmountCentavos: amount,
    merchantCentavos: merchantShare,
    courierCentavos: courierShare,
    driverCentavos: driverShare,
    platformCentavos: platformShare,
    refunds: asRecord(payment.refunds) as Record<string, {
      amountCentavos?: unknown;
      status?: unknown;
      walletSettlementStatus?: unknown;
    }>,
  });
  const merchantId = asString(entity.merchantId) || asString(entity.sourceId);
  const courierId = asString(entity.deliveryId);
  const driverId = asString(entity.driverId);
  if (merchantShare > 0 && !merchantId) throw new HttpsError("failed-precondition", "The completed order has no merchant recipient.");
  if (courierShare > 0 && !courierId) return false;
  if (driverShare > 0 && !driverId) throw new HttpsError("failed-precondition", "The completed ride has no assigned driver recipient.");

  const complete = entityType === "ORDER"
    ? asString(entity.status).toUpperCase() === "DELIVERED"
    : asString(entity.status).toUpperCase() === "COMPLETED";
  const settlementConfirmed = ["SETTLED", "EARLY_SETTLED"].includes(
    asString(payment.settlementStatus).toUpperCase()
  );
  const availableFundsConfirmed = Number(payment.availableFundsConfirmedAtTimestamp ?? 0) > 0;
  const release = complete && settlementConfirmed && availableFundsConfirmed &&
    !netShares.refundReconciliationPending;
  const hasPending = asString(payment.earningsPendingStatus).toUpperCase() === "PENDING";

  if (!release && hasPending) {
    if (complete) {
      await db.ref("payments/" + entityId).update({
        earningsSettlementStatus: netShares.refundReconciliationPending
          ? "WAITING_FOR_REFUND_RECONCILIATION"
          : settlementConfirmed
            ? "WAITING_FOR_AVAILABLE_FUNDS"
            : "AWAITING_SETTLEMENT",
        ...(netShares.refundReconciliationPending ? { availableFundsConfirmedAtTimestamp: null } : {}),
        serviceCompletedAtTimestamp: Number(entity.completedAtTimestamp ?? entity.updatedAtTimestamp ?? Date.now()),
      });
    }
    return false;
  }

  const shares: Array<{ ownerId: string; accountType: string; amount: number }> = [];
  const releaseMerchantShare = release ? netShares.merchantCentavos : merchantShare;
  const releaseCourierShare = release ? netShares.courierCentavos : courierShare;
  const releaseDriverShare = release ? netShares.driverCentavos : driverShare;
  if (releaseMerchantShare > 0) shares.push({ ownerId: merchantId, accountType: "MERCHANT", amount: releaseMerchantShare });
  if (releaseCourierShare > 0) shares.push({ ownerId: courierId, accountType: "COURIER", amount: releaseCourierShare });
  if (releaseDriverShare > 0) shares.push({ ownerId: driverId, accountType: "DRIVER", amount: releaseDriverShare });
  const participantTotal = release
    ? releaseMerchantShare + releaseCourierShare + releaseDriverShare
    : merchantShare + courierShare + driverShare;
  const entryId = (release ? "earning_release_" : "earning_pending_") + entityType.toLowerCase() + "_" + entityId;
  const lines: LedgerLine[] = [];
  const recoveredByOwner = new Map<string, number>();

  if (release) {
    // Wallet balances become withdrawable only after Xendit confirms transaction settlement,
    // posted refunds are reconciled, and the current PHP cash balance has been checked.
    for (const share of shares) {
      const eventId = "earning_release_" + entryId + "_" + share.ownerId;
      const result = await applyWalletEarningCredit(share.ownerId, eventId, share.amount, hasPending);
      recoveredByOwner.set(share.ownerId, result.recoveredCentavos);
    }
    if (hasPending) {
      for (const share of shares) {
        lines.push(ledgerLine(share.accountType + "_PENDING_PAYABLE", share.ownerId, "DEBIT", share.amount));
        const recovered = recoveredByOwner.get(share.ownerId) ?? 0;
        const available = share.amount - recovered;
        if (available > 0) lines.push(ledgerLine(share.accountType + "_AVAILABLE_PAYABLE", share.ownerId, "CREDIT", available));
        if (recovered > 0) lines.push(ledgerLine("WALLET_REFUND_RECEIVABLE", share.ownerId, "CREDIT", recovered));
      }
    } else {
      lines.push(ledgerLine("XENDIT_GATEWAY_CLEARING", "PLATFORM", "DEBIT", netShares.netAmountCentavos));
      for (const share of shares) {
        const recovered = recoveredByOwner.get(share.ownerId) ?? 0;
        const available = share.amount - recovered;
        if (available > 0) lines.push(ledgerLine(share.accountType + "_AVAILABLE_PAYABLE", share.ownerId, "CREDIT", available));
        if (recovered > 0) lines.push(ledgerLine("WALLET_REFUND_RECEIVABLE", share.ownerId, "CREDIT", recovered));
      }
      if (netShares.platformCentavos > 0) {
        lines.push(ledgerLine("PLATFORM_COMMISSION_REVENUE", "PLATFORM", "CREDIT", netShares.platformCentavos));
      }
    }
  } else {
    // Keep gross participant shares pending; refund postings reduce these balances
    // before any later settlement release.
    lines.push(ledgerLine("XENDIT_GATEWAY_CLEARING", "PLATFORM", "DEBIT", amount));
    if (merchantShare > 0) lines.push(ledgerLine("MERCHANT_PENDING_PAYABLE", merchantId, "CREDIT", merchantShare));
    if (courierShare > 0) lines.push(ledgerLine("COURIER_PENDING_PAYABLE", courierId, "CREDIT", courierShare));
    if (driverShare > 0) lines.push(ledgerLine("DRIVER_PENDING_PAYABLE", driverId, "CREDIT", driverShare));
    if (platformShare > 0) lines.push(ledgerLine("PLATFORM_COMMISSION_REVENUE", "PLATFORM", "CREDIT", platformShare));
  }

  const journalTotal = release && hasPending
    ? participantTotal
    : release
      ? netShares.netAmountCentavos
      : amount;
  if (journalTotal <= 0) {
    if (release) {
      await db.ref("payments/" + entityId).update({
        earningsSettlementStatus: "RELEASED",
        earningsReleasedAtTimestamp: Date.now(),
      });
    }
    return true;
  }
  const entry = {
    operationId: (release ? "EARNING_RELEASE:" : "EARNING_PENDING:") + entityType + ":" + entityId,
    entryType: release ? "EARNING_RELEASE" : "EARNING_PENDING",
    entityType,
    entityId,
    paymentId: asString(payment.providerReference),
    totalCentavos: journalTotal,
    lines,
    memo: release
      ? "Move settled GCash earnings to available after refund and balance verification"
      : "Reserve paid GCash earnings pending service completion or Xendit settlement",
    createdAtTimestamp: Number(payment.earningsPendingAtTimestamp ?? payment.paidAtTimestamp ?? payment.updatedAtTimestamp ?? entity.createdAtTimestamp ?? 1),
  };
  await ensureMarketplaceLedgerEntry(entryId, entry);

  if (!release) {
    for (const share of shares) {
      await applyWalletDeltas(share.ownerId, "earning_pending_" + entryId + "_" + share.ownerId, { pendingCentavos: share.amount });
    }
    await db.ref("payments/" + entityId).update({
      earningsPendingStatus: "PENDING",
      earningsPendingLedgerEntryId: entryId,
      earningsSettlementStatus: complete
        ? netShares.refundReconciliationPending
          ? "WAITING_FOR_REFUND_RECONCILIATION"
          : settlementConfirmed
            ? "WAITING_FOR_AVAILABLE_FUNDS"
            : "AWAITING_SETTLEMENT"
        : "PENDING_SERVICE_COMPLETION",
      ...(complete ? {
        serviceCompletedAtTimestamp: Number(entity.completedAtTimestamp ?? entity.updatedAtTimestamp ?? Date.now()),
      } : {}),
    });
    await db.ref((entityType === "ORDER" ? "orders/" : "trips/") + entityId).update({
      earningsPendingStatus: "PENDING",
      earningsPendingLedgerEntryId: entryId,
      ...(complete ? {
        earningsSettlementStatus: netShares.refundReconciliationPending
          ? "WAITING_FOR_REFUND_RECONCILIATION"
          : settlementConfirmed
            ? "WAITING_FOR_AVAILABLE_FUNDS"
            : "AWAITING_SETTLEMENT",
        serviceCompletedAtTimestamp: Number(entity.completedAtTimestamp ?? entity.updatedAtTimestamp ?? Date.now()),
      } : {}),
    });
  } else {
    await db.ref("payments/" + entityId).update({
      earningsPendingStatus: hasPending ? "RELEASED" : asString(payment.earningsPendingStatus),
      earningsSettlementStatus: "RELEASED",
      earningsReleasedAtTimestamp: Date.now(),
      earningsLedgerEntryId: entryId,
    });
    await db.ref((entityType === "ORDER" ? "orders/" : "trips/") + entityId).update({
      earningsSettlementStatus: "RELEASED",
      earningsReleasedAtTimestamp: Date.now(),
      earningsLedgerEntryId: entryId,
    });
  }
  return true;
}

async function applyWalletRefundDebit(
  uid: string,
  eventId: string,
  sourceField: "pendingCentavos" | "availableCentavos",
  amountCentavos: number
): Promise<{ debitedCentavos: number; debtCentavos: number }> {
  const walletRef = db.ref("wallets/" + uid);
  let result: { debitedCentavos: number; debtCentavos: number } | null = null;
  const transaction = await walletRef.transaction((rawValue) => {
    const current = asRecord(rawValue);
    const applied = asRecord(current.appliedLedgerEntries);
    const refundAdjustments = asRecord(current.refundAdjustments);
    const previous = asRecord(refundAdjustments[eventId]);
    if (applied[eventId] === true || Object.keys(previous).length > 0) {
      result = {
        debitedCentavos: Number(previous.debitedCentavos ?? 0),
        debtCentavos: Number(previous.debtCentavos ?? 0),
      };
      return current;
    }
    const availableToDebit = walletNumber(current, sourceField);
    const debitedCentavos = Math.min(availableToDebit, amountCentavos);
    const debtCentavos = amountCentavos - debitedCentavos;
    const existingDebt = walletNumber(current, "refundDebtCentavos");
    if (!Number.isSafeInteger(existingDebt + debtCentavos)) {
      throw new HttpsError("data-loss", "Refund recovery balance exceeded the supported wallet range.");
    }
    result = { debitedCentavos, debtCentavos };
    return {
      ...current,
      [sourceField]: availableToDebit - debitedCentavos,
      refundDebtCentavos: existingDebt + debtCentavos,
      appliedLedgerEntries: { ...applied, [eventId]: true },
      refundAdjustments: {
        ...refundAdjustments,
        [eventId]: {
          sourceField,
          requestedCentavos: amountCentavos,
          debitedCentavos,
          debtCentavos,
          createdAtTimestamp: Date.now(),
        },
      },
      currency: "PHP",
      updatedAtTimestamp: Date.now(),
    };
  });
  if (!result) {
    const current = asRecord((await walletRef.get()).val());
    const saved = asRecord(asRecord(current.refundAdjustments)[eventId]);
    if (Object.keys(saved).length === 0) {
      throw new HttpsError("aborted", "Refund recovery could not be committed to the wallet.");
    }
    result = {
      debitedCentavos: Number(saved.debitedCentavos ?? 0),
      debtCentavos: Number(saved.debtCentavos ?? 0),
    };
  }
  if (!transaction.committed && !result) {
    throw new HttpsError("aborted", "Refund recovery transaction did not commit.");
  }
  return result;
}

async function reconcileRefundedPaymentEarnings(entityId: string, refundAttemptId: string): Promise<void> {
  const paymentRef = db.ref("payments/" + entityId);
  let payment = asRecord((await paymentRef.get()).val());
  let refund = asRecord(asRecord(payment.refunds)[refundAttemptId]);
  if (
    asString(payment.provider).toUpperCase() !== "XENDIT" ||
    asString(refund.status).toUpperCase() !== "SUCCEEDED"
  ) return;
  if (asString(refund.walletSettlementStatus).toUpperCase() === "POSTED") return;

  const entityType = asString(payment.entityType).toUpperCase() === "TRIP" ? "TRIP" : "ORDER";
  const entityPath = entityType === "TRIP" ? "trips/" : "orders/";
  const entitySnapshot = await db.ref(entityPath + entityId).get();
  if (!entitySnapshot.exists()) throw new HttpsError("not-found", "The refunded order or trip was not found.");
  const entity = asRecord(entitySnapshot.val());

  if (
    asString(payment.earningsPendingStatus).toUpperCase() !== "PENDING" &&
    asString(payment.earningsSettlementStatus).toUpperCase() !== "RELEASED"
  ) {
    await reconcileCompletedPaymentEarnings(entityType, entityId, entity, {
      ...payment,
      status: "PAID",
      suppressPayoutQueue: true,
    });
    payment = asRecord((await paymentRef.get()).val());
    refund = asRecord(asRecord(payment.refunds)[refundAttemptId]);
    if (asString(refund.walletSettlementStatus).toUpperCase() === "POSTED") return;
  }

  const refundAmount = nonNegativeInteger(refund.amountCentavos, "confirmed refund amount");
  const grossAmount = nonNegativeInteger(payment.amountCentavos, "original payment amount");
  if (refundAmount <= 0 || grossAmount <= 0 || refundAmount > grossAmount) {
    throw new HttpsError("data-loss", "The confirmed refund amount does not match the original payment.");
  }
  const breakdown = asRecord(payment.breakdown ?? entity.breakdown);
  const merchantShare = entityType === "ORDER" ? nonNegativeInteger(breakdown.merchantShare ?? 0, "merchant share") : 0;
  const courierShare = entityType === "ORDER" ? nonNegativeInteger(breakdown.courierShare ?? 0, "courier share") : 0;
  const driverShare = entityType === "TRIP" ? nonNegativeInteger(breakdown.driverShare ?? 0, "driver share") : 0;
  const platformShare = nonNegativeInteger(breakdown.platformShare ?? 0, "platform share");
  const originalShares = merchantShare + courierShare + driverShare + platformShare;
  if (originalShares !== grossAmount) throw new HttpsError("failed-precondition", "Original payment split does not reconcile.");

  const scaled = (share: number): number =>
    Number((BigInt(refundAmount) * BigInt(share)) / BigInt(grossAmount));
  const merchantRefund = scaled(merchantShare);
  const courierRefund = scaled(courierShare);
  const driverRefund = scaled(driverShare);
  const platformRefund = refundAmount - merchantRefund - courierRefund - driverRefund;
  const isPending = asString(payment.earningsPendingStatus).toUpperCase() === "PENDING";
  const sourceField: "pendingCentavos" | "availableCentavos" = isPending ? "pendingCentavos" : "availableCentavos";
  const recipients: Array<{ uid: string; type: string; amount: number }> = [];
  const merchantId = asString(entity.merchantId) || asString(entity.sourceId);
  const courierId = asString(entity.deliveryId);
  const driverId = asString(entity.driverId);
  if (merchantRefund > 0 && !merchantId) {
    throw new HttpsError("failed-precondition", "The refund cannot be reconciled because the original merchant recipient is missing.");
  }
  if (courierRefund > 0 && !courierId) {
    throw new HttpsError("failed-precondition", "The refund is waiting for the original courier assignment before it can be reconciled.");
  }
  if (driverRefund > 0 && !driverId) {
    throw new HttpsError("failed-precondition", "The refund cannot be reconciled because the original driver recipient is missing.");
  }
  if (merchantRefund > 0) recipients.push({ uid: merchantId, type: "MERCHANT", amount: merchantRefund });
  if (courierRefund > 0) recipients.push({ uid: courierId, type: "COURIER", amount: courierRefund });
  if (driverRefund > 0) recipients.push({ uid: driverId, type: "DRIVER", amount: driverRefund });

  const lines: LedgerLine[] = [];
  for (const recipient of recipients) {
    const walletResult = await applyWalletRefundDebit(
      recipient.uid,
      "refund_" + refundAttemptId + "_" + recipient.uid,
      sourceField,
      recipient.amount
    );
    if (walletResult.debitedCentavos > 0) {
      lines.push(ledgerLine(recipient.type + (isPending ? "_PENDING_PAYABLE" : "_AVAILABLE_PAYABLE"), recipient.uid, "DEBIT", walletResult.debitedCentavos));
    }
    if (walletResult.debtCentavos > 0) {
      lines.push(ledgerLine("WALLET_REFUND_RECEIVABLE", recipient.uid, "DEBIT", walletResult.debtCentavos));
    }
  }
  if (platformRefund > 0) lines.push(ledgerLine("PLATFORM_COMMISSION_REVENUE", "PLATFORM", "DEBIT", platformRefund));
  lines.push(ledgerLine("XENDIT_REFUND_CLEARING", "PLATFORM", "CREDIT", refundAmount));

  const entryId = "refund_settlement_" + entityType.toLowerCase() + "_" + entityId + "_" + refundAttemptId;
  const entry = {
    operationId: "REFUND_SETTLEMENT:" + entityType + ":" + entityId + ":" + refundAttemptId,
    entryType: "REFUND_SETTLEMENT",
    entityType,
    entityId,
    refundAttemptId,
    refundId: asString(refund.providerRefundId),
    totalCentavos: refundAmount,
    lines,
    memo: "Reconcile a provider-confirmed Xendit refund against participant earnings and commission",
    createdAtTimestamp: Number(refund.createdAtTimestamp ?? refund.updatedAtTimestamp ?? payment.updatedAtTimestamp ?? 1),
  };
  await ensureMarketplaceLedgerEntry(entryId, entry);
  await db.ref().update({
    ["payments/" + entityId + "/refunds/" + refundAttemptId + "/walletSettlementStatus"]: "POSTED",
    ["payments/" + entityId + "/refunds/" + refundAttemptId + "/walletLedgerEntryId"]: entryId,
    ["payments/" + entityId + "/refunds/" + refundAttemptId + "/walletSettledAtTimestamp"]: Date.now(),
  });
}

export const reconcileMarketplaceEarnings = onSchedule(
  {
    schedule: "every 5 minutes",
    timeZone: "Asia/Manila",
    region: REGION,
    secrets: [XENDIT_PAYOUT_SECRET_KEY],
  },
  async () => {
    const secret = XENDIT_PAYOUT_SECRET_KEY.value();
    let released = 0;
    let pending = 0;
    let failed = 0;
    const paymentSnapshots = await Promise.all(
      ["PAID", "PARTIALLY_REFUNDED", "REFUNDED"].map((status) =>
        db.ref("payments").orderByChild("status").equalTo(status).limitToLast(500).get()
      )
    );
    const paidRowsById = new Map<string, Record<string, unknown>>();
    for (const snapshot of paymentSnapshots) {
      snapshot.forEach((child) => {
        if (child.key) paidRowsById.set(child.key, asRecord(child.val()));
        return false;
      });
    }
    const paidRows = Array.from(paidRowsById.entries()).map(([id, value]) => ({ id, value }));

    for (const row of paidRows) {
      try {
        let payment = row.value;
        if (
          asString(payment.provider).toUpperCase() !== "XENDIT" ||
          asString(payment.channelCode).toUpperCase() !== "GCASH" ||
          asString(payment.earningsSettlementStatus).toUpperCase() === "RELEASED"
        ) continue;

        const entityType = asString(payment.entityType).toUpperCase() === "TRIP" ? "TRIP" : "ORDER";
        const entityPath = entityType === "TRIP" ? "trips/" : "orders/";
        const entitySnapshot = await db.ref(entityPath + row.id).get();
        if (!entitySnapshot.exists()) continue;
        const entity = asRecord(entitySnapshot.val());
        const complete = entityType === "ORDER"
          ? asString(entity.status).toUpperCase() === "DELIVERED"
          : asString(entity.status).toUpperCase() === "COMPLETED";

        let settlementConfirmed = ["SETTLED", "EARLY_SETTLED"].includes(
          asString(payment.settlementStatus).toUpperCase()
        );

        // Reserve participant earnings as pending as soon as payment is confirmed.
        // A completed service cannot release those earnings until the provider settlement
        // and available PHP balance have both been independently verified.
        if (!complete || !settlementConfirmed) {
          if (await reconcileCompletedPaymentEarnings(entityType, row.id, entity, payment)) pending++;
        }

        if (!settlementConfirmed) {
          if (!secret) continue;
          const matched = await findXenditGcashSettlement(payment, secret);
          if (!matched) {
            await db.ref("payments/" + row.id).update({
              settlementStatus: "PENDING",
              settlementLastCheckedAtTimestamp: Date.now(),
            });
            continue;
          }
          const transaction = matched.transaction;
          const providerSettlementStatus = asString(transaction.settlement_status).toUpperCase();
          settlementConfirmed = matched.settled;
          const estimatedAt = Date.parse(asString(transaction.estimated_settlement_time));
          const settlementFields = {
            settlementStatus: settlementConfirmed ? providerSettlementStatus : "PENDING",
            settlementTransactionId: asString(transaction.id),
            estimatedSettlementAtTimestamp: Number.isFinite(estimatedAt) ? estimatedAt : 0,
            settlementNetAmountCentavos: phpAmountToCentavos(transaction.net_amount) ?? 0,
            settlementLastCheckedAtTimestamp: Date.now(),
          };
          await db.ref("payments/" + row.id).update(settlementFields);
          payment = { ...payment, ...settlementFields };
        }

        if (!settlementConfirmed || !complete || !secret) continue;

        // Clear any previous one-time balance confirmation before rechecking. If a function
        // execution stops between the check and ledger release, the next run must check again.
        await db.ref("payments/" + row.id).update({
          availableFundsConfirmedAtTimestamp: null,
          earningsSettlementStatus: "WAITING_FOR_AVAILABLE_FUNDS",
        });
        payment = { ...payment, availableFundsConfirmedAtTimestamp: null };

        const participantTotal = participantEarningsCentavos(entityType, entity, payment);
        if (participantTotal > 0) {
          const [availableBalanceCentavos, walletLiabilitiesCentavos] = await Promise.all([
            getXenditAvailableBalanceCentavos(secret),
            sumWalletAvailableLiabilityCentavos(),
          ]);
          const requiredCentavos = walletLiabilitiesCentavos + participantTotal;
          if (!Number.isSafeInteger(requiredCentavos) || availableBalanceCentavos < requiredCentavos) {
            await db.ref("payments/" + row.id).update({
              earningsSettlementStatus: "WAITING_FOR_AVAILABLE_FUNDS",
              availableFundsConfirmedAtTimestamp: null,
              availableBalanceSnapshotCentavos: availableBalanceCentavos,
              availableBalanceCheckedAtTimestamp: Date.now(),
            });
            continue;
          }
          const confirmedAt = Date.now();
          await db.ref("payments/" + row.id).update({
            availableFundsConfirmedAtTimestamp: confirmedAt,
            availableBalanceSnapshotCentavos: availableBalanceCentavos,
            availableBalanceCheckedAtTimestamp: confirmedAt,
          });
          payment = {
            ...payment,
            availableFundsConfirmedAtTimestamp: confirmedAt,
            availableBalanceSnapshotCentavos: availableBalanceCentavos,
          };
        } else {
          const confirmedAt = Date.now();
          await db.ref("payments/" + row.id).update({
            availableFundsConfirmedAtTimestamp: confirmedAt,
            availableBalanceCheckedAtTimestamp: confirmedAt,
          });
          payment = { ...payment, availableFundsConfirmedAtTimestamp: confirmedAt };
        }

        if (await reconcileCompletedPaymentEarnings(entityType, row.id, entity, payment)) released++;
      } catch (error) {
        failed++;
        logger.error("Paid transaction settlement/earnings reconciliation failed", {
          entityId: row.id,
          error,
        });
      }
    }

    logger.info("Marketplace settlement and earnings reconciliation finished", {
      released,
      pending,
      failed,
    });
  }
);

function payoutStatusIsActive(status: string): boolean {
  return [
    "RESERVING",
    "SUBMITTING",
    "ACCEPTED",
    "PENDING_COMPLIANCE_ASSESSMENT",
    "ROUTING",
    "REQUESTED",
    "READY",
    "LOCKED",
    "RECONCILIATION_REQUIRED",
  ].includes(status.toUpperCase());
}

function normalizePayoutProviderStatus(value: string): string {
  const status = value.toUpperCase();
  const allowed = [
    "ACCEPTED",
    "PENDING_COMPLIANCE_ASSESSMENT",
    "REJECTED",
    "ROUTING",
    "REQUESTED",
    "READY",
    "LOCKED",
    "EXPIRED",
    "FAILED",
    "SUCCEEDED",
    "CANCELLED",
    "REVERSED",
  ];
  return allowed.includes(status) ? status : "RECONCILIATION_REQUIRED";
}

function payoutIntentId(uid: string, key: string): string {
  return createHash("sha256").update("LABMDA_PAYOUT|" + uid + "|" + key).digest("hex").slice(0, 32);
}

async function createPayoutProviderRequest(
  payoutId: string,
  secret: string
): Promise<void> {
  const payoutRef = db.ref("payouts/" + payoutId);
  const payout = asRecord((await payoutRef.get()).val());
  if (!payoutRef || !Object.keys(payout).length) return;
  if (asString(payout.providerPayoutId)) return;
  if (!["SUBMITTING", "RECONCILIATION_REQUIRED"].includes(asString(payout.status))) return;

  const uid = asString(payout.userId);
  const account = asRecord((await db.ref("payoutAccounts/" + uid).get()).val());
  if (asString(account.status).toUpperCase() !== "VERIFIED") {
    throw new HttpsError("failed-precondition", "The payout recipient account is not verified.");
  }
  const accountNumber = decryptPayoutAccountNumber(account);
  const idempotencyKey = asString(payout.providerIdempotencyKey);
  if (!idempotencyKey) throw new HttpsError("data-loss", "Payout idempotency reference is missing.");

  const requestBody = {
    reference_id: "labmda_payout_" + payoutId,
    recipient: {
      type: "INDIVIDUAL",
      given_name: asString(account.givenName),
      surname: asString(account.surname),
      relationship: "CUSTOMER",
      details: {
        personal_mobile_number: asString(account.personalMobileNumber),
      },
      address: {
        country: "PH",
        street_line_1: asString(account.streetLine1),
        city: asString(account.city),
        province_state: asString(account.provinceState),
        postal_code: asString(account.postalCode),
      },
      account_details: {
        currency: "PHP",
        account_country: "PH",
        account_holder_name: asString(account.accountHolderName),
        account_number: accountNumber,
        routing_type_1: "WALLET",
        routing_value_1: "PH_GCASH",
      },
    },
    payout_details: {
      source_currency: "PHP",
      source_amount: nonNegativeInteger(payout.amountCentavos, "payout amount"),
      destination_currency: "PHP",
    },
    source_of_fund: "BUSINESS_REVENUE",
    purpose_code: "SALARY",
    description: ("Labmda " + asString(payout.earningType) + " earnings").slice(0, 100),
  };

  let response: Response;
  let providerBody: Record<string, unknown>;
  try {
    response = await fetch("https://api.xendit.co/v3/payouts", {
      method: "POST",
      headers: {
        Authorization: "Basic " + Buffer.from(secret + ":").toString("base64"),
        "Content-Type": "application/json",
        "api-version": "2025-09-01",
        "Idempotency-key": idempotencyKey,
      },
      body: JSON.stringify(requestBody),
    });
    providerBody = asRecord(await response.json().catch(() => ({})));
  } catch (error) {
    logger.warn("Xendit payout request response is uncertain; it will be retried idempotently", {
      payoutId,
      error,
    });
    await payoutRef.update({
      status: "RECONCILIATION_REQUIRED",
      lastError: "Provider response was not received; retry will use the same idempotency key.",
      updatedAtTimestamp: Date.now(),
    });
    await db.ref("payoutReconciliationQueue/" + payoutId).set({
      payoutId,
      dueAtTimestamp: Date.now() + PAYOUT_RECONCILIATION_MS,
      attempts: Number(payout.attempts ?? 0) + 1,
    });
    return;
  }

  if (!response.ok) {
    const failureCode = asString(providerBody.error_code) || "XENDIT_HTTP_" + response.status;
    logger.error("Xendit payout creation failed", { payoutId, status: response.status, providerBody });
    // 5xx/429 and DUPLICATE_ERROR may represent an unknown create result. Keep the
    // reservation and retry the identical body with the identical provider key.
    if (response.status >= 500 || response.status === 429 || failureCode === "DUPLICATE_ERROR") {
      await payoutRef.update({
        status: "RECONCILIATION_REQUIRED",
        failureCode,
        lastError: "Provider response is uncertain; the same idempotency key will be reconciled.",
        updatedAtTimestamp: Date.now(),
      });
      await db.ref("payoutReconciliationQueue/" + payoutId).set({
        payoutId,
        dueAtTimestamp: Date.now() + PAYOUT_RECONCILIATION_MS,
        attempts: Number(payout.attempts ?? 0) + 1,
      });
      return;
    }
    await applyPayoutProviderStatus({
      payoutId,
      providerPayoutId: "",
      referenceId: asString(providerBody.reference_id) || "labmda_payout_" + payoutId,
      status: "FAILED",
      sourceAmount: nonNegativeInteger(payout.amountCentavos, "payout amount"),
      failureCode,
      providerUpdatedAt: new Date().toISOString(),
    });
    return;
  }

  const providerPayoutId = asString(providerBody.payout_id);
  const referenceId = asString(providerBody.reference_id);
  const sourceAmount = nonNegativeInteger(providerBody.source_amount, "provider payout source amount");
  if (
    !providerPayoutId.startsWith("po-") ||
    referenceId !== "labmda_payout_" + payoutId ||
    sourceAmount !== Number(payout.amountCentavos) ||
    asString(providerBody.source_currency).toUpperCase() !== "PHP"
  ) {
    logger.error("Xendit payout response did not match the reserved payout", { payoutId, providerBody });
    await payoutRef.update({
      status: "RECONCILIATION_REQUIRED",
      lastError: "Provider response could not be matched to the reserved payout.",
      updatedAtTimestamp: Date.now(),
    });
    await db.ref("payoutReconciliationQueue/" + payoutId).set({
      payoutId,
      dueAtTimestamp: Date.now() + PAYOUT_RECONCILIATION_MS,
      attempts: Number(payout.attempts ?? 0) + 1,
    });
    return;
  }
  await applyPayoutProviderStatus({
    payoutId,
    providerPayoutId,
    referenceId,
    status: normalizePayoutProviderStatus(asString(providerBody.status)),
    sourceAmount,
    failureCode: asString(providerBody.failure_code),
    providerUpdatedAt: asString(providerBody.updated) || asString(providerBody.created),
  });
}

async function applyPayoutProviderStatus(args: {
  payoutId: string;
  providerPayoutId: string;
  referenceId: string;
  status: string;
  sourceAmount: number;
  failureCode?: string;
  providerUpdatedAt?: string;
}): Promise<void> {
  const payoutRef = db.ref("payouts/" + args.payoutId);
  let payoutError = "";
  const transaction = await payoutRef.transaction((rawValue) => {
    const current = asRecord(rawValue);
    if (!Object.keys(current).length) return;
    const expectedReference = "labmda_payout_" + args.payoutId;
    if (
      asString(current.referenceId) !== expectedReference ||
      args.referenceId !== expectedReference ||
      Number(current.amountCentavos) !== args.sourceAmount ||
      (asString(current.providerPayoutId) &&
        args.providerPayoutId &&
        asString(current.providerPayoutId) !== args.providerPayoutId)
    ) {
      payoutError = "Provider payout reference or amount did not match the server reservation.";
      return;
    }
    const currentStatus = asString(current.status).toUpperCase();
    const incomingStatus = normalizePayoutProviderStatus(args.status);
    if (currentStatus === "SUCCEEDED" && incomingStatus !== "REVERSED") return current;
    if (["FAILED", "REJECTED", "CANCELLED", "EXPIRED"].includes(currentStatus) &&
        !["SUCCEEDED", "REVERSED"].includes(incomingStatus)) return current;
    return {
      ...current,
      providerPayoutId: args.providerPayoutId || asString(current.providerPayoutId),
      providerStatus: incomingStatus,
      status: incomingStatus,
      failureCode: args.failureCode || asString(current.failureCode),
      providerUpdatedAt: args.providerUpdatedAt || asString(current.providerUpdatedAt),
      updatedAtTimestamp: Date.now(),
    };
  });
  if (payoutError) throw new HttpsError("data-loss", payoutError);
  if (!transaction.committed) {
    const current = asRecord((await payoutRef.get()).val());
    if (!Object.keys(current).length) throw new HttpsError("not-found", "Payout reservation not found.");
    const savedStatus = asString(current.status).toUpperCase();
    if (savedStatus !== normalizePayoutProviderStatus(args.status) &&
        !(savedStatus === "SUCCEEDED" && args.status === "SUCCEEDED")) return;
  }
  const payout = asRecord((await payoutRef.get()).val());
  const status = asString(payout.status).toUpperCase();
  const uid = asString(payout.userId);
  const amount = nonNegativeInteger(payout.amountCentavos, "payout amount");
  const entryPrefix = "payout_" + args.payoutId;

  if (status === "SUCCEEDED") {
    const walletResult = await applyPayoutWalletTerminal(uid, args.payoutId, amount, "SUCCEEDED");
    const entryId = entryPrefix + "_success";
    const entry = {
      operationId: "PAYOUT_SUCCESS:" + args.payoutId,
      entryType: "PAYOUT_SUCCESS",
      payoutId: args.payoutId,
      userId: uid,
      totalCentavos: amount,
      lines: [
        ledgerLine("WALLET_PAYOUT_PENDING", uid, "DEBIT", amount),
        ledgerLine("XENDIT_PAYOUT_CLEARING", "PLATFORM", "CREDIT", amount),
      ],
      memo: "Confirmed Xendit payout",
      createdAtTimestamp: Number(payout.createdAtTimestamp ?? 1),
    };
    await ensureMarketplaceLedgerEntry(entryId, entry);
    await payoutRef.update({ ledgerEntryId: entryId, successLedgerEntryId: entryId, refundRecoveredCentavos: walletResult.refundRecoveredCentavos, completedAtTimestamp: Date.now() });
    await db.ref("payoutReconciliationQueue/" + args.payoutId).remove();
  } else if (status === "FAILED" || status === "REJECTED" || status === "CANCELLED" || status === "EXPIRED") {
    const walletResult = await applyPayoutWalletTerminal(uid, args.payoutId, amount, "FAILED");
    const availableAfterRecovery = amount - walletResult.refundRecoveredCentavos;
    const entryId = entryPrefix + "_failed";
    const entry = {
      operationId: "PAYOUT_FAILED:" + args.payoutId,
      entryType: "PAYOUT_FAILED",
      payoutId: args.payoutId,
      userId: uid,
      totalCentavos: amount,
      lines: [
        ledgerLine("WALLET_PAYOUT_PENDING", uid, "DEBIT", amount),
        ...(availableAfterRecovery > 0 ? [ledgerLine("WALLET_AVAILABLE_PAYABLE", uid, "CREDIT", availableAfterRecovery)] : []),
        ...(walletResult.refundRecoveredCentavos > 0 ? [ledgerLine("WALLET_REFUND_RECEIVABLE", uid, "CREDIT", walletResult.refundRecoveredCentavos)] : []),
      ],
      memo: "Return reserved wallet funds after a failed payout",
      createdAtTimestamp: Number(payout.createdAtTimestamp ?? 1),
    };
    await ensureMarketplaceLedgerEntry(entryId, entry);
    await payoutRef.update({ ledgerEntryId: entryId, failureLedgerEntryId: entryId, refundRecoveredCentavos: walletResult.refundRecoveredCentavos, completedAtTimestamp: Date.now() });
    await db.ref("payoutReconciliationQueue/" + args.payoutId).remove();
  } else if (status === "REVERSED") {
    const walletResult = await applyPayoutWalletTerminal(uid, args.payoutId, amount, "REVERSED");
    const availableAfterRecovery = amount - walletResult.refundRecoveredCentavos;
    const entryId = entryPrefix + "_reversed";
    const entry = {
      operationId: "PAYOUT_REVERSED:" + args.payoutId,
      entryType: "PAYOUT_REVERSED",
      payoutId: args.payoutId,
      userId: uid,
      totalCentavos: amount,
      lines: [
        ledgerLine("XENDIT_PAYOUT_CLEARING", "PLATFORM", "DEBIT", amount),
        ...(availableAfterRecovery > 0 ? [ledgerLine("WALLET_AVAILABLE_PAYABLE", uid, "CREDIT", availableAfterRecovery)] : []),
        ...(walletResult.refundRecoveredCentavos > 0 ? [ledgerLine("WALLET_REFUND_RECEIVABLE", uid, "CREDIT", walletResult.refundRecoveredCentavos)] : []),
      ],
      memo: "Restore a payout returned by the recipient bank or wallet",
      createdAtTimestamp: Number(payout.createdAtTimestamp ?? 1),
    };
    await ensureMarketplaceLedgerEntry(entryId, entry);
    await payoutRef.update({ ledgerEntryId: entryId, reversalLedgerEntryId: entryId, refundRecoveredCentavos: walletResult.refundRecoveredCentavos, reversedAtTimestamp: Date.now() });
    await db.ref("payoutReconciliationQueue/" + args.payoutId).remove();
  } else {
    await db.ref("payoutReconciliationQueue/" + args.payoutId).set({
      payoutId: args.payoutId,
      dueAtTimestamp: Date.now() + PAYOUT_RECONCILIATION_MS,
      attempts: 0,
    });
  }

  const payoutRequestId = asString(payout.payoutRequestId);
  if (payoutRequestId) {
    const requestStatus = status === "SUCCEEDED"
      ? "PAID"
      : ["FAILED", "REJECTED", "CANCELLED", "EXPIRED"].includes(status)
        ? "FAILED"
        : status === "REVERSED"
          ? "REVERSED"
          : "PROCESSING";
    await db.ref("payoutRequests/" + payoutRequestId).update({
      status: requestStatus,
      payoutId: args.payoutId,
      providerPayoutId: args.providerPayoutId,
      providerStatus: status,
      updatedAtTimestamp: Date.now(),
    });
  }
}

async function reserveAndSubmitPayout(
  uid: string,
  requestKey: string,
  automatic: boolean,
  requestedAmountCentavos?: number,
  payoutRequestId?: string
): Promise<Record<string, unknown>> {
  const payoutId = payoutIntentId(uid, requestKey);
  const payoutRef = db.ref("payouts/" + payoutId);
  const existing = asRecord((await payoutRef.get()).val());
  if (Object.keys(existing).length) {
    if (asString(existing.userId) !== uid) throw new HttpsError("already-exists", "This payout key belongs to another user.");
    const secret = XENDIT_PAYOUT_SECRET_KEY.value();
    if (!asString(existing.providerPayoutId) && ["SUBMITTING", "RECONCILIATION_REQUIRED"].includes(asString(existing.status))) {
      if (!secret) throw new HttpsError("failed-precondition", "Xendit money-out credentials are not configured.");
      await createPayoutProviderRequest(payoutId, secret);
    }
    return asRecord((await payoutRef.get()).val());
  }

  const account = asRecord((await db.ref("payoutAccounts/" + uid).get()).val());
  if (asString(account.status).toUpperCase() !== "VERIFIED") {
    throw new HttpsError("failed-precondition", "Set up a payout account and have it verified before requesting a payout.");
  }
  const secret = XENDIT_PAYOUT_SECRET_KEY.value();
  if (!secret) throw new HttpsError("failed-precondition", "Xendit money-out credentials are not configured.");
  const walletRef = db.ref("wallets/" + uid);
  const wallet = asRecord((await walletRef.get()).val());
  const availableAmount = walletNumber(wallet, "availableCentavos");
  const amount = requestedAmountCentavos ?? availableAmount;
  if (!Number.isSafeInteger(amount) || amount <= 0 || amount > availableAmount) {
    throw new HttpsError("failed-precondition", "Available earnings are lower than the requested payout amount.");
  }
  const xenditAvailableBalanceCentavos = await getXenditAvailableBalanceCentavos(secret);
  if (xenditAvailableBalanceCentavos < amount) {
    throw new HttpsError(
      "failed-precondition",
      "Xendit's available PHP balance is not sufficient for this payout. The request remains unsubmitted."
    );
  }
  if (walletNumber(wallet, "refundDebtCentavos") > 0) {
    throw new HttpsError("failed-precondition", "Outstanding refund recovery must be cleared from future earnings before another payout.");
  }
  const minimum = MANUAL_PAYOUT_MINIMUM_CENTAVOS;
  if (amount < minimum) {
    throw new HttpsError(
      "failed-precondition",
      "Available earnings must reach PHP " + phpFromCents(minimum).toFixed(2) + " before payout."
    );
  }
  if (asString(wallet.activePayoutId)) {
    throw new HttpsError("already-exists", "A payout is already being processed for this wallet.");
  }

  const timestamp = Date.now();
  const providerIdempotencyKey = "labmda_payout_" + payoutId;
  const payout = {
    id: payoutId,
    userId: uid,
    earningType: "MARKETPLACE",
    amountCentavos: amount,
    amount: phpFromCents(amount),
    currency: "PHP",
    status: "RESERVING",
    provider: "XENDIT",
    providerIdempotencyKey,
    referenceId: "labmda_payout_" + payoutId,
    payoutAccountStatus: "VERIFIED",
    accountChannelCode: asString(account.channelCode),
    maskedAccountNumber: asString(account.maskedAccountNumber),
    automatic,
    payoutRequestId: payoutRequestId || null,
    attempts: 0,
    createdAtTimestamp: timestamp,
    updatedAtTimestamp: timestamp,
  };
  const createTransaction = await payoutRef.transaction((currentValue) => {
    if (currentValue !== null && currentValue !== undefined) return;
    return payout;
  });
  if (!createTransaction.committed) {
    const saved = asRecord((await payoutRef.get()).val());
    if (asString(saved.userId) !== uid) throw new HttpsError("already-exists", "This payout key is already in use.");
    return saved;
  }

  let reserveError = "";
  const reserve = await walletRef.transaction((rawValue) => {
    const current = asRecord(rawValue);
    if (asString(current.activePayoutId)) {
      reserveError = "A payout is already being processed for this wallet.";
      return;
    }
    const available = walletNumber(current, "availableCentavos");
    if (available < amount) {
      reserveError = "Wallet balance changed and is now lower than the requested payout. Please retry.";
      return;
    }
    const activeRequestId = asString(current.activePayoutRequestId);
    if (activeRequestId && activeRequestId !== (payoutRequestId || "")) {
      reserveError = "A different payout request is awaiting administrator approval.";
      return;
    }
    const pending = walletNumber(current, "pendingPayoutCentavos");
    const applied = asRecord(current.appliedLedgerEntries);
    const nextWallet: Record<string, unknown> = {
      ...current,
      availableCentavos: available - amount,
      pendingPayoutCentavos: pending + amount,
      activePayoutId: payoutId,
      appliedLedgerEntries: { ...applied, ["payout_reserve_" + payoutId]: true },
      currency: "PHP",
      updatedAtTimestamp: Date.now(),
    };
    if (payoutRequestId && asString(nextWallet.activePayoutRequestId) === payoutRequestId) {
      delete nextWallet.activePayoutRequestId;
    }
    return nextWallet;
  });
  if (!reserve.committed) {
    await payoutRef.update({
      status: "CANCELLED",
      failureCode: reserveError || "WALLET_RESERVATION_FAILED",
      updatedAtTimestamp: Date.now(),
    });
    throw new HttpsError("failed-precondition", reserveError || "Wallet balance could not be reserved.");
  }

  const reserveLedger = {
    operationId: "PAYOUT_RESERVE:" + payoutId,
    entryType: "PAYOUT_RESERVE",
    payoutId,
    userId: uid,
    totalCentavos: amount,
    lines: [
      ledgerLine("WALLET_AVAILABLE_PAYABLE", uid, "DEBIT", amount),
      ledgerLine("WALLET_PAYOUT_PENDING", uid, "CREDIT", amount),
    ],
    memo: automatic ? "Automatically reserve available earnings for payout" : "Administrator-approved manual earnings payout",
    createdAtTimestamp: timestamp,
  };
  await ensureMarketplaceLedgerEntry("payout_" + payoutId + "_reserve", reserveLedger);
  await payoutRef.update({
    status: "SUBMITTING",
    reserveLedgerEntryId: "payout_" + payoutId + "_reserve",
    updatedAtTimestamp: Date.now(),
  });
  await db.ref("payoutReconciliationQueue/" + payoutId).set({
    payoutId,
    dueAtTimestamp: Date.now() + PAYOUT_RECONCILIATION_MS,
    attempts: 0,
  });
  await createPayoutProviderRequest(payoutId, secret);
  return asRecord((await payoutRef.get()).val());
}

export const getMyWallet = onCall(
  { region: REGION },
  async (request) => {
    const uid = requireUid(request);
    const wallet = asRecord((await db.ref("wallets/" + uid).get()).val());
    const account = asRecord((await db.ref("payoutAccounts/" + uid).get()).val());
    const activeRequestId = asString(wallet.activePayoutRequestId);
    const activePayoutRequestSnapshot = activeRequestId
      ? await db.ref("payoutRequests/" + activeRequestId).get()
      : null;
    const activePayoutRequest = activePayoutRequestSnapshot?.exists()
      ? asRecord(activePayoutRequestSnapshot.val())
      : null;
    const payoutRequestSnapshot = await db.ref("payoutRequests").orderByChild("userId").equalTo(uid).limitToLast(25).get();
    const payoutRequests: Record<string, unknown>[] = [];
    payoutRequestSnapshot.forEach((child) => {
      if (child.exists()) {
        const payoutRequest = asRecord(child.val());
        payoutRequests.push({
          id: child.key,
          amountCentavos: Number(payoutRequest.amountCentavos ?? 0),
          status: asString(payoutRequest.status),
          providerStatus: asString(payoutRequest.providerStatus),
          createdAtTimestamp: Number(payoutRequest.createdAtTimestamp ?? 0),
          updatedAtTimestamp: Number(payoutRequest.updatedAtTimestamp ?? 0),
          maskedAccountNumber: asString(payoutRequest.maskedAccountNumber),
          rejectionNote: asString(payoutRequest.rejectionNote),
          payoutId: asString(payoutRequest.payoutId),
        });
      }
      return false;
    });
    const payoutSnapshot = await db.ref("payouts").orderByChild("userId").equalTo(uid).limitToLast(25).get();
    const payouts: Record<string, unknown>[] = [];
    payoutSnapshot.forEach((child) => {
      if (child.exists()) {
        const payout = asRecord(child.val());
        payouts.push({
          id: child.key,
          amountCentavos: Number(payout.amountCentavos ?? 0),
          status: asString(payout.status),
          provider: asString(payout.provider),
          providerPayoutId: asString(payout.providerPayoutId),
          failureCode: asString(payout.failureCode),
          failureMessage: asString(payout.failureMessage) || asString(payout.failureReason),
          createdAtTimestamp: Number(payout.createdAtTimestamp ?? 0),
          updatedAtTimestamp: Number(payout.updatedAtTimestamp ?? 0),
          maskedAccountNumber: asString(payout.maskedAccountNumber),
        });
      }
      return false;
    });
    return {
      userId: uid,
      currency: "PHP",
      availableCentavos: walletNumber(wallet, "availableCentavos"),
      pendingCentavos: walletNumber(wallet, "pendingCentavos"),
      cashOwedCentavos: walletNumber(wallet, "cashOwedCentavos"),
      cashEarningsCentavos: walletNumber(wallet, "cashEarningsCentavos"),
      pendingPayoutCentavos: walletNumber(wallet, "pendingPayoutCentavos"),
      paidOutCentavos: walletNumber(wallet, "paidOutCentavos"),
      refundDebtCentavos: walletNumber(wallet, "refundDebtCentavos"),
      payoutAccount: Object.keys(account).length ? payoutAccountSummary(account) : { status: "NOT_CONFIGURED" },
      activePayoutRequest: activePayoutRequest ? {
        id: asString(activePayoutRequest.id),
        amountCentavos: Number(activePayoutRequest.amountCentavos ?? 0),
        status: asString(activePayoutRequest.status),
        createdAtTimestamp: Number(activePayoutRequest.createdAtTimestamp ?? 0),
        updatedAtTimestamp: Number(activePayoutRequest.updatedAtTimestamp ?? 0),
      } : null,
      payoutRequests: payoutRequests.sort((a, b) => Number(b.createdAtTimestamp) - Number(a.createdAtTimestamp)),
      payouts: payouts.sort((a, b) => Number(b.createdAtTimestamp) - Number(a.createdAtTimestamp)),
    };
  }
);

export const getMyPayoutAccount = onCall(
  { region: REGION },
  async (request) => {
    const uid = requireUid(request);
    const account = asRecord((await db.ref("payoutAccounts/" + uid).get()).val());
    return Object.keys(account).length ? payoutAccountSummary(account) : { status: "NOT_CONFIGURED" };
  }
);

export const savePayoutAccount = onCall(
  { region: REGION, secrets: [PAYOUT_ACCOUNT_ENCRYPTION_KEY] },
  async (request) => {
    const uid = requireUid(request);
    const data = asRecord(request.data);
    const givenName = asString(data.givenName);
    const surname = asString(data.surname);
    const accountHolderName = asString(data.accountHolderName);
    const accountNumber = asString(data.accountNumber).replace(/\s+/g, "");
    const phone = normalizePhilippineMobile(asString(data.personalMobileNumber));
    const streetLine1 = asString(data.streetLine1);
    const city = asString(data.city);
    const provinceState = asString(data.provinceState);
    const postalCode = asString(data.postalCode);
    if (!givenName || !surname || !accountHolderName) {
      throw new HttpsError("invalid-argument", "Enter the recipient's first name, last name and account holder name.");
    }
    if (!/^\d{10,13}$/.test(accountNumber)) {
      throw new HttpsError("invalid-argument", "Enter a valid GCash mobile/account number.");
    }
    if (!streetLine1 || !city || !provinceState || !/^[A-Za-z0-9 -]{3,12}$/.test(postalCode)) {
      throw new HttpsError("invalid-argument", "Enter the recipient's Philippine street, city, province and postal code.");
    }
    const wallet = asRecord((await db.ref("wallets/" + uid).get()).val());
    if (asString(wallet.activePayoutId) || asString(wallet.activePayoutRequestId)) {
      throw new HttpsError("failed-precondition", "Payout account details cannot be changed while a payout is pending or processing.");
    }
    const encrypted = encryptPayoutAccountNumber(accountNumber);
    const timestamp = Date.now();
    const record = {
      userId: uid,
      accountType: "GCASH",
      channelCode: "PH_GCASH",
      accountHolderName: accountHolderName.slice(0, 255),
      givenName: givenName.slice(0, 50),
      surname: surname.slice(0, 50),
      personalMobileNumber: phone,
      streetLine1: streetLine1.slice(0, 255),
      city: city.slice(0, 255),
      provinceState: provinceState.slice(0, 255),
      postalCode: postalCode.slice(0, 30),
      accountNumberCiphertext: encrypted.ciphertext,
      accountNumberIv: encrypted.iv,
      accountNumberTag: encrypted.tag,
      maskedAccountNumber: maskAccountNumber(accountNumber),
      status: "PENDING_REVIEW",
      submittedAtTimestamp: timestamp,
      updatedAtTimestamp: timestamp,
    };
    await db.ref("payoutAccounts/" + uid).set(record);
    await db.ref("payoutAccountReviews/" + uid).set({
      userId: uid,
      accountType: "GCASH",
      channelCode: "PH_GCASH",
      accountHolderName: record.accountHolderName,
      maskedAccountNumber: record.maskedAccountNumber,
      status: "PENDING_REVIEW",
      submittedAtTimestamp: timestamp,
      updatedAtTimestamp: timestamp,
    });
    return payoutAccountSummary(record);
  }
);

/**
 * A participant requests a manual cashout. This reserves the request slot, not the
 * money: available earnings remain visible until an administrator approves it.
 */
export const requestPayout = onCall(
  { region: REGION },
  async (request) => {
    const uid = requireUid(request);
    await requireApprovedEarningsRole(uid);
    const data = asRecord(request.data);
    const requestKey = parseIdempotencyKey(data.idempotencyKey);
    const requestId = payoutIntentId(uid, requestKey);
    const requestRef = db.ref("payoutRequests/" + requestId);
    const existingSnapshot = await requestRef.get();
    if (existingSnapshot.exists()) {
      const existing = asRecord(existingSnapshot.val());
      if (asString(existing.userId) !== uid) {
        throw new HttpsError("already-exists", "This payout request key belongs to another account.");
      }
      return {
        payoutRequestId: requestId,
        status: asString(existing.status),
        amountCentavos: Number(existing.amountCentavos ?? 0),
        maskedAccountNumber: asString(existing.maskedAccountNumber),
        reused: true,
      };
    }

    const account = asRecord((await db.ref("payoutAccounts/" + uid).get()).val());
    if (asString(account.status).toUpperCase() !== "VERIFIED") {
      throw new HttpsError("failed-precondition", "Set up a payout account and have it verified before requesting a payout.");
    }

    const walletRef = db.ref("wallets/" + uid);
    let reservationError = "";
    let requestedAmountCentavos = 0;
    const lock = await walletRef.transaction((rawValue) => {
      const wallet = asRecord(rawValue);
      if (asString(wallet.activePayoutId)) {
        reservationError = "A payout is already being processed for this wallet.";
        return;
      }
      const activeRequest = asString(wallet.activePayoutRequestId);
      if (activeRequest && activeRequest !== requestId) {
        reservationError = "You already have a payout request awaiting administrator review.";
        return;
      }
      if (walletNumber(wallet, "refundDebtCentavos") > 0) {
        reservationError = "Outstanding refund recovery must be cleared before requesting another payout.";
        return;
      }
      const available = walletNumber(wallet, "availableCentavos");
      if (available < MANUAL_PAYOUT_MINIMUM_CENTAVOS) {
        reservationError = "Available earnings must reach PHP " +
          phpFromCents(MANUAL_PAYOUT_MINIMUM_CENTAVOS).toFixed(2) + " before requesting a payout.";
        return;
      }
      requestedAmountCentavos = available;
      return {
        ...wallet,
        activePayoutRequestId: requestId,
        currency: "PHP",
        updatedAtTimestamp: Date.now(),
      };
    });
    if (!lock.committed) {
      throw new HttpsError("failed-precondition", reservationError || "A payout request could not be created.");
    }

    const timestamp = Date.now();
    const payoutRequest = {
      id: requestId,
      userId: uid,
      requestKey,
      amountCentavos: requestedAmountCentavos,
      amount: phpFromCents(requestedAmountCentavos),
      currency: "PHP",
      status: "PENDING_ADMIN_APPROVAL",
      provider: "XENDIT",
      maskedAccountNumber: asString(account.maskedAccountNumber),
      accountChannelCode: asString(account.channelCode),
      createdAtTimestamp: timestamp,
      updatedAtTimestamp: timestamp,
    };
    const created = await requestRef.transaction((currentValue) => {
      if (currentValue !== null && currentValue !== undefined) return;
      return payoutRequest;
    });
    if (!created.committed) {
      const saved = asRecord((await requestRef.get()).val());
      if (asString(saved.userId) === uid) {
        return {
          payoutRequestId: requestId,
          status: asString(saved.status),
          amountCentavos: Number(saved.amountCentavos ?? 0),
          maskedAccountNumber: asString(saved.maskedAccountNumber),
          reused: true,
        };
      }
      await walletRef.transaction((rawValue) => {
        const wallet = asRecord(rawValue);
        if (asString(wallet.activePayoutRequestId) !== requestId) return;
        const next = { ...wallet };
        delete next.activePayoutRequestId;
        return next;
      });
      throw new HttpsError("already-exists", "This payout request key is already in use.");
    }

    return {
      payoutRequestId: requestId,
      status: "PENDING_ADMIN_APPROVAL",
      amountCentavos: requestedAmountCentavos,
      maskedAccountNumber: asString(account.maskedAccountNumber),
      reused: false,
    };
  }
);

/** Only an administrator can approve a pending request and initiate its Xendit payout. */
export const adminApprovePayoutRequest = onCall(
  { region: REGION, secrets: [XENDIT_PAYOUT_SECRET_KEY, PAYOUT_ACCOUNT_ENCRYPTION_KEY] },
  async (request) => {
    const adminUid = requireAdmin(request);
    const requestId = asString(asRecord(request.data).payoutRequestId);
    if (!/^[a-f0-9]{32}$/i.test(requestId)) {
      throw new HttpsError("invalid-argument", "A valid payout request ID is required.");
    }
    const requestRef = db.ref("payoutRequests/" + requestId);
    const requestSnapshot = await requestRef.get();
    if (!requestSnapshot.exists()) throw new HttpsError("not-found", "The payout request was not found.");
    const payoutRequest = asRecord(requestSnapshot.val());
    const uid = asString(payoutRequest.userId);
    const requestKey = asString(payoutRequest.requestKey);
    const amountCentavos = nonNegativeInteger(payoutRequest.amountCentavos, "requested payout amount");
    if (!uid || !requestKey || amountCentavos <= 0) {
      throw new HttpsError("data-loss", "The payout request is missing its accounting details.");
    }
    if (["PROCESSING", "PAID"].includes(asString(payoutRequest.status))) {
      const existingPayout = asRecord((await db.ref("payouts/" + requestId).get()).val());
      return {
        payoutRequestId: requestId,
        payoutId: requestId,
        status: asString(payoutRequest.status),
        payoutStatus: asString(existingPayout.status),
        amountCentavos,
        reused: true,
      };
    }
    if (asString(payoutRequest.status) !== "PENDING_ADMIN_APPROVAL" &&
        asString(payoutRequest.status) !== "APPROVING") {
      throw new HttpsError("failed-precondition", "This payout request is no longer awaiting approval.");
    }

    await requireApprovedEarningsRole(uid);
    const wallet = asRecord((await db.ref("wallets/" + uid).get()).val());
    if (asString(wallet.activePayoutRequestId) !== requestId) {
      throw new HttpsError("failed-precondition", "The payout request no longer holds the wallet request lock.");
    }
    let approvalClaimError = "";
    const approvalClaim = await requestRef.transaction((rawValue) => {
      const current = asRecord(rawValue);
      const currentStatus = asString(current.status);
      if (currentStatus !== "PENDING_ADMIN_APPROVAL" && currentStatus !== "APPROVING") {
        approvalClaimError = "This payout request is no longer awaiting approval.";
        return;
      }
      return {
        ...current,
        status: "APPROVING",
        approvedBy: adminUid,
        approvalStartedAtTimestamp: Number(current.approvalStartedAtTimestamp ?? Date.now()),
        updatedAtTimestamp: Date.now(),
      };
    });
    if (!approvalClaim.committed) {
      throw new HttpsError("failed-precondition", approvalClaimError || "The payout request could not be claimed for approval.");
    }

    try {
      const payout = await reserveAndSubmitPayout(uid, requestKey, false, amountCentavos, requestId);
      const payoutStatus = asString(payout.status).toUpperCase();
      const requestStatus = payoutStatus === "SUCCEEDED"
        ? "PAID"
        : ["FAILED", "REJECTED", "CANCELLED", "EXPIRED"].includes(payoutStatus)
          ? "FAILED"
          : payoutStatus === "REVERSED"
            ? "REVERSED"
            : "PROCESSING";
      await requestRef.update({
        status: requestStatus,
        payoutId: requestId,
        providerPayoutId: asString(payout.providerPayoutId),
        providerStatus: payoutStatus,
        approvedBy: adminUid,
        approvedAtTimestamp: Date.now(),
        updatedAtTimestamp: Date.now(),
      });
      if (requestStatus === "FAILED" || requestStatus === "REVERSED") {
        await db.ref("wallets/" + uid).transaction((rawValue) => {
          const currentWallet = asRecord(rawValue);
          if (asString(currentWallet.activePayoutRequestId) !== requestId) return;
          const nextWallet = { ...currentWallet };
          delete nextWallet.activePayoutRequestId;
          nextWallet.updatedAtTimestamp = Date.now();
          return nextWallet;
        });
      }
      return {
        payoutRequestId: requestId,
        payoutId: requestId,
        status: requestStatus,
        payoutStatus,
        amountCentavos,
        providerPayoutId: asString(payout.providerPayoutId),
        reused: false,
      };
    } catch (error) {
      const existingPayout = asRecord((await db.ref("payouts/" + requestId).get()).val());
      if (Object.keys(existingPayout).length) {
        const existingPayoutStatus = asString(existingPayout.status).toUpperCase();
        if (["CANCELLED", "FAILED", "REJECTED", "EXPIRED"].includes(existingPayoutStatus)) {
          await db.ref("wallets/" + uid).transaction((rawValue) => {
            const currentWallet = asRecord(rawValue);
            if (asString(currentWallet.activePayoutRequestId) !== requestId) return;
            const nextWallet = { ...currentWallet };
            delete nextWallet.activePayoutRequestId;
            nextWallet.updatedAtTimestamp = Date.now();
            return nextWallet;
          });
          await requestRef.update({
            status: "FAILED",
            payoutId: requestId,
            payoutStatus: existingPayoutStatus,
            approvedBy: adminUid,
            lastError: "The payout could not be reserved or submitted. Submit a new request after reviewing the wallet.",
            updatedAtTimestamp: Date.now(),
          });
          return {
            payoutRequestId: requestId,
            payoutId: requestId,
            status: "FAILED",
            payoutStatus: existingPayoutStatus,
            amountCentavos,
            reused: true,
          };
        }
        await requestRef.update({
          status: "PROCESSING",
          payoutId: requestId,
          providerPayoutId: asString(existingPayout.providerPayoutId),
          approvedBy: adminUid,
          approvedAtTimestamp: Date.now(),
          lastError: "Provider submission is pending reconciliation.",
          updatedAtTimestamp: Date.now(),
        });
        return {
          payoutRequestId: requestId,
          payoutId: requestId,
          status: "PROCESSING",
          payoutStatus: existingPayoutStatus,
          amountCentavos,
          reused: true,
        };
      }
      await requestRef.update({
        status: "PENDING_ADMIN_APPROVAL",
        approvedBy: null,
        lastError: "Payout approval could not be completed. The administrator can retry.",
        updatedAtTimestamp: Date.now(),
      });
      throw error;
    }
  }
);

/** Rejecting a request releases its request lock without changing the user's balance. */
export const adminRejectPayoutRequest = onCall(
  { region: REGION },
  async (request) => {
    const adminUid = requireAdmin(request);
    const data = asRecord(request.data);
    const requestId = asString(data.payoutRequestId);
    const note = asString(data.note).slice(0, 300);
    if (!/^[a-f0-9]{32}$/i.test(requestId)) {
      throw new HttpsError("invalid-argument", "A valid payout request ID is required.");
    }
    const requestRef = db.ref("payoutRequests/" + requestId);
    let rejectError = "";
    const transaction = await requestRef.transaction((rawValue) => {
      const current = asRecord(rawValue);
      if (asString(current.status) !== "PENDING_ADMIN_APPROVAL") {
        rejectError = "Only a payout request awaiting approval can be rejected.";
        return;
      }
      return {
        ...current,
        status: "REJECTED",
        rejectedBy: adminUid,
        rejectionNote: note,
        rejectedAtTimestamp: Date.now(),
        updatedAtTimestamp: Date.now(),
      };
    });
    if (!transaction.committed) {
      throw new HttpsError("failed-precondition", rejectError || "The payout request could not be rejected.");
    }
    const uid = asString(asRecord(transaction.snapshot.val()).userId);
    if (uid) {
      await db.ref("wallets/" + uid).transaction((rawValue) => {
        const wallet = asRecord(rawValue);
        if (asString(wallet.activePayoutRequestId) !== requestId) return;
        const next = { ...wallet };
        delete next.activePayoutRequestId;
        next.updatedAtTimestamp = Date.now();
        return next;
      });
    }
    return { payoutRequestId: requestId, status: "REJECTED" };
  }
);

export const adminReviewPayoutAccount = onCall(
  { region: REGION },
  async (request) => {
    const adminUid = requireAdmin(request);
    const data = asRecord(request.data);
    const targetUid = asString(data.userId);
    const approved = data.approved === true;
    const note = asString(data.note).slice(0, 300);
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(targetUid)) {
      throw new HttpsError("invalid-argument", "A valid account owner is required.");
    }
    const accountRef = db.ref("payoutAccounts/" + targetUid);
    const snapshot = await accountRef.get();
    if (!snapshot.exists()) throw new HttpsError("not-found", "Payout account was not found.");
    const account = asRecord(snapshot.val());
    const wallet = asRecord((await db.ref("wallets/" + targetUid).get()).val());
    if (asString(wallet.activePayoutId)) throw new HttpsError("failed-precondition", "A payout is currently in progress.");
    const status = approved ? "VERIFIED" : "REJECTED";
    const timestamp = Date.now();
    await accountRef.update({
      status,
      reviewNote: note,
      reviewedBy: adminUid,
      reviewedAtTimestamp: timestamp,
      updatedAtTimestamp: timestamp,
    });
    await db.ref("payoutAccountReviews/" + targetUid).update({
      status,
      reviewNote: note,
      reviewedBy: adminUid,
      reviewedAtTimestamp: timestamp,
      updatedAtTimestamp: timestamp,
    });
    return {
      userId: targetUid,
      status,
      maskedAccountNumber: asString(account.maskedAccountNumber),
      reviewedAtTimestamp: timestamp,
    };
  }
);

export const getAdminPaymentDashboard = onCall(
  { region: REGION },
  async (request) => {
    requireAdmin(request);
    const [payments, ledger, payouts, payoutRequests, wallets, accountReviews, cashCollections] = await Promise.all([
      db.ref("payments").limitToLast(100).get(),
      db.ref("ledger").limitToLast(100).get(),
      db.ref("payouts").limitToLast(100).get(),
      db.ref("payoutRequests").limitToLast(100).get(),
      db.ref("wallets").limitToLast(100).get(),
      db.ref("payoutAccountReviews").limitToLast(100).get(),
      db.ref("cashCollections").limitToLast(100).get(),
    ]);
    const values = (snapshot: DataSnapshot): Record<string, unknown>[] => {
      const output: Record<string, unknown>[] = [];
      snapshot.forEach((child) => {
        if (child.exists()) output.push({ id: child.key, ...asRecord(child.val()) });
        return false;
      });
      return output.reverse();
    };
    return {
      payments: values(payments),
      ledgerEntries: values(ledger),
      payouts: values(payouts),
      payoutRequests: values(payoutRequests),
      wallets: values(wallets).map((wallet) => ({
        userId: wallet.id,
        availableCentavos: Number(wallet.availableCentavos ?? 0),
        pendingCentavos: Number(wallet.pendingCentavos ?? 0),
        cashOwedCentavos: Number(wallet.cashOwedCentavos ?? 0),
        cashEarningsCentavos: Number(wallet.cashEarningsCentavos ?? 0),
        pendingPayoutCentavos: Number(wallet.pendingPayoutCentavos ?? 0),
        paidOutCentavos: Number(wallet.paidOutCentavos ?? 0),
        refundDebtCentavos: Number(wallet.refundDebtCentavos ?? 0),
      })),
      payoutAccountReviews: values(accountReviews),
      cashCollections: values(cashCollections),
    };
  }
);

export const xenditPayoutWebhook = onRequest(
  { region: REGION, secrets: [XENDIT_PAYOUT_WEBHOOK_TOKEN] },
  async (request, response) => {
    if (request.method !== "POST") {
      response.status(405).send("Method not allowed");
      return;
    }
    if (!validCallbackToken(asString(request.get("x-callback-token")), XENDIT_PAYOUT_WEBHOOK_TOKEN.value())) {
      response.status(401).send("Invalid payout callback token");
      return;
    }
    const body = asRecord(request.body);
    const event = asString(body.event).toLowerCase();
    const data = asRecord(body.data);
    const reservation = await reserveWebhookEvent("XENDIT_PAYOUT", event, body);
    if (reservation.status === "DUPLICATE") { response.status(200).send("Duplicate event"); return; }
    if (reservation.status === "BUSY") { response.status(503).send("Webhook event is being processed"); return; }
    const webhookEventKey = reservation.key;
    const referenceId = asString(data.reference_id);
    const match = /^labmda_payout_([a-f0-9]{32})$/i.exec(referenceId);
    if (!match) {
      await markWebhookEventProcessed(webhookEventKey);
      response.status(200).send("Ignored unrelated payout event");
      return;
    }
    const payoutId = match[1];
    const payoutSnapshot = await db.ref("payouts/" + payoutId).get();
    if (!payoutSnapshot.exists()) {
      response.status(503).send("Payout reservation is not ready");
      return;
    }
    const payout = asRecord(payoutSnapshot.val());
    const sourceAmount = nonNegativeInteger(data.source_amount, "provider payout amount");
    const providerPayoutId = asString(data.payout_id);
    if (
      referenceId !== asString(payout.referenceId) ||
      sourceAmount !== Number(payout.amountCentavos) ||
      asString(data.source_currency).toUpperCase() !== "PHP" ||
      !providerPayoutId.startsWith("po-") ||
      (asString(payout.providerPayoutId) && asString(payout.providerPayoutId) !== providerPayoutId)
    ) {
      logger.error("Xendit payout webhook did not match the stored payout", { payoutId, event });
      response.status(400).send("Payout details mismatch");
      return;
    }
    const mapped: Record<string, string> = {
      "v3_payout.succeeded": "SUCCEEDED",
      "v3_payout.failed": "FAILED",
      "v3_payout.reversed": "REVERSED",
      "v3_payout.rejected": "REJECTED",
      "v3_payout.pending_compliance": "PENDING_COMPLIANCE_ASSESSMENT",
    };
    const status = mapped[event];
    if (!status) {
      await markWebhookEventProcessed(webhookEventKey);
      response.status(200).send("Ignored payout event");
      return;
    }
    try {
      await applyPayoutProviderStatus({
        payoutId,
        providerPayoutId,
        referenceId,
        status,
        sourceAmount,
        failureCode: asString(data.failure_code),
        providerUpdatedAt: asString(data.updated) || asString(body.created),
      });
      await markWebhookEventProcessed(webhookEventKey);
      response.status(200).send("OK");
    } catch (error) {
      logger.error("Xendit payout webhook reconciliation failed", { payoutId, event, error });
      response.status(503).send("Payout status could not be applied");
    }
  }
);

export const reconcileRefundEarnings = onSchedule(
  { schedule: "every 5 minutes", timeZone: "Asia/Manila", region: REGION },
  async () => {
    let reconciled = 0;
    let failed = 0;
    for (const status of ["PAID", "PARTIALLY_REFUNDED", "REFUNDED"]) {
      const snapshot = await db.ref("payments").orderByChild("status").equalTo(status).limitToLast(300).get();
      const rows: Array<{ entityId: string; payment: Record<string, unknown> }> = [];
      snapshot.forEach((child) => {
        if (child.key) rows.push({ entityId: child.key, payment: asRecord(child.val()) });
        return false;
      });
      for (const row of rows) {
        const refunds = asRecord(row.payment.refunds);
        for (const [refundAttemptId, rawRefund] of Object.entries(refunds)) {
          const refund = asRecord(rawRefund);
          if (
            asString(refund.status).toUpperCase() !== "SUCCEEDED" ||
            asString(refund.walletSettlementStatus).toUpperCase() === "POSTED"
          ) continue;
          try {
            await reconcileRefundedPaymentEarnings(row.entityId, refundAttemptId);
            const latest = asRecord((await db.ref("payments/" + row.entityId + "/refunds/" + refundAttemptId).get()).val());
            if (asString(latest.walletSettlementStatus).toUpperCase() === "POSTED") reconciled++;
          } catch (error) {
            failed++;
            logger.warn("Confirmed refund remains queued for wallet reconciliation", {
              entityId: row.entityId,
              refundAttemptId,
              error,
            });
          }
        }
      }
    }
    logger.info("Refund wallet reconciliation finished", { reconciled, failed });
  }
);

export const reconcilePayouts = onSchedule(
  {
    schedule: "every 5 minutes",
    timeZone: "Asia/Manila",
    region: REGION,
    secrets: [XENDIT_PAYOUT_SECRET_KEY, PAYOUT_ACCOUNT_ENCRYPTION_KEY],
  },
  async () => {
    const secret = XENDIT_PAYOUT_SECRET_KEY.value();
    if (!secret) {
      logger.error("Payout reconciliation skipped because Xendit secret key is not configured");
      return;
    }
    const now = Date.now();
    const pending = await db.ref("payoutReconciliationQueue")
      .orderByChild("dueAtTimestamp")
      .endAt(now)
      .limitToFirst(100)
      .get();
    const entries: Array<{ payoutId: string }> = [];
    pending.forEach((child) => {
      if (child.key) entries.push({ payoutId: child.key });
      return false;
    });
    for (const entry of entries) {
      try {
        const payoutRef = db.ref("payouts/" + entry.payoutId);
        const payout = asRecord((await payoutRef.get()).val());
        if (!Object.keys(payout).length || !payoutStatusIsActive(asString(payout.status))) {
          await db.ref("payoutReconciliationQueue/" + entry.payoutId).remove();
          continue;
        }
        const providerId = asString(payout.providerPayoutId);
        if (!providerId) {
          await createPayoutProviderRequest(entry.payoutId, secret);
          continue;
        }
        const response = await fetch(
          "https://api.xendit.co/v3/payouts/" + encodeURIComponent(providerId),
          {
            method: "GET",
            headers: {
              Authorization: "Basic " + Buffer.from(secret + ":").toString("base64"),
              "Content-Type": "application/json",
              "api-version": "2025-09-01",
            },
          }
        );
        const provider = asRecord(await response.json().catch(() => ({})));
        if (!response.ok) throw new Error("Xendit payout status lookup returned HTTP " + response.status);
        const sourceAmount = nonNegativeInteger(provider.source_amount, "provider payout source amount");
        const referenceId = asString(provider.reference_id);
        if (
          asString(provider.payout_id) !== providerId ||
          referenceId !== asString(payout.referenceId) ||
          sourceAmount !== Number(payout.amountCentavos) ||
          asString(provider.source_currency).toUpperCase() !== "PHP"
        ) {
          throw new Error("Xendit payout status did not match the reserved recipient transfer.");
        }
        await applyPayoutProviderStatus({
          payoutId: entry.payoutId,
          providerPayoutId: providerId,
          referenceId,
          status: normalizePayoutProviderStatus(asString(provider.status)),
          sourceAmount,
          failureCode: asString(provider.failure_code),
          providerUpdatedAt: asString(provider.updated),
        });
      } catch (error) {
        logger.error("Payout reconciliation attempt failed", { payoutId: entry.payoutId, error });
        const queueRef = db.ref("payoutReconciliationQueue/" + entry.payoutId);
        const queue = asRecord((await queueRef.get()).val());
        const attempts = Number(queue.attempts ?? 0) + 1;
        await queueRef.update({
          attempts,
          dueAtTimestamp: now + Math.min(60, 5 * Math.pow(2, Math.min(attempts, 3))) * 60 * 1000,
          lastError: "Provider status lookup failed; payout remains reserved.",
        });
      }
    }
  }
);

export const removePublicRestaurantManagerPhones = onCall({ region: REGION }, async (request) => {
  requireAdmin(request);
  const snapshot = await db.ref("restaurants").get();
  const updates: Record<string, null> = {};
  snapshot.forEach((restaurant) => {
    if (restaurant.child("managers").exists()) {
      updates[`${restaurant.key}/managers`] = null;
    }
    return false;
  });
  const removedCount = Object.keys(updates).length;
  if (removedCount > 0) await db.ref("restaurants").update(updates);
  logger.info("Removed public restaurant manager phone fields", { removedCount });
  return { removedCount };
});

export const adminReviewRestaurant = onCall({ region: REGION }, async (request) => {
  const adminUid = requireAdmin(request);
  const data = asRecord(request.data);
  const restaurantUid = asString(data.restaurantUid);
  const decision = asString(data.decision).toUpperCase();
  if (!restaurantUid || restaurantUid.length > 128) {
    throw new HttpsError("invalid-argument", "A valid restaurant application UID is required.");
  }
  if (decision !== "APPROVED" && decision !== "REJECTED") {
    throw new HttpsError("invalid-argument", "Decision must be APPROVED or REJECTED.");
  }

  const userRef = db.ref("users/" + restaurantUid);
  const transaction = await userRef.transaction((currentValue) => {
    if (currentValue === null || currentValue === undefined) return;
    const current = asRecord(currentValue);
    if (asString(current.foodStatus).toUpperCase() !== "PENDING") return;
    return { ...current, foodStatus: decision };
  });

  let profile: Record<string, unknown>;
  if (transaction.committed) {
    profile = asRecord(transaction.snapshot.val());
  } else {
    // Complete side effects if a retry follows a committed status transition.
    const currentSnapshot = await userRef.get();
    profile = asRecord(currentSnapshot.val());
    if (!currentSnapshot.exists() || asString(profile.foodStatus).toUpperCase() !== decision) {
      throw new HttpsError("failed-precondition", "This restaurant application is no longer pending review.");
    }
  }

  const updates: Record<string, unknown> = {};
  if (decision === "APPROVED") {
    const deliveryFee = Number(profile.deliveryFee ?? 0);
    const estimatedTimeMinutes = Number(profile.estimatedTimeMinutes ?? 20);
    const latitude = Number(profile.restaurantLatitude ?? 0);
    const longitude = Number(profile.restaurantLongitude ?? 0);
    updates["restaurants/" + restaurantUid] = {
      id: restaurantUid,
      name: asString(profile.sourceName) || "My Restaurant",
      imageUrl: asString(profile.restaurantImageUrl),
      rating: 5.0,
      deliveryFee: Number.isFinite(deliveryFee) ? deliveryFee : 0,
      estimatedTimeMinutes: Number.isFinite(estimatedTimeMinutes) ? estimatedTimeMinutes : 20,
      isOpen: true,
      paymentMethods: ["GCASH", "CASH"],
      address: asString(profile.restaurantAddress),
      latitude: Number.isFinite(latitude) ? latitude : 0,
      longitude: Number.isFinite(longitude) ? longitude : 0,
    };
    updates["restaurantPrivate/" + restaurantUid] = {
      managerUid: restaurantUid,
      managerPhone: asString(profile.managerPhone) || asString(profile.phoneNumber),
    };
  } else {
    updates["restaurants/" + restaurantUid] = null;
    updates["restaurantPrivate/" + restaurantUid] = null;
  }

  const auditKey = db.ref("admin_audit_logs/restaurants/" + restaurantUid).push().key;
  if (!auditKey) throw new HttpsError("internal", "Could not create the restaurant audit record.");
  updates["admin_audit_logs/restaurants/" + restaurantUid + "/" + auditKey] = {
    restaurantUid, decision, adminUid, reviewedAtTimestamp: Date.now(),
  };
  // Publication/private data and the immutable audit event share one atomic update.
  await db.ref().update(updates);
  logger.info("Restaurant application reviewed", { restaurantUid, decision, adminUid });
  return { restaurantUid, decision };
});

export const syncPublicShop = onValueWritten({ ref: "/shops/{shopId}", region: REGION }, async (event) => {
  const id = event.params.shopId, after = event.data.after, target = db.ref("publicShops/" + event.params.shopId);
  if (!after.exists()) { await target.remove(); return; }
  const shop = asRecord(after.val());
  if (!["APPROVED", "ACTIVE"].includes(asString(shop.status).toUpperCase())) { await target.remove(); return; }
  const latitude = Number(shop.latitude);
  const longitude = Number(shop.longitude);
  await target.set({
    id,
    name: asString(shop.name),
    description: asString(shop.description),
    category: asString(shop.category),
    phone: asString(shop.phone),
    address: asString(shop.address),
    ...(Number.isFinite(latitude) && Number.isFinite(longitude) ? { latitude, longitude } : {}),
    imageUrl: asString(shop.imageUrl),
    status: "APPROVED",
    createdAtTimestamp: Number(shop.createdAtTimestamp ?? 0),
    isOnline: shop.isOnline === true,
  });
});
export const syncPublicDriverProfile = onValueWritten({ ref: "/users/{uid}", region: REGION }, async (event) => {
  const uid = event.params.uid, after = event.data.after, target = db.ref("publicDrivers/" + uid);
  if (!after.exists()) {
    await target.remove();
    await db.ref("driverLocations/" + uid).remove();
    return;
  }

  const profile = asRecord(after.val());
  const driver = asRecord(profile.driverService);
  const courier = asRecord(profile.deliveryServices);
  const driverStatus = asString(driver.status).toUpperCase();
  const courierStatus = asString(courier.status).toUpperCase();
  const driverApproved = driverStatus === "APPROVED" || driverStatus === "ACTIVE";
  const courierApproved = courierStatus === "APPROVED" || courierStatus === "ACTIVE";
  const driverOnline = driverApproved && driver.isOnline === true;
  const courierOnline = courierApproved && courier.isOnline === true;

  // Publish profile/availability changes only. High-frequency coordinates live
  // under /driverLocations/{uid} and are handled by syncPublicDriverLocation.
  if (!driverOnline && !courierOnline) {
    await target.remove();
    await db.ref("driverLocations/" + uid).remove();
    return;
  }

  const locationSnapshot = await db.ref("driverLocations/" + uid).get();
  const location = asRecord(locationSnapshot.val());
  const vehicle = asRecord(driver.vehicleInfo ?? profile.vehicleInfo);
  const latitude = Number(location.latitude);
  const longitude = Number(location.longitude);
  const heading = Number(location.heading ?? 0);
  const hasLocation = locationSnapshot.exists() &&
    Number.isFinite(latitude) && latitude >= -90 && latitude <= 90 &&
    Number.isFinite(longitude) && longitude >= -180 && longitude <= 180 &&
    !(latitude === 0 && longitude === 0);
  const publicProfile: Record<string, unknown> = {
    uid,
    fullName: asString(profile.fullName) || (courierOnline && !driverOnline ? "Courier" : "Driver"),
    isOnline: true,
    isDriverOnline: driverOnline,
    isCourierOnline: courierOnline,
    vehicleInfo: {
      type: asString(vehicle.type),
      make: asString(vehicle.make),
      model: asString(vehicle.model),
      color: asString(vehicle.color),
    },
    updatedAtTimestamp: Date.now(),
  };
  if (hasLocation) {
    publicProfile.currentLocation = { latitude, longitude, heading: Number.isFinite(heading) ? heading : 0 };
  }
  await target.set(publicProfile);
});

export const syncPublicDriverLocation = onValueWritten({ ref: "/driverLocations/{uid}", region: REGION }, async (event) => {
  const uid = event.params.uid;
  const target = db.ref("publicDrivers/" + uid);
  const publicSnapshot = await target.get();
  if (!publicSnapshot.exists() || asRecord(publicSnapshot.val()).isOnline !== true) return;

  if (!event.data.after.exists()) {
    await target.child("currentLocation").remove();
    await target.child("updatedAtTimestamp").set(Date.now());
    return;
  }

  const location = asRecord(event.data.after.val());
  const latitude = Number(location.latitude);
  const longitude = Number(location.longitude);
  const heading = Number(location.heading ?? 0);
  if (
    !Number.isFinite(latitude) || latitude < -90 || latitude > 90 ||
    !Number.isFinite(longitude) || longitude < -180 || longitude > 180 ||
    (latitude === 0 && longitude === 0)
  ) {
    await target.child("currentLocation").remove();
    return;
  }

  await target.update({
    currentLocation: {
      latitude,
      longitude,
      heading: Number.isFinite(heading) ? heading : 0,
    },
    updatedAtTimestamp: Date.now(),
  });
});

/**
 * One-time admin migration: remove phone fields from already-published driver
 * records. The ongoing projection never republishes phone numbers.
 */
export const sanitizePublicDriverProfiles = onCall({ region: REGION }, async (request) => {
  requireUid(request);
  if (request.auth?.token.admin !== true) {
    throw new HttpsError("permission-denied", "Only an administrator can sanitize public driver profiles.");
  }

  const snapshot = await db.ref("publicDrivers").get();
  const batches: Array<Record<string, null>> = [];
  let batch: Record<string, null> = {};
  let sanitizedProfiles = 0;
  snapshot.forEach((child) => {
    const uid = child.key;
    if (!uid) return false;
    const profile = asRecord(child.val());
    if (profile.phoneNumber !== undefined) batch[uid + "/phoneNumber"] = null;
    if (profile.phone !== undefined) batch[uid + "/phone"] = null;
    if (Object.keys(batch).length >= 400) {
      batches.push(batch);
      batch = {};
    }
    if (profile.phoneNumber !== undefined || profile.phone !== undefined) sanitizedProfiles++;
    return false;
  });
  if (Object.keys(batch).length) batches.push(batch);
  for (const updates of batches) await db.ref("publicDrivers").update(updates);
  return { sanitizedProfiles };
});

export const syncAvailableDeliveryTask = onValueWritten({ ref: "/orders/{orderId}", region: REGION }, async (event) => {
  const id = event.params.orderId, after = event.data.after, target = db.ref("availableDeliveryTasks/" + event.params.orderId);
  if (!after.exists()) { await target.remove(); return; }
  const order = asRecord(after.val());
  const paymentMethod = asString(order.paymentMethod).toUpperCase();
  const paymentStatus = asString(order.paymentStatus).toUpperCase();
  if (
    asString(order.status).toUpperCase() !== "OUT_FOR_DELIVERY" ||
    asString(order.deliveryId) ||
    !["CASH", "GCASH"].includes(paymentMethod) ||
    paymentMethod === "GCASH" && paymentStatus !== "PAID"
  ) { await target.remove(); return; }
  if (paymentMethod === "GCASH") {
    const payment = asRecord((await db.ref("payments/" + id).get()).val());
    if (asString(payment.status).toUpperCase() !== "PAID") { await target.remove(); return; }
  }
  await target.set({ id, clientId: asString(order.clientId), orderType: asString(order.orderType), sourceId: asString(order.sourceId), sourceName: asString(order.sourceName),
    items: order.items ?? [], subtotal: Number(order.subtotal ?? 0), deliveryFee: Number(order.deliveryFee ?? 0), totalPrice: Number(order.totalPrice ?? 0),
    status: "OUT_FOR_DELIVERY", createdAtTimestamp: Number(order.createdAtTimestamp ?? 0), paymentMethod: asString(order.paymentMethod), paymentStatus: asString(order.paymentStatus), paymentId: asString(order.paymentId),
    deliveryId: "", deliveryName: "", deliveryPhone: "", pickupLocation: asString(order.pickupLocation), pickupLat: Number(order.pickupLat ?? 0), pickupLng: Number(order.pickupLng ?? 0),
    dropoffLocation: asString(order.dropoffLocation), dropoffLat: Number(order.dropoffLat ?? 0), dropoffLng: Number(order.dropoffLng ?? 0) });
});
export const syncAvailableRideRequest = onValueWritten({ ref: "/trips/{tripId}", region: REGION }, async (event) => {
  const id = event.params.tripId, after = event.data.after, target = db.ref("availableRideRequests/" + event.params.tripId);
  if (!after.exists()) { await target.remove(); return; }
  const trip = asRecord(after.val());
  const paymentMethod = asString(trip.paymentMethod).toUpperCase();
  const paymentStatus = asString(trip.paymentStatus).toUpperCase();
  if (
    asString(trip.status).toUpperCase() !== "REQUESTED" ||
    asString(trip.driverId) ||
    !["CASH", "GCASH"].includes(paymentMethod) ||
    paymentMethod === "GCASH" && paymentStatus !== "PAID"
  ) { await target.remove(); return; }
  if (paymentMethod === "GCASH") {
    const payment = asRecord((await db.ref("payments/" + id).get()).val());
    if (asString(payment.status).toUpperCase() !== "PAID") { await target.remove(); return; }
  }
  await target.set({ id, passengerId: asString(trip.passengerId), passengerName: asString(trip.passengerName), passengerPhone: "",
    pickupLocation: asString(trip.pickupLocation), dropoffLocation: asString(trip.dropoffLocation), pickupLat: Number(trip.pickupLat ?? 0), pickupLng: Number(trip.pickupLng ?? 0),
    dropoffLat: Number(trip.dropoffLat ?? 0), dropoffLng: Number(trip.dropoffLng ?? 0), fare: Number(trip.fare ?? 0), driverId: "", driverName: "", driverPhone: "",
    vehicleDetails: "", status: "REQUESTED", serviceType: asString(trip.serviceType), paymentMethod: asString(trip.paymentMethod), paymentStatus: asString(trip.paymentStatus),
    paymentId: asString(trip.paymentId), createdAtTimestamp: Number(trip.createdAtTimestamp ?? 0) });
});
