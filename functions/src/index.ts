import { createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { DataSnapshot, getDatabase } from "firebase-admin/database";
import { logger } from "firebase-functions";
import { defineSecret, defineString } from "firebase-functions/params";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";

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

function buildPaymentResponse(
  entityType: "ORDER" | "TRIP",
  id: string,
  payment: Record<string, unknown>,
  amount: number
): Record<string, unknown> {
  return {
    [entityType === "ORDER" ? "orderId" : "tripId"]: id,
    paymentId: asString(payment.providerReference),
    paymentStatus: asString(payment.status),
    amount,
    checkoutUrl: asString(payment.checkoutUrl) || null,
  };
}

async function returnExistingCheckout(
  entityPath: "orders" | "trips",
  id: string,
  uid: string
): Promise<Record<string, unknown> | null> {
  const entitySnapshot = await db.ref(entityPath + "/" + id).get();
  if (!entitySnapshot.exists()) return null;

  const entity = asRecord(entitySnapshot.val());
  const ownerId = entityPath === "orders" ? asString(entity.clientId) : asString(entity.passengerId);
  if (ownerId !== uid) {
    throw new HttpsError("already-exists", "This checkout key is already in use.");
  }

  const paymentSnapshot = await db.ref("payments/" + id).get();
  const payment = asRecord(paymentSnapshot.val());
  const amount = entityPath === "orders"
    ? asNumber(entity.totalPrice, "stored total")
    : asNumber(entity.fare, "stored fare");
  return buildPaymentResponse(entityPath === "orders" ? "ORDER" : "TRIP", id, payment, amount);
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

  return {
    sourceName: asString(shop.name) || "Shop",
    pickupLocation: asString(shop.address),
    pickupLat: Number(shop.latitude ?? 0),
    pickupLng: Number(shop.longitude ?? 0),
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

type RouteResult = {
  provider: "GOOGLE_ROUTES_API";
  distanceMeters: number;
  duration: string;
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

function calculateRideFareCentavos(
  distanceMeters: number,
  rule: RidePricingRule,
  sharedRideChargeCentavos: number
): { fareCentavos: number; optionalChargesCentavos: number } {
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
  const rounded = roundCentavos(
    rule.baseFareCentavos + perKm + optionalChargesCentavos,
    rule.roundingIncrementCentavos,
    rule.roundingMode
  );
  return {
    fareCentavos: Math.max(rule.minimumFareCentavos, rounded),
    optionalChargesCentavos,
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
    },
    calculatedAtTimestamp: Date.now(),
    ...extra,
  };
}

async function calculateGoogleRoute(
  originLat: number,
  originLng: number,
  destinationLat: number,
  destinationLng: number
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

  const response = await fetch("https://routes.googleapis.com/directions/v2:computeRoutes", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Goog-Api-Key": apiKey,
      "X-Goog-FieldMask": "routes.distanceMeters,routes.duration",
    },
    body: JSON.stringify({
      origin: { location: { latLng: { latitude: originLat, longitude: originLng } } },
      destination: { location: { latLng: { latitude: destinationLat, longitude: destinationLng } } },
      travelMode: "DRIVE",
      routingPreference: "TRAFFIC_UNAWARE",
      computeAlternativeRoutes: false,
      languageCode: "en",
      units: "METRIC",
    }),
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
  if (!Number.isSafeInteger(distanceMeters) || distanceMeters <= 0 || distanceMeters > 300000) {
    throw new HttpsError("failed-precondition", "The route service did not return a valid route distance.");
  }
  return { provider: "GOOGLE_ROUTES_API", distanceMeters, duration };
}

function closeCoordinate(left: number, right: number): boolean {
  return Math.abs(left - right) <= 0.00005;
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
    const route = await calculateGoogleRoute(pickupLat, pickupLng, dropoffLat, dropoffLng);
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
      if (!/^[a-z0-9_-]{2,24}$/.test(serviceType)) {
        throw new HttpsError("invalid-argument", "Invalid ride service type.");
      }
      const rule = config.rides[serviceType];
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
      return {
        orderId,
        totalCentavos: Number(existing.totalPriceCentavos ?? 0),
        amount: Number(existing.totalPrice ?? 0),
        pricingVersion: Number(existing.pricingVersion ?? 0),
        distanceMeters: Number(existing.distanceMeters ?? 0),
        breakdown: existing.breakdown ?? null,
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
      status: "PLACED",
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
      paymentMethod: "",
      paymentStatus: "UNPAID",
      paymentId: "",
    };
    await orderRef.set(order);

    logger.info("Server created order from locked pricing quote", { orderId, quoteId, uid, pricingVersion: pricingConfig.version });
    return {
      orderId,
      totalCentavos,
      amount: phpFromCents(totalCentavos),
      distanceMeters: nonNegativeInteger(quote.distanceMeters, "quoted route distance"),
      pricingVersion: pricingConfig.version,
      breakdown: quote.breakdown,
      reused: false,
    };
  }
);

export const createOrderPayment = onCall(
  { region: REGION, secrets: [XENDIT_SECRET_KEY, GOOGLE_ROUTES_API_KEY] },
  async (request) => {
    const uid = requireUid(request);
    const data = asRecord(request.data);
    const id = parseIdempotencyKey(data.idempotencyKey);
    const orderTypeValue = asString(data.orderType).toUpperCase();
    if (orderTypeValue !== "FOOD" && orderTypeValue !== "RETAIL") {
      throw new HttpsError("invalid-argument", "Unsupported order type.");
    }
    const orderType = orderTypeValue as OrderType;
    const sourceId = asString(data.sourceId);
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(sourceId)) {
      throw new HttpsError("invalid-argument", "A valid source ID is required.");
    }

    const existing = await returnExistingCheckout("orders", id, uid);
    if (existing) return existing;

    const paymentMethod = parsePaymentMethod(data.paymentMethod);
    const requestedItems = normalizeCatalogItems(data.items);
    const dropoffLocation = asString(data.dropoffLocation);
    const dropoffLat = asNumber(data.dropoffLat, "drop-off latitude");
    const dropoffLng = asNumber(data.dropoffLng, "drop-off longitude");
    validateCoordinates(dropoffLat, dropoffLng, "Drop-off");
    if (!dropoffLocation) throw new HttpsError("invalid-argument", "A drop-off address is required.");

    const catalog = await loadOrderCatalog(uid, orderType, sourceId, requestedItems);
    validateCoordinates(catalog.pickupLat, catalog.pickupLng, "Merchant pickup");
    const pricingQuoteId = asString(data.pricingQuoteId);
    let config: PricingConfiguration;
    let route: RouteResult;
    let deliveryFeeCentavos: number;
    let savedPricingSnapshot: Record<string, unknown>;

    if (pricingQuoteId) {
      const quote = await loadOwnedPricingQuote(
        uid, pricingQuoteId, "DELIVERY",
        catalog.pickupLat, catalog.pickupLng, dropoffLat, dropoffLng
      );
      config = normalizePricingConfiguration(quote.pricingConfiguration);
      route = {
        provider: "GOOGLE_ROUTES_API",
        distanceMeters: nonNegativeInteger(quote.distanceMeters, "quoted route distance"),
        duration: asString(quote.routeDuration),
      };
      deliveryFeeCentavos = nonNegativeInteger(quote.deliveryFeeCentavos, "quoted delivery fee");
      savedPricingSnapshot = asRecord(quote.pricingSnapshot);
    } else {
      config = await getCurrentPricingConfiguration();
      route = await calculateGoogleRoute(catalog.pickupLat, catalog.pickupLng, dropoffLat, dropoffLng);
      deliveryFeeCentavos = calculateDeliveryFeeCentavos(route.distanceMeters, config.delivery);
      savedPricingSnapshot = pricingSnapshot(config, orderType === "FOOD" ? "FOOD_DELIVERY" : "GOODS_DELIVERY", route);
    }

    const subtotalCentavos = catalog.items.reduce(
      (sum, item) => sum + centsFromPhp(item.basePrice, "catalog item price") * item.quantity,
      0
    );
    if (!Number.isSafeInteger(subtotalCentavos) || subtotalCentavos <= 0) {
      throw new HttpsError("failed-precondition", "The server-calculated item subtotal is invalid.");
    }
    const categoryChargeCentavos = orderType === "FOOD"
      ? config.additionalCharges.foodCentavos
      : config.additionalCharges.goodsCentavos;
    const additionalChargesCentavos =
      categoryChargeCentavos + config.additionalCharges.deliveryCentavos;
    const totalCentavos = subtotalCentavos + deliveryFeeCentavos + additionalChargesCentavos;
    if (!Number.isSafeInteger(totalCentavos) || totalCentavos <= 0) {
      throw new HttpsError("failed-precondition", "The server-calculated order total is invalid.");
    }

    const itemCommissionBps = orderType === "FOOD"
      ? config.commissions.foodBps
      : config.commissions.goodsBps;
    const merchantCommissionCentavos = commissionCentavos(subtotalCentavos, itemCommissionBps);
    const deliveryCommissionCentavos = commissionCentavos(deliveryFeeCentavos, config.commissions.deliveryBps);
    const merchantShareCentavos = subtotalCentavos - merchantCommissionCentavos;
    const courierShareCentavos = deliveryFeeCentavos - deliveryCommissionCentavos;
    const platformShareCentavos =
      merchantCommissionCentavos + deliveryCommissionCentavos + additionalChargesCentavos;

    const subtotal = phpFromCents(subtotalCentavos);
    const deliveryFee = phpFromCents(deliveryFeeCentavos);
    const totalPrice = phpFromCents(totalCentavos);
    const breakdown = {
      subtotal: subtotalCentavos,
      deliveryFee: deliveryFeeCentavos,
      serviceFee: additionalChargesCentavos,
      discount: 0,
      tax: 0,
      total: totalCentavos,
      merchantShare: merchantShareCentavos,
      courierShare: courierShareCentavos,
      driverShare: 0,
      platformShare: platformShareCentavos,
      gatewayFee: 0,
      merchantCommission: merchantCommissionCentavos,
      deliveryCommission: deliveryCommissionCentavos,
      rideCommission: 0,
    };
    savedPricingSnapshot = {
      ...savedPricingSnapshot,
      category: orderType === "FOOD" ? "FOOD_DELIVERY" : "GOODS_DELIVERY",
      commissionRatesBps: {
        merchant: itemCommissionBps,
        delivery: config.commissions.deliveryBps,
      },
      additionalChargesCentavos,
      cancellationFees: config.cancellationFees,
      refundPolicy: config.refundPolicy,
    };

    const timestamp = Date.now();
    let providerReference = "cash_" + id;
    let paymentStatus = "UNPAID";
    let checkoutUrl = "";
    let orderStatus = "PLACED";

    if (paymentMethod === "GCASH") {
      const xendit = await createXenditGcashRequest({
        referenceId: "order_" + id,
        amount: totalPrice,
        description: catalog.sourceName + " order",
        entityType: "ORDER",
        entityId: id,
        clientId: uid,
        idempotencyKey: id,
      });
      providerReference = xendit.paymentId;
      checkoutUrl = xendit.checkoutUrl;
      paymentStatus = xendit.status === "SUCCEEDED" ? "PAID" : xendit.status;
      orderStatus = paymentStatus === "PAID" ? "PLACED" : ["FAILED", "CANCELLED", "EXPIRED"].includes(paymentStatus) ? "CANCELLED" : "AWAITING_PAYMENT";
    }

    const order = {
      id,
      clientId: uid,
      orderType,
      sourceId,
      sourceName: catalog.sourceName,
      merchantId: catalog.merchantId,
      items: catalog.items,
      subtotal,
      deliveryFee,
      totalPrice,
      subtotalCentavos,
      deliveryFeeCentavos,
      totalPriceCentavos: totalCentavos,
      distanceMeters: route.distanceMeters,
      distanceKm: Number((route.distanceMeters / 1000).toFixed(3)),
      routeDuration: route.duration,
      pricingVersion: config.version,
      pricingQuoteId: pricingQuoteId || null,
      pricingSnapshot: savedPricingSnapshot,
      breakdown,
      status: orderStatus,
      deliveryId: "",
      deliveryName: "",
      deliveryPhone: "",
      pickupLocation: catalog.pickupLocation,
      pickupLat: catalog.pickupLat,
      pickupLng: catalog.pickupLng,
      dropoffLocation,
      dropoffLat,
      dropoffLng,
      createdAtTimestamp: timestamp,
      paymentMethod,
      paymentStatus,
      paymentId: providerReference,
    };
    const payment = {
      entityType: "ORDER",
      entityId: id,
      clientId: uid,
      merchantId: catalog.merchantId,
      provider: paymentMethod === "GCASH" ? "XENDIT" : "CASH",
      providerReference,
      channelCode: paymentMethod,
      status: paymentStatus,
      amount: totalPrice,
      amountCentavos: totalCentavos,
      breakdown,
      pricingVersion: config.version,
      currency: "PHP",
      checkoutUrl,
      createdAtTimestamp: timestamp,
      updatedAtTimestamp: timestamp,
    };

    const updates: Record<string, unknown> = {};
    updates["orders/" + id] = order;
    updates["payments/" + id] = payment;
    await db.ref().update(updates);

    return {
      orderId: id,
      paymentId: providerReference,
      paymentStatus,
      amount: totalPrice,
      amountCentavos: totalCentavos,
      breakdown,
      distanceMeters: route.distanceMeters,
      pricingVersion: config.version,
      checkoutUrl: checkoutUrl || null,
    };
  }
);

export const createTripPayment = onCall(
  { region: REGION, secrets: [XENDIT_SECRET_KEY, GOOGLE_ROUTES_API_KEY] },
  async (request) => {
    const uid = requireUid(request);
    const data = asRecord(request.data);
    const id = parseIdempotencyKey(data.idempotencyKey);
    const existing = await returnExistingCheckout("trips", id, uid);
    if (existing) return existing;

    const paymentMethod = parsePaymentMethod(data.paymentMethod);
    const serviceType = (asString(data.serviceType) || "ride").toLowerCase();
    if (!/^[a-z0-9_-]{2,24}$/.test(serviceType)) {
      throw new HttpsError("invalid-argument", "Invalid ride service type.");
    }

    const pickupAddress = asString(data.pickupLocation);
    const dropoffAddress = asString(data.dropoffLocation);
    const pickupLat = asNumber(data.pickupLat, "pickup latitude");
    const pickupLng = asNumber(data.pickupLng, "pickup longitude");
    const dropoffLat = asNumber(data.dropoffLat, "drop-off latitude");
    const dropoffLng = asNumber(data.dropoffLng, "drop-off longitude");
    validateCoordinates(pickupLat, pickupLng, "Pickup");
    validateCoordinates(dropoffLat, dropoffLng, "Drop-off");
    if (!pickupAddress || !dropoffAddress) {
      throw new HttpsError("invalid-argument", "Pickup and drop-off addresses are required.");
    }

    const pricingQuoteId = asString(data.pricingQuoteId);
    let config: PricingConfiguration;
    let route: RouteResult;
    let fareCentavos: number;
    let optionalChargesCentavos = 0;
    let savedPricingSnapshot: Record<string, unknown>;
    let rideRule: RidePricingRule;

    if (pricingQuoteId) {
      const quote = await loadOwnedPricingQuote(
        uid, pricingQuoteId, "RIDE",
        pickupLat, pickupLng, dropoffLat, dropoffLng, serviceType
      );
      config = normalizePricingConfiguration(quote.pricingConfiguration);
      route = {
        provider: "GOOGLE_ROUTES_API",
        distanceMeters: nonNegativeInteger(quote.distanceMeters, "quoted route distance"),
        duration: asString(quote.routeDuration),
      };
      fareCentavos = nonNegativeInteger(quote.fareCentavos, "quoted ride fare");
      savedPricingSnapshot = asRecord(quote.pricingSnapshot);
      rideRule = config.rides[serviceType];
    } else {
      config = await getCurrentPricingConfiguration();
      rideRule = config.rides[serviceType];
      if (!rideRule) {
        throw new HttpsError("failed-precondition", "Ride pricing is not configured for " + serviceType + ".");
      }
      route = await calculateGoogleRoute(pickupLat, pickupLng, dropoffLat, dropoffLng);
      const fare = calculateRideFareCentavos(route.distanceMeters, rideRule, config.additionalCharges.ridesCentavos);
      fareCentavos = fare.fareCentavos;
      optionalChargesCentavos = fare.optionalChargesCentavos;
      savedPricingSnapshot = pricingSnapshot(config, "RIDES", route, {
        serviceType,
        optionalChargesCentavos,
      });
    }

    if (!rideRule) {
      throw new HttpsError("failed-precondition", "Ride pricing snapshot is missing this service type.");
    }
    if (pricingQuoteId) {
      optionalChargesCentavos = Number(asRecord(savedPricingSnapshot).optionalChargesCentavos ?? 0);
    }
    if (fareCentavos <= 0) {
      throw new HttpsError("failed-precondition", "The server-calculated ride fare must be greater than zero.");
    }

    const rideCommissionCentavos = commissionCentavos(fareCentavos, config.commissions.ridesBps);
    const driverShareCentavos = fareCentavos - rideCommissionCentavos;
    const rideBreakdown = {
      subtotal: fareCentavos - optionalChargesCentavos,
      deliveryFee: 0,
      serviceFee: optionalChargesCentavos,
      discount: 0,
      tax: 0,
      total: fareCentavos,
      merchantShare: 0,
      courierShare: 0,
      driverShare: driverShareCentavos,
      platformShare: rideCommissionCentavos,
      gatewayFee: 0,
      merchantCommission: 0,
      deliveryCommission: 0,
      rideCommission: rideCommissionCentavos,
    };
    savedPricingSnapshot = {
      ...savedPricingSnapshot,
      category: "RIDES",
      serviceType,
      commissionRatesBps: { rides: config.commissions.ridesBps },
      cancellationFees: config.cancellationFees,
      refundPolicy: config.refundPolicy,
    };
    const fareAmount = phpFromCents(fareCentavos);

    const timestamp = Date.now();
    let providerReference = "cash_" + id;
    let paymentStatus = "UNPAID";
    let checkoutUrl = "";
    let tripStatus = "REQUESTED";

    if (paymentMethod === "GCASH") {
      const xendit = await createXenditGcashRequest({
        referenceId: "trip_" + id,
        amount: fareAmount,
        description: "Labmda ride",
        entityType: "TRIP",
        entityId: id,
        clientId: uid,
        idempotencyKey: id,
      });
      providerReference = xendit.paymentId;
      checkoutUrl = xendit.checkoutUrl;
      paymentStatus = xendit.status === "SUCCEEDED" ? "PAID" : xendit.status;
      tripStatus = paymentStatus === "PAID" ? "REQUESTED" : ["FAILED", "CANCELLED", "EXPIRED"].includes(paymentStatus) ? "CANCELLED" : "AWAITING_PAYMENT";
    }

    const targetDriverId = asString(data.targetDriverId);
    const trip = {
      id,
      passengerId: uid,
      passengerName: asString(data.passengerName) || "Passenger",
      passengerPhone: asString(data.passengerPhone),
      pickupLocation: pickupAddress,
      dropoffLocation: dropoffAddress,
      pickupLat,
      pickupLng,
      dropoffLat,
      dropoffLng,
      fare: fareAmount,
      fareCentavos,
      distanceMeters: route.distanceMeters,
      distanceKm: Number((route.distanceMeters / 1000).toFixed(3)),
      routeDuration: route.duration,
      pricingVersion: config.version,
      pricingQuoteId: pricingQuoteId || null,
      pricingSnapshot: savedPricingSnapshot,
      breakdown: rideBreakdown,
      serviceType: serviceType.toUpperCase(),
      driverId: targetDriverId,
      status: tripStatus,
      paymentMethod,
      paymentStatus,
      paymentId: providerReference,
      createdAtTimestamp: timestamp,
    };
    const payment = {
      entityType: "TRIP",
      entityId: id,
      clientId: uid,
      provider: paymentMethod === "GCASH" ? "XENDIT" : "CASH",
      providerReference,
      channelCode: paymentMethod,
      status: paymentStatus,
      amount: fareAmount,
      amountCentavos: fareCentavos,
      breakdown: rideBreakdown,
      pricingVersion: config.version,
      currency: "PHP",
      checkoutUrl,
      createdAtTimestamp: timestamp,
      updatedAtTimestamp: timestamp,
    };

    const updates: Record<string, unknown> = {};
    updates["trips/" + id] = trip;
    updates["payments/" + id] = payment;
    await db.ref().update(updates);

    return {
      tripId: id,
      paymentId: providerReference,
      paymentStatus,
      fare: fareAmount,
      fareCentavos,
      amount: fareAmount,
      amountCentavos: fareCentavos,
      distanceMeters: route.distanceMeters,
      distanceKm: Number((route.distanceMeters / 1000).toFixed(3)),
      pricingVersion: config.version,
      breakdown: rideBreakdown,
      checkoutUrl: checkoutUrl || null,
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
      return {
        tripId,
        fareCentavos: Number(existing.fareCentavos ?? 0),
        fare: Number(existing.fare ?? 0),
        pricingVersion: Number(existing.pricingVersion ?? 0),
        distanceMeters: Number(existing.distanceMeters ?? 0),
        breakdown: existing.breakdown ?? null,
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
    if (!serviceType || (asString(data.serviceType) && asString(data.serviceType).toLowerCase() !== serviceType)) {
      throw new HttpsError("invalid-argument", "The requested ride service does not match the quote.");
    }

    const config = normalizePricingConfiguration(quote.pricingConfiguration);
    const rideRule = config.rides[serviceType];
    if (!rideRule) {
      throw new HttpsError("failed-precondition", "The quoted ride service no longer has a valid pricing snapshot.");
    }
    const fareCentavos = nonNegativeInteger(quote.fareCentavos, "quoted ride fare");
    const route = {
      provider: "GOOGLE_ROUTES_API" as const,
      distanceMeters: nonNegativeInteger(quote.distanceMeters, "quoted route distance"),
      duration: asString(quote.routeDuration),
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
          fareCentavos: Number(saved.fareCentavos ?? 0),
          fare: Number(saved.fare ?? 0),
          pricingVersion: Number(saved.pricingVersion ?? 0),
          distanceMeters: Number(saved.distanceMeters ?? 0),
          breakdown: saved.breakdown ?? null,
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
      pricingVersion: config.version,
      pricingQuoteId: quoteId,
      pricingSnapshot: pricingSnapshotValue,
      breakdown: rideBreakdown,
      serviceType: serviceType.toUpperCase(),
      driverId: asString(data.targetDriverId),
      status: "REQUESTED",
      paymentMethod: "",
      paymentStatus: "UNPAID",
      paymentId: "",
      createdAtTimestamp: timestamp,
    };
    await db.ref("trips/" + tripId).set(trip);

    logger.info("Server created trip from locked pricing quote", { tripId, quoteId, uid, pricingVersion: config.version });
    return {
      tripId,
      fareCentavos,
      fare,
      distanceMeters: route.distanceMeters,
      distanceKm: Number((route.distanceMeters / 1000).toFixed(3)),
      pricingVersion: config.version,
      breakdown: rideBreakdown,
      reused: false,
    };
  }
);

function validCallbackToken(received: string, expected: string): boolean {
  if (!received || !expected) return false;
  const receivedHash = createHash("sha256").update(received).digest();
  const expectedHash = createHash("sha256").update(expected).digest();
  return timingSafeEqual(receivedHash, expectedHash);
}

export const xenditWebhook = onRequest(
  { region: REGION, secrets: [XENDIT_WEBHOOK_TOKEN] },
  async (request, response) => {
    if (request.method !== "POST") {
      response.status(405).send("Method not allowed");
      return;
    }

    const receivedToken = asString(request.get("x-callback-token"));
    if (!validCallbackToken(receivedToken, XENDIT_WEBHOOK_TOKEN.value())) {
      response.status(401).send("Invalid callback token");
      return;
    }

    const body = asRecord(request.body);
    const data = asRecord(body.data);
    const referenceId = asString(data.reference_id) || asString(body.reference_id);
    const status = (asString(data.status) || asString(body.status)).toUpperCase();
    const match = /^(order|trip)_([A-Za-z0-9_-]{12,80})$/.exec(referenceId);

    if (!match) {
      // Acknowledge unrelated Xendit events so the integration does not retry them forever.
      response.status(200).send("Ignored");
      return;
    }

    const entityType = match[1];
    const entityId = match[2];
    const paymentSnapshot = await db.ref("payments/" + entityId).get();
    if (!paymentSnapshot.exists()) {
      logger.warn("Payment webhook arrived before the payment record existed", { referenceId, status });
      response.status(503).send("Payment record not ready");
      return;
    }

    const payment = asRecord(paymentSnapshot.val());
    const providerReference = asString(data.payment_request_id);
    if (
      providerReference &&
      asString(payment.providerReference) &&
      providerReference !== asString(payment.providerReference)
    ) {
      logger.error("Xendit payment reference did not match the stored payment", { referenceId });
      response.status(400).send("Payment reference mismatch");
      return;
    }

    const eventAmount = Number(data.request_amount);
    const expectedAmount = Number(payment.amount);
    const eventCurrency = asString(data.currency).toUpperCase();
    const expectedCurrency = asString(payment.currency).toUpperCase();
    if (
      !Number.isFinite(eventAmount) ||
      !Number.isFinite(expectedAmount) ||
      roundMoney(eventAmount) !== roundMoney(expectedAmount) ||
      eventCurrency !== expectedCurrency
    ) {
      logger.error("Xendit webhook amount or currency did not match the stored payment", {
        referenceId,
        eventAmount,
        eventCurrency,
      });
      response.status(400).send("Payment amount or currency mismatch");
      return;
    }

    const providerUpdatedAt = asString(data.updated);
    const incomingUpdatedAt = Date.parse(providerUpdatedAt);
    const previousUpdatedAt = Date.parse(asString(payment.providerUpdatedAt));
    if (
      Number.isFinite(incomingUpdatedAt) &&
      Number.isFinite(previousUpdatedAt) &&
      incomingUpdatedAt < previousUpdatedAt
    ) {
      response.status(200).send("Older payment event ignored");
      return;
    }

    const succeeded = ["SUCCEEDED", "COMPLETED", "PAID"].includes(status);
    const failed = ["FAILED", "EXPIRED", "CANCELED", "CANCELLED"].includes(status);
    if (!succeeded && !failed) {
      response.status(200).send("No terminal payment update");
      return;
    }

    const paymentStatus = succeeded
      ? "PAID"
      : status === "EXPIRED"
        ? "EXPIRED"
        : ["CANCELED", "CANCELLED"].includes(status)
          ? "CANCELLED"
          : "FAILED";
    const updates: Record<string, unknown> = {};
    updates["payments/" + entityId + "/status"] = paymentStatus;
    updates["payments/" + entityId + "/updatedAtTimestamp"] = Date.now();
    updates["payments/" + entityId + "/providerUpdatedAt"] = providerUpdatedAt;
    updates["payments/" + entityId + "/webhookEvent"] = asString(body.event);

    if (entityType === "order") {
      updates["orders/" + entityId + "/paymentStatus"] = paymentStatus;
      updates["orders/" + entityId + "/status"] = succeeded ? "PLACED" : "CANCELLED";
    } else {
      updates["trips/" + entityId + "/paymentStatus"] = paymentStatus;
      updates["trips/" + entityId + "/status"] = succeeded ? "REQUESTED" : "CANCELLED";
    }

    await db.ref().update(updates);
    response.status(200).send("OK");
  }
);


// -----------------------------------------------------------------------------
// Xendit GCash lifecycle. A successful payment is recognized only from a
// provider-authenticated payment.capture event or a server-to-server status read.
// GCash uses automatic checkout collection; there is no card-style authorization
// hold or later capture step.
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
  const updates: Record<string, unknown> = {};
  updates[entityPath + "/" + args.entityId + "/paymentStatus"] = asString(saved.status);
  updates[entityPath + "/" + args.entityId + "/paymentId"] = asString(saved.providerReference);
  updates["payments/" + args.entityId + "/status"] = asString(saved.status);
  if (["PAID", "FAILED", "CANCELLED", "EXPIRED"].includes(asString(saved.status))) {
    updates["paymentReconciliationQueue/" + args.entityId] = null;
  }
  await db.ref().update(updates);
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
    const referenceId = asString(data.reference_id);

    if (event === "refund.succeeded" || event === "refund.failed") {
      const match = /^refund_(.+)_([a-f0-9]{32})$/i.exec(referenceId);
      if (!match) {
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

const AUTO_PAYOUT_MINIMUM_CENTAVOS = 50000; // PHP 500.00
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
): Promise<void> {
  const walletRef = db.ref("wallets/" + uid);
  const eventId = "payout_" + terminal.toLowerCase() + "_" + payoutId;
  let errorMessage = "";
  const transaction = await walletRef.transaction((rawValue) => {
    const current = asRecord(rawValue);
    const applied = asRecord(current.appliedLedgerEntries);
    const next: Record<string, unknown> = { ...current, currency: "PHP", updatedAtTimestamp: Date.now() };
    if (applied[eventId] !== true) {
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
        available += amountCentavos;
      } else {
        // A reversal can only follow a payout that was already marked successful.
        if (paidOut < amountCentavos) {
          errorMessage = "Paid-out balance is smaller than the reversed provider payout.";
          return;
        }
        paidOut -= amountCentavos;
        available += amountCentavos;
      }
      if (![available, pending, paidOut].every(Number.isSafeInteger)) {
        errorMessage = "Payout reconciliation exceeded the supported wallet balance.";
        return;
      }
      next.availableCentavos = available;
      next.pendingPayoutCentavos = pending;
      next.paidOutCentavos = paidOut;
      next.appliedLedgerEntries = { ...applied, [eventId]: true };
    }
    if (asString(current.activePayoutId) === payoutId) {
      next.activePayoutId = null;
    }
    return next;
  });
  if (errorMessage) throw new HttpsError("data-loss", errorMessage);
  if (!transaction.committed) {
    const current = asRecord((await walletRef.get()).val());
    if (asRecord(current.appliedLedgerEntries)[eventId] === true) return;
    throw new HttpsError("aborted", "Payout wallet reconciliation could not be committed.");
  }
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

function enqueueWalletPayout(uid: string): void {
  void db.ref("walletPayoutQueue/" + uid).set({
    userId: uid,
    dueAtTimestamp: Date.now(),
    updatedAtTimestamp: Date.now(),
  }).catch((error) => logger.warn("Could not queue wallet payout", { uid, error }));
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
    await applyWalletDeltas(merchantId, "cash_merchant_release_" + collectionId, {
      pendingCentavos: -merchantOwed,
      availableCentavos: merchantOwed,
    });
    enqueueWalletPayout(merchantId);
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
    asString(payment.status).toUpperCase() !== "PAID" ||
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

  const merchantId = asString(entity.merchantId) || asString(entity.sourceId);
  const courierId = asString(entity.deliveryId);
  const driverId = asString(entity.driverId);
  if (merchantShare > 0 && !merchantId) throw new HttpsError("failed-precondition", "The completed order has no merchant recipient.");
  if (courierShare > 0 && !courierId) return false;
  if (driverShare > 0 && !driverId) throw new HttpsError("failed-precondition", "The completed ride has no assigned driver recipient.");

  const complete = entityType === "ORDER"
    ? asString(entity.status).toUpperCase() === "DELIVERED"
    : asString(entity.status).toUpperCase() === "COMPLETED";
  const hasPending = asString(payment.earningsPendingStatus).toUpperCase() === "PENDING";
  if (!complete && hasPending) return false;

  const shares: Array<{ ownerId: string; accountType: string; amount: number }> = [];
  if (merchantShare > 0) shares.push({ ownerId: merchantId, accountType: "MERCHANT", amount: merchantShare });
  if (courierShare > 0) shares.push({ ownerId: courierId, accountType: "COURIER", amount: courierShare });
  if (driverShare > 0) shares.push({ ownerId: driverId, accountType: "DRIVER", amount: driverShare });
  const participantTotal = merchantShare + courierShare + driverShare;
  const entryId = (complete ? "earning_release_" : "earning_pending_") + entityType.toLowerCase() + "_" + entityId;
  const lines: LedgerLine[] = [];
  const recoveredByOwner = new Map<string, number>();

  if (complete) {
    // Apply each wallet mutation idempotently first, then build the immutable journal
    // from the exact recovered/debt-offset amount returned by that wallet transaction.
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
        if (recovered > 0) lines.push(ledgerLine(share.accountType + "_REFUND_RECEIVABLE", share.ownerId, "CREDIT", recovered));
      }
    } else {
      lines.push(ledgerLine("XENDIT_GATEWAY_CLEARING", "PLATFORM", "DEBIT", amount));
      for (const share of shares) {
        const recovered = recoveredByOwner.get(share.ownerId) ?? 0;
        const available = share.amount - recovered;
        if (available > 0) lines.push(ledgerLine(share.accountType + "_AVAILABLE_PAYABLE", share.ownerId, "CREDIT", available));
        if (recovered > 0) lines.push(ledgerLine(share.accountType + "_REFUND_RECEIVABLE", share.ownerId, "CREDIT", recovered));
      }
      if (platformShare > 0) lines.push(ledgerLine("PLATFORM_COMMISSION_REVENUE", "PLATFORM", "CREDIT", platformShare));
    }
  } else {
    lines.push(ledgerLine("XENDIT_GATEWAY_CLEARING", "PLATFORM", "DEBIT", amount));
    for (const share of shares) lines.push(ledgerLine(share.accountType + "_PENDING_PAYABLE", share.ownerId, "CREDIT", share.amount));
    if (platformShare > 0) lines.push(ledgerLine("PLATFORM_COMMISSION_REVENUE", "PLATFORM", "CREDIT", platformShare));
  }

  const journalTotal = complete && hasPending ? participantTotal : amount;
  if (journalTotal <= 0) {
    await db.ref("payments/" + entityId).update({ earningsSettlementStatus: "RELEASED", earningsReleasedAtTimestamp: Date.now() });
    return true;
  }
  const entry = {
    operationId: (complete ? "EARNING_RELEASE:" : "EARNING_PENDING:") + entityType + ":" + entityId,
    entryType: complete ? "EARNING_RELEASE" : "EARNING_PENDING",
    entityType,
    entityId,
    paymentId: asString(payment.providerReference),
    totalCentavos: journalTotal,
    lines,
    memo: complete ? "Move pending GCash earnings to available after service completion" : "Reserve paid GCash earnings pending service completion",
    createdAtTimestamp: Number(payment.earningsPendingAtTimestamp ?? payment.paidAtTimestamp ?? payment.updatedAtTimestamp ?? entity.createdAtTimestamp ?? 1),
  };
  await ensureMarketplaceLedgerEntry(entryId, entry);

  if (!complete) {
    for (const share of shares) {
      await applyWalletDeltas(share.ownerId, "earning_pending_" + entryId + "_" + share.ownerId, { pendingCentavos: share.amount });
    }
  }

  if (complete) {
    if (payment.suppressPayoutQueue !== true) {
      for (const share of shares) enqueueWalletPayout(share.ownerId);
    }
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
  } else {
    await db.ref("payments/" + entityId).update({
      earningsPendingStatus: "PENDING",
      earningsPendingLedgerEntryId: entryId,
      earningsPendingAtTimestamp: Date.now(),
    });
    await db.ref((entityType === "ORDER" ? "orders/" : "trips/") + entityId).update({
      earningsPendingStatus: "PENDING",
      earningsPendingLedgerEntryId: entryId,
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
      lines.push(ledgerLine(recipient.type + "_REFUND_RECEIVABLE", recipient.uid, "DEBIT", walletResult.debtCentavos));
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
  { schedule: "every 5 minutes", timeZone: "Asia/Manila", region: REGION },
  async () => {
    let released = 0;
    let failed = 0;
    const paidSnapshot = await db.ref("payments").orderByChild("status").equalTo("PAID").limitToLast(500).get();
    const paidRows: Array<{ id: string; value: Record<string, unknown> }> = [];
    paidSnapshot.forEach((child) => {
      if (child.key) paidRows.push({ id: child.key, value: asRecord(child.val()) });
      return false;
    });
    for (const row of paidRows) {
      try {
        const payment = row.value;
        if (asString(payment.provider).toUpperCase() !== "XENDIT" ||
            asString(payment.channelCode).toUpperCase() !== "GCASH" ||
            asString(payment.earningsSettlementStatus).toUpperCase() === "RELEASED") continue;
        const entityType = asString(payment.entityType).toUpperCase() === "TRIP" ? "TRIP" : "ORDER";
        const entityPath = entityType === "TRIP" ? "trips/" : "orders/";
        const entitySnapshot = await db.ref(entityPath + row.id).get();
        if (!entitySnapshot.exists()) continue;
        if (await reconcileCompletedPaymentEarnings(entityType, row.id, asRecord(entitySnapshot.val()), payment)) released++;
      } catch (error) {
        failed++;
        logger.error("Paid transaction earnings reservation/release failed", { entityId: row.id, error });
      }
    }
    const orderSnapshot = await db.ref("orders").orderByChild("status").equalTo("DELIVERED").limitToFirst(300).get();
    const orders: Array<{ id: string; value: Record<string, unknown> }> = [];
    orderSnapshot.forEach((child) => {
      if (child.key) orders.push({ id: child.key, value: asRecord(child.val()) });
      return false;
    });
    for (const order of orders) {
      try {
        const payment = asRecord((await db.ref("payments/" + order.id).get()).val());
        if (asString(payment.earningsSettlementStatus) === "RELEASED") continue;
        if (await reconcileCompletedPaymentEarnings("ORDER", order.id, order.value, payment)) released++;
      } catch (error) {
        failed++;
        logger.error("Order earnings settlement failed", { orderId: order.id, error });
      }
    }

    const tripSnapshot = await db.ref("trips").orderByChild("status").equalTo("COMPLETED").limitToFirst(300).get();
    const trips: Array<{ id: string; value: Record<string, unknown> }> = [];
    tripSnapshot.forEach((child) => {
      if (child.key) trips.push({ id: child.key, value: asRecord(child.val()) });
      return false;
    });
    for (const trip of trips) {
      try {
        const payment = asRecord((await db.ref("payments/" + trip.id).get()).val());
        if (asString(payment.earningsSettlementStatus) === "RELEASED") continue;
        if (await reconcileCompletedPaymentEarnings("TRIP", trip.id, trip.value, payment)) released++;
      } catch (error) {
        failed++;
        logger.error("Ride earnings settlement failed", { tripId: trip.id, error });
      }
    }
    logger.info("Marketplace earnings reconciliation finished", { released, failed });
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
    await applyPayoutWalletTerminal(uid, args.payoutId, amount, "SUCCEEDED");
    await payoutRef.update({ ledgerEntryId: entryId, completedAtTimestamp: Date.now() });
    await db.ref("payoutReconciliationQueue/" + args.payoutId).remove();
  } else if (status === "FAILED" || status === "REJECTED" || status === "CANCELLED" || status === "EXPIRED") {
    const entryId = entryPrefix + "_failed";
    const entry = {
      operationId: "PAYOUT_FAILED:" + args.payoutId,
      entryType: "PAYOUT_FAILED",
      payoutId: args.payoutId,
      userId: uid,
      totalCentavos: amount,
      lines: [
        ledgerLine("WALLET_PAYOUT_PENDING", uid, "DEBIT", amount),
        ledgerLine("WALLET_AVAILABLE_PAYABLE", uid, "CREDIT", amount),
      ],
      memo: "Return reserved wallet funds after a failed payout",
      createdAtTimestamp: Number(payout.createdAtTimestamp ?? 1),
    };
    await ensureMarketplaceLedgerEntry(entryId, entry);
    await applyPayoutWalletTerminal(uid, args.payoutId, amount, "FAILED");
    await payoutRef.update({ ledgerEntryId: entryId, completedAtTimestamp: Date.now() });
    await db.ref("payoutReconciliationQueue/" + args.payoutId).remove();
  } else if (status === "REVERSED") {
    const entryId = entryPrefix + "_reversed";
    const entry = {
      operationId: "PAYOUT_REVERSED:" + args.payoutId,
      entryType: "PAYOUT_REVERSED",
      payoutId: args.payoutId,
      userId: uid,
      totalCentavos: amount,
      lines: [
        ledgerLine("XENDIT_PAYOUT_CLEARING", "PLATFORM", "DEBIT", amount),
        ledgerLine("WALLET_AVAILABLE_PAYABLE", uid, "CREDIT", amount),
      ],
      memo: "Restore a payout returned by the recipient bank or wallet",
      createdAtTimestamp: Number(payout.createdAtTimestamp ?? 1),
    };
    await ensureMarketplaceLedgerEntry(entryId, entry);
    await applyPayoutWalletTerminal(uid, args.payoutId, amount, "REVERSED");
    await payoutRef.update({ ledgerEntryId: entryId, reversedAtTimestamp: Date.now() });
    await db.ref("payoutReconciliationQueue/" + args.payoutId).remove();
    enqueueWalletPayout(uid);
  } else {
    await db.ref("payoutReconciliationQueue/" + args.payoutId).set({
      payoutId: args.payoutId,
      dueAtTimestamp: Date.now() + PAYOUT_RECONCILIATION_MS,
      attempts: 0,
    });
  }
}

async function reserveAndSubmitPayout(
  uid: string,
  requestKey: string,
  automatic: boolean
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
  const amount = walletNumber(wallet, "availableCentavos");
  if (walletNumber(wallet, "refundDebtCentavos") > 0) {
    throw new HttpsError("failed-precondition", "Outstanding refund recovery must be cleared from future earnings before another payout.");
  }
  const minimum = automatic ? AUTO_PAYOUT_MINIMUM_CENTAVOS : MANUAL_PAYOUT_MINIMUM_CENTAVOS;
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
    if (available !== amount) {
      reserveError = "Wallet balance changed while the payout was being prepared. Please retry.";
      return;
    }
    const pending = walletNumber(current, "pendingPayoutCentavos");
    const applied = asRecord(current.appliedLedgerEntries);
    return {
      ...current,
      availableCentavos: available - amount,
      pendingPayoutCentavos: pending + amount,
      activePayoutId: payoutId,
      appliedLedgerEntries: { ...applied, ["payout_reserve_" + payoutId]: true },
      currency: "PHP",
      updatedAtTimestamp: Date.now(),
    };
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
    memo: automatic ? "Automatically reserve available earnings for payout" : "User requested an earnings payout",
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
    if (asString(wallet.activePayoutId)) {
      throw new HttpsError("failed-precondition", "Payout account details cannot be changed while a payout is processing.");
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

export const requestPayout = onCall(
  { region: REGION, secrets: [XENDIT_PAYOUT_SECRET_KEY, PAYOUT_ACCOUNT_ENCRYPTION_KEY] },
  async (request) => {
    const uid = requireUid(request);
    await requireApprovedEarningsRole(uid);
    const data = asRecord(request.data);
    const key = parseIdempotencyKey(data.idempotencyKey);
    const result = await reserveAndSubmitPayout(uid, key, false);
    return {
      payoutId: asString(result.id),
      status: asString(result.status),
      amountCentavos: Number(result.amountCentavos ?? 0),
      providerPayoutId: asString(result.providerPayoutId),
      maskedAccountNumber: asString(result.maskedAccountNumber),
    };
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
    if (approved) enqueueWalletPayout(targetUid);
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
    const [payments, ledger, payouts, wallets, accountReviews, cashCollections] = await Promise.all([
      db.ref("payments").limitToLast(100).get(),
      db.ref("ledger").limitToLast(100).get(),
      db.ref("payouts").limitToLast(100).get(),
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
    const referenceId = asString(data.reference_id);
    const match = /^labmda_payout_([a-f0-9]{32})$/i.exec(referenceId);
    if (!match) {
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
      response.status(200).send("OK");
    } catch (error) {
      logger.error("Xendit payout webhook reconciliation failed", { payoutId, event, error });
      response.status(503).send("Payout status could not be applied");
    }
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

export const processAutomaticPayouts = onSchedule(
  {
    schedule: "every 15 minutes",
    timeZone: "Asia/Manila",
    region: REGION,
    secrets: [XENDIT_PAYOUT_SECRET_KEY, PAYOUT_ACCOUNT_ENCRYPTION_KEY],
  },
  async () => {
    const secret = XENDIT_PAYOUT_SECRET_KEY.value();
    if (!secret) return;
    const queue = await db.ref("walletPayoutQueue").limitToFirst(100).get();
    const entries: Array<{ uid: string; updatedAtTimestamp: number }> = [];
    queue.forEach((child) => {
      if (child.key) {
        const item = asRecord(child.val());
        entries.push({
          uid: child.key,
          updatedAtTimestamp: Number(item.updatedAtTimestamp ?? item.dueAtTimestamp ?? 0),
        });
      }
      return false;
    });
    for (const queued of entries) {
      const uid = queued.uid;
      try {
        await requireApprovedEarningsRole(uid);
        const account = asRecord((await db.ref("payoutAccounts/" + uid).get()).val());
        const wallet = asRecord((await db.ref("wallets/" + uid).get()).val());
        const available = walletNumber(wallet, "availableCentavos");
        if (walletNumber(wallet, "refundDebtCentavos") > 0) {
          await db.ref("walletPayoutQueue/" + uid).remove();
          continue;
        }
        if (asString(account.status).toUpperCase() !== "VERIFIED") continue;
        if (available < AUTO_PAYOUT_MINIMUM_CENTAVOS) {
          await db.ref("walletPayoutQueue/" + uid).remove();
          continue;
        }
        const requestKey = "auto_" + createHash("sha256").update(uid + "|" + queued.updatedAtTimestamp).digest("hex").slice(0, 32);
        await reserveAndSubmitPayout(uid, requestKey, true);
        await db.ref("walletPayoutQueue/" + uid).remove();
      } catch (error) {
        logger.warn("Automatic wallet payout is deferred", { uid, error });
      }
    }
  }
);
