import { createHash, timingSafeEqual } from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { getDatabase } from "firebase-admin/database";
import { logger } from "firebase-functions";
import { defineSecret, defineString } from "firebase-functions/params";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";

initializeApp();

const REGION = "asia-southeast1";
const db = getDatabase();
const XENDIT_SECRET_KEY = defineSecret("XENDIT_SECRET_KEY");
const XENDIT_WEBHOOK_TOKEN = defineSecret("XENDIT_WEBHOOK_TOKEN");
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
  reference_id?: string;
  status?: string;
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

  return value.map((raw) => {
    const item = asRecord(raw);
    const id = asString(item.id);
    const quantity = Number(item.quantity);
    if (!id || !Number.isInteger(quantity) || quantity < 1 || quantity > 50) {
      throw new HttpsError("invalid-argument", "Each item needs a valid catalog ID and quantity.");
    }
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
}): Promise<{ paymentId: string; checkoutUrl: string; status: string }> {
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
  const action = (request.actions ?? []).find((candidate) =>
    candidate.type === "REDIRECT_CUSTOMER"
  );
  const rawCheckoutUrl = asString(action?.value);
  const checkoutCandidate = rawCheckoutUrl
    ? (/^https:\/\//i.test(rawCheckoutUrl)
      ? rawCheckoutUrl
      : /^https?:\/\//i.test(rawCheckoutUrl)
        ? rawCheckoutUrl
        : "https://" + rawCheckoutUrl.replace(/^\/+/, ""))
    : "";
  let checkoutUrl = "";
  try {
    const parsedCheckoutUrl = new URL(checkoutCandidate);
    if (parsedCheckoutUrl.protocol === "https:") {
      checkoutUrl = parsedCheckoutUrl.toString();
    }
  } catch {
    checkoutUrl = "";
  }

  if (!paymentId || !checkoutUrl) {
    logger.error("Xendit response did not contain a payment ID and valid HTTPS customer redirect", {
      referenceId: args.referenceId,
      paymentId,
      actionType: action?.type,
    });
    throw new HttpsError("unavailable", "Xendit did not return a valid GCash checkout link.");
  }

  const initialStatus = asString(request.status).toUpperCase();
  const status = initialStatus === "SUCCEEDED"
    ? "SUCCEEDED"
    : ["FAILED", "EXPIRED", "CANCELED", "CANCELLED"].includes(initialStatus)
      ? "FAILED"
      : "PENDING";
  return { paymentId, checkoutUrl, status };
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
      deliveryFee: roundMoney(Math.max(0, asNumber(restaurant.deliveryFee ?? 0, "restaurant delivery fee"))),
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

  const shopFeeSnapshot = await db.ref("config/pricing/shopDeliveryFee").get();
  if (!shopFeeSnapshot.exists()) {
    throw new HttpsError("failed-precondition", "Retail delivery pricing is not configured by the administrator.");
  }
  const deliveryFee = roundMoney(Math.max(0, asNumber(shopFeeSnapshot.val(), "shop delivery fee")));

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
    deliveryFee,
    items,
    merchantId: asString(shop.ownerUid),
  };
}

export const createOrderPayment = onCall(
  { region: REGION, secrets: [XENDIT_SECRET_KEY] },
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
    if (!sourceId) throw new HttpsError("invalid-argument", "A source ID is required.");

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
    const subtotal = roundMoney(catalog.items.reduce((sum, item) => sum + item.basePrice * item.quantity, 0));
    const deliveryFee = catalog.deliveryFee;
    const totalPrice = roundMoney(subtotal + deliveryFee);
    if (totalPrice <= 0) {
      throw new HttpsError("failed-precondition", "The order total must be greater than zero.");
    }

    const timestamp = Date.now();
    let providerReference = "cash_" + id;
    let paymentStatus = "CASH_DUE";
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
      paymentStatus = xendit.status;
      orderStatus = paymentStatus === "SUCCEEDED" ? "PLACED" : paymentStatus === "FAILED" ? "CANCELLED" : "AWAITING_PAYMENT";
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
      checkoutUrl: checkoutUrl || null,
    };
  }
);

function distanceKm(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const radians = (degrees: number) => degrees * Math.PI / 180;
  const dLat = radians(lat2 - lat1);
  const dLng = radians(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(radians(lat1)) * Math.cos(radians(lat2)) *
    Math.sin(dLng / 2) ** 2;
  return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

async function calculateRideFare(
  serviceType: string,
  pickupLat: number,
  pickupLng: number,
  dropoffLat: number,
  dropoffLng: number
): Promise<{ fare: number; distanceKm: number }> {
  const key = serviceType.toLowerCase();
  const snapshot = await db.ref("config/pricing/rides/" + key).get();
  if (!snapshot.exists()) {
    throw new HttpsError(
      "failed-precondition",
      "Ride pricing is not configured. Ask an administrator to configure config/pricing/rides/" + key + "."
    );
  }

  const pricing = asRecord(snapshot.val());
  const baseFare = asNumber(pricing.baseFare, "ride base fare");
  const perKm = asNumber(pricing.perKm, "ride price per kilometre");
  const minimumFare = asNumber(pricing.minimumFare, "ride minimum fare");
  if (baseFare < 0 || perKm < 0 || minimumFare < 0) {
    throw new HttpsError("failed-precondition", "Ride pricing configuration cannot contain negative values.");
  }

  const distance = distanceKm(pickupLat, pickupLng, dropoffLat, dropoffLng);
  const fare = roundMoney(Math.max(minimumFare, baseFare + distance * perKm));
  return { fare, distanceKm: roundMoney(distance) };
}

export const createTripPayment = onCall(
  { region: REGION, secrets: [XENDIT_SECRET_KEY] },
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

    const fareResult = await calculateRideFare(serviceType, pickupLat, pickupLng, dropoffLat, dropoffLng);
    if (fareResult.fare <= 0) {
      throw new HttpsError("failed-precondition", "The server-calculated ride fare must be greater than zero.");
    }

    const timestamp = Date.now();
    let providerReference = "cash_" + id;
    let paymentStatus = "CASH_DUE";
    let checkoutUrl = "";
    let tripStatus = "REQUESTED";

    if (paymentMethod === "GCASH") {
      const xendit = await createXenditGcashRequest({
        referenceId: "trip_" + id,
        amount: fareResult.fare,
        description: "Labmda ride",
        entityType: "TRIP",
        entityId: id,
        clientId: uid,
        idempotencyKey: id,
      });
      providerReference = xendit.paymentId;
      checkoutUrl = xendit.checkoutUrl;
      paymentStatus = xendit.status;
      tripStatus = paymentStatus === "SUCCEEDED" ? "REQUESTED" : paymentStatus === "FAILED" ? "CANCELLED" : "AWAITING_PAYMENT";
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
      fare: fareResult.fare,
      distanceKm: fareResult.distanceKm,
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
      amount: fareResult.fare,
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
      fare: fareResult.fare,
      amount: fareResult.fare,
      checkoutUrl: checkoutUrl || null,
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

    const paymentStatus = succeeded ? "SUCCEEDED" : "FAILED";
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
