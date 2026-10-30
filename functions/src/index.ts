import { createHash, randomUUID, timingSafeEqual } from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { getDatabase } from "firebase-admin/database";
import { logger } from "firebase-functions";
import { defineSecret, defineString } from "firebase-functions/params";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";

initializeApp();

const REGION = "asia-southeast1";
const db = getDatabase();
const XENDIT_SECRET_KEY = defineSecret("XENDIT_SECRET_KEY");
const XENDIT_WEBHOOK_TOKEN = defineSecret("XENDIT_WEBHOOK_TOKEN");
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
    if (["SUCCEEDED", "FAILED"].includes(previousStatus)) return current;

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

  if (!transaction.committed || !transaction.snapshot.exists()) return false;
  const saved = asRecord(transaction.snapshot.val());
  const refundQueueKey = args.entityId + "_" + args.refundAttemptId;
  await db.ref("refundReconciliationQueue/" + refundQueueKey).remove();
  if (["REFUNDED", "PARTIALLY_REFUNDED"].includes(asString(saved.status))) {
    const entityType = asString(saved.entityType).toUpperCase() === "TRIP" ? "TRIP" : "ORDER";
    await db.ref(paymentEntityPath(entityType as GcashEntityType) + "/" + args.entityId + "/paymentStatus")
      .set(asString(saved.status));
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
    const validReasons = ["FRAUDULENT", "DUPLICATE", "REQUESTED_BY_CUSTOMER", "OTHERS"];
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
      await paymentRef.child("refunds").child(refundAttemptId).update({
        providerRefundId,
        status: refundStatus,
        updatedAtTimestamp: Date.now(),
      });

      if (refundStatus === "PENDING") {
        await db.ref("refundReconciliationQueue/" + entityId + "_" + refundAttemptId).set({
          entityId,
          refundAttemptId,
          providerRefundId,
          referenceId,
          dueAtTimestamp: Date.now() + 2 * 60 * 1000,
          attempts: 0,
        });
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
      asString(payment.providerReference) !== paymentRequestId ||
      asString(payment.referenceId) !== referenceId ||
      asString(payment.channelCode).toUpperCase() !== "GCASH"
    ) {
      response.status(400).send("Payment reference mismatch");
      return;
    }

    const amount = centsFromProviderAmount(data.request_amount);
    if (
      (amount !== null && amount !== Number(payment.amountCentavos)) ||
      (asString(data.currency) && asString(data.currency).toUpperCase() !== "PHP") ||
      (asString(data.country) && asString(data.country).toUpperCase() !== "PH") ||
      (asString(data.channel_code) && asString(data.channel_code).toUpperCase() !== "GCASH")
    ) {
      logger.error("Xendit payment webhook amount or channel did not match the stored payment", { referenceId });
      response.status(400).send("Payment details mismatch");
      return;
    }

    let nextStatus: GcashTerminalStatus | null = null;
    if (event === "payment.capture") nextStatus = "PAID";
    else if (event === "payment.failure") nextStatus = "FAILED";
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

    for (const child of pendingPayments.children) {
      const entityId = child.key;
      if (!entityId) continue;
      const paymentSnapshot = await db.ref("payments/" + entityId).get();
      if (!paymentSnapshot.exists()) {
        await child.ref.remove();
        continue;
      }
      const payment = asRecord(paymentSnapshot.val());
      if (
        asString(payment.provider) !== "XENDIT" ||
        asString(payment.channelCode).toUpperCase() !== "GCASH" ||
        asString(payment.status).toUpperCase() !== "PENDING"
      ) {
        await child.ref.remove();
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
          const attempts = Number(asRecord(child.val()).attempts ?? 0) + 1;
          await child.ref.update({
            attempts,
            lastCheckedAtTimestamp: now,
            dueAtTimestamp: now + 5 * 60 * 1000,
          });
        }
      } catch (error) {
        logger.warn("Payment reconciliation lookup failed", { entityId, error });
        const queue = asRecord(child.val());
        const attempts = Number(queue.attempts ?? 0) + 1;
        const backoffMinutes = Math.min(30, 5 * Math.pow(2, Math.min(attempts, 2)));
        await child.ref.update({
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

    for (const child of pendingRefunds.children) {
      const queue = asRecord(child.val());
      const entityId = asString(queue.entityId);
      const refundAttemptId = asString(queue.refundAttemptId);
      const providerRefundId = asString(queue.providerRefundId);
      const referenceId = asString(queue.referenceId);
      if (!entityId || !refundAttemptId || !providerRefundId) {
        await child.ref.remove();
        continue;
      }
      const paymentSnapshot = await db.ref("payments/" + entityId).get();
      if (!paymentSnapshot.exists()) {
        await child.ref.remove();
        continue;
      }
      const payment = asRecord(paymentSnapshot.val());
      const refund = asRecord(asRecord(payment.refunds)[refundAttemptId]);
      if (["SUCCEEDED", "FAILED"].includes(asString(refund.status).toUpperCase())) {
        await child.ref.remove();
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
          await child.ref.update({
            attempts,
            lastCheckedAtTimestamp: now,
            dueAtTimestamp: now + 5 * 60 * 1000,
          });
        }
      } catch (error) {
        logger.warn("Refund reconciliation lookup failed", { entityId, providerRefundId, error });
        const attempts = Number(queue.attempts ?? 0) + 1;
        const backoffMinutes = Math.min(30, 5 * Math.pow(2, Math.min(attempts, 2)));
        await child.ref.update({
          attempts,
          lastCheckedAtTimestamp: now,
          dueAtTimestamp: now + backoffMinutes * 60 * 1000,
        });
      }
    }

    logger.info("Xendit payment reconciliation finished", {
      paymentCandidates: pendingPayments.size,
      refundCandidates: pendingRefunds.size,
    });
  }
);
