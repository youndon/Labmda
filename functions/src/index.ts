import { onCall, HttpsError } from "firebase-functions/v2/https";
import { initializeApp } from "firebase-admin/app";
import { getDatabase } from "firebase-admin/database";
import { defineSecret, defineString } from "firebase-functions/params";
import { Buffer } from "node:buffer";

initializeApp();

const stripeSecretKey = defineSecret("STRIPE_SECRET_KEY");
const paypalClientId = defineSecret("PAYPAL_CLIENT_ID");
const paypalClientSecret = defineSecret("PAYPAL_CLIENT_SECRET");
const paypalEnvironment = defineString("PAYPAL_ENVIRONMENT", { default: "sandbox" });

type OrderType = "FOOD" | "RETAIL";
type ItemRequest = { id: string; quantity: number };

function minorUnits(value: unknown, label: string): number {
  const amount = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(amount) || amount < 0) throw new HttpsError("failed-precondition", `Invalid ${label} in catalog.`);
  return Math.round(amount * 100);
}


type PaymentContext = {
  payment: Record<string, unknown>;
  rootPath: "orders" | "trips";
};

function normalizePaymentMethod(value: unknown): "CASH" | "STRIPE" | "PAYPAL" | "" {
  const raw = typeof value === "string" ? value.toUpperCase().replace(/[_-]+/g, " ").trim() : "";
  if (raw === "CASH" || raw === "CASH ON DELIVERY") return "CASH";
  if (raw === "STRIPE") return "STRIPE";
  if (raw === "PAYPAL") return "PAYPAL";
  return "";
}

function merchantAccessIds(source: Record<string, unknown>, fallbackId: string): Record<string, true> {
  const ids = new Set<string>();
  for (const candidate of [source.ownerUid, source.ownerId, source.merchantId, fallbackId]) {
    if (typeof candidate === "string" && candidate.trim()) ids.add(candidate.trim());
  }
  const managers = source.managers;
  if (Array.isArray(managers)) {
    for (const manager of managers) if (typeof manager === "string" && manager.trim()) ids.add(manager.trim());
  } else if (managers && typeof managers === "object") {
    for (const [uid, allowed] of Object.entries(managers as Record<string, unknown>)) {
      if (allowed === true && uid.trim()) ids.add(uid.trim());
    }
  }
  return Object.fromEntries([...ids].map(uid => [uid, true])) as Record<string, true>;
}

function distanceKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const radians = (degrees: number) => degrees * Math.PI / 180;
  const dLat = radians(lat2 - lat1);
  const dLon = radians(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(radians(lat1)) * Math.cos(radians(lat2)) * Math.sin(dLon / 2) ** 2;
  return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
}

async function getOwnedPaymentContext(orderId: string, uid: string): Promise<PaymentContext> {
  const db = getDatabase();
  const paymentSnap = await db.ref(`payments/${orderId}`).get();
  if (!paymentSnap.exists()) throw new HttpsError("not-found", "Payment record not found.");
  const payment = paymentSnap.val() as Record<string, unknown>;
  if (payment.clientId !== uid) throw new HttpsError("permission-denied", "This payment does not belong to you.");
  const declaredContext = String(payment.contextType ?? "");
  const tripSnap = declaredContext === "RIDE" ? await db.ref(`trips/${orderId}`).get() : null;
  const orderSnap = declaredContext === "RIDE" ? null : await db.ref(`orders/${orderId}`).get();
  const tripExists = tripSnap?.exists() ?? false;
  const orderExists = orderSnap?.exists() ?? false;
  if (!tripExists && !orderExists) throw new HttpsError("not-found", "The linked order or trip was not found.");
  const rootPath = tripExists ? "trips" : "orders";
  return { payment, rootPath };
}

async function getPayPalAccessToken(): Promise<{ baseUrl: string; token: string }> {
  const baseUrl = paypalEnvironment.value().toLowerCase() === "live"
    ? "https://api-m.paypal.com"
    : "https://api-m.sandbox.paypal.com";
  const clientId = paypalClientId.value().trim();
  const clientSecret = paypalClientSecret.value().trim();
  if (!clientId || !clientSecret) {
    throw new HttpsError("failed-precondition", "PayPal server credentials are not configured.");
  }
  const response = await fetch(`${baseUrl}/v1/oauth2/token`, {
    method: "POST",
    headers: {
      Authorization: `Basic ${Buffer.from(`${clientId}:${clientSecret}`).toString("base64")}`,
      "Content-Type": "application/x-www-form-urlencoded"
    },
    body: "grant_type=client_credentials"
  });
  const json = await response.json() as { access_token?: string; error_description?: string };
  if (!response.ok || !json.access_token) {
    throw new HttpsError("internal", "PayPal authentication failed. Check server credentials and environment.");
  }
  return { baseUrl, token: json.access_token };
}

async function setPaymentStatus(
  orderId: string,
  context: PaymentContext,
  status: string,
  extraPaymentFields: Record<string, unknown> = {}
): Promise<void> {
  const now = Date.now();
  await getDatabase().ref().update({
    [`payments/${orderId}/status`]: status,
    [`payments/${orderId}/updatedAtEpochMillis`]: now,
    ...Object.fromEntries(Object.entries(extraPaymentFields).map(([key, value]) => [`payments/${orderId}/${key}`, value])),
    [`payments/${orderId}/provider`]:
      typeof extraPaymentFields.provider === "string" ? extraPaymentFields.provider : String(context.payment.provider ?? context.payment.method ?? "CASH"),
    [`payments/${orderId}/providerReference`]:
      typeof extraPaymentFields.providerReference === "string" ? extraPaymentFields.providerReference :
      typeof extraPaymentFields.providerPaymentId === "string" ? extraPaymentFields.providerPaymentId :
      String(context.payment.providerReference ?? context.payment.providerPaymentId ?? ""),
    [`${context.rootPath}/${orderId}/paymentStatus`]: status,
    [`${context.rootPath}/${orderId}/paymentId`]: orderId,
    [`${context.rootPath}/${orderId}/paymentMethod`]:
      typeof extraPaymentFields.provider === "string" ? extraPaymentFields.provider :
      typeof extraPaymentFields.method === "string" ? extraPaymentFields.method : String(context.payment.provider ?? context.payment.method ?? "CASH")
  });
}

export const createOrder = onCall({ enforceAppCheck: true }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before placing an order.");
  const body = request.data as {
    orderType?: OrderType; sourceId?: string; items?: ItemRequest[];
    dropoffLocation?: string; dropoffLat?: number; dropoffLng?: number; paymentMethod?: string;
  };
  const orderType = body.orderType;
  const sourceId = typeof body.sourceId === "string" ? body.sourceId.trim() : "";
  const dropoffLocation = typeof body.dropoffLocation === "string" ? body.dropoffLocation.trim() : "";
  const lat = Number(body.dropoffLat), lng = Number(body.dropoffLng);
  const rawMethod = typeof body.paymentMethod === "string" ? body.paymentMethod.toUpperCase() : "";
  const normalizedMethod = rawMethod.replace(/[_-]+/g, " ").trim();
  const method = normalizedMethod === "PAYPAL" ? "PAYPAL"
    : normalizedMethod === "STRIPE" ? "STRIPE"
    : normalizedMethod === "CASH" || normalizedMethod === "CASH ON DELIVERY" ? "CASH" : "";

  if (orderType !== "FOOD" && orderType !== "RETAIL") throw new HttpsError("invalid-argument", "orderType must be FOOD or RETAIL.");
  if (!sourceId || !dropoffLocation || !Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180)
    throw new HttpsError("invalid-argument", "A valid source and delivery destination are required.");
  if (!method) throw new HttpsError("invalid-argument", "Choose a supported payment method.");
  const items = Array.isArray(body.items) ? body.items : [];
  if (!items.length || items.length > 100) throw new HttpsError("invalid-argument", "An order must contain 1–100 items.");
  const seen = new Set<string>();
  for (const item of items) {
    if (!item || typeof item.id !== "string" || !item.id.trim() || !Number.isInteger(item.quantity) ||
        item.quantity < 1 || item.quantity > 99 || seen.has(item.id))
      throw new HttpsError("invalid-argument", "Invalid or duplicate item/quantity.");
    seen.add(item.id);
  }

  const db = getDatabase();
  const sourcePath = orderType === "FOOD" ? `restaurants/${sourceId}` : `shops/${sourceId}`;
  const sourceSnap = await db.ref(sourcePath).get();
  if (!sourceSnap.exists()) throw new HttpsError("not-found", "This store is no longer available.");
  const source = sourceSnap.val() as Record<string, unknown>;
  let approved = false;
  if (orderType === "FOOD") {
    const profile = await db.ref(`users/${sourceId}`).get();
    approved = profile.child("foodStatus").val() === "APPROVED" || source.status === "APPROVED";
    if (!approved || source.isOpen === false) throw new HttpsError("failed-precondition", "This restaurant is not approved or is closed.");
  } else {
    approved = source.status === "APPROVED";
    if (!approved || source.isOnline === false) throw new HttpsError("failed-precondition", "This shop is not approved or is offline.");
  }

  let subtotal = 0;
  const normalizedItems: Array<Record<string, unknown>> = [];
  for (const item of items) {
    const itemPath = orderType === "FOOD" ? `${sourcePath}/meals/${item.id}` : `${sourcePath}/products/${item.id}`;
    const itemSnap = await db.ref(itemPath).get();
    if (!itemSnap.exists()) throw new HttpsError("failed-precondition", "An item is no longer available.");
    const catalog = itemSnap.val() as Record<string, unknown>;
    const available = catalog.isAvailable !== false;
    const stock = typeof catalog.stockQuantity === "number" ? catalog.stockQuantity : Number.MAX_SAFE_INTEGER;
    if (!available || (orderType === "RETAIL" && stock < item.quantity)) throw new HttpsError("failed-precondition", "An item is unavailable or out of stock.");
    const unit = minorUnits(orderType === "FOOD" ? catalog.basePrice : catalog.price, "item price");
    subtotal += unit * item.quantity;
    if (!Number.isSafeInteger(subtotal)) throw new HttpsError("out-of-range", "Order total is too large.");
    normalizedItems.push({ id: item.id, name: typeof catalog.name === "string" ? catalog.name : "", basePrice: unit / 100, quantity: item.quantity, specialInstructions: "" });
  }

  let fee: number;
  if (orderType === "FOOD") {
    fee = minorUnits(source.deliveryFee ?? 0, "delivery fee");
  } else {
    const configuredFee = await db.ref("settings/deliveryFees/RETAIL").get();
    if (!configuredFee.exists()) throw new HttpsError("failed-precondition", "Retail delivery fee is not configured.");
    fee = minorUnits(configuredFee.val(), "retail delivery fee");
  }
  const total = subtotal + fee;
  if (!Number.isSafeInteger(total)) throw new HttpsError("out-of-range", "Order total is too large.");
  const orderRef = db.ref("orders").push();
  const orderId = orderRef.key;
  if (!orderId) throw new HttpsError("internal", "Could not allocate an order ID.");
  const accessMerchantIds = merchantAccessIds(source, sourceId);
  const merchantId = String(source.ownerUid ?? source.ownerId ?? source.merchantId ?? sourceId);
  const now = Date.now();
  const order = {
    id: orderId, clientId: uid, merchantId, orderType, sourceId,
    sourceName: typeof source.name === "string" ? source.name : String(source.fullName ?? ""),
    items: normalizedItems, subtotal: subtotal / 100, deliveryFee: fee / 100, totalPrice: total / 100,
    status: "PLACED", pickupLocation: String(source.address ?? ""),
    pickupLat: Number(source.latitude) || 0, pickupLng: Number(source.longitude) || 0,
    dropoffLocation, dropoffLat: lat, dropoffLng: lng, createdAtTimestamp: now,
    paymentMethod: method, paymentStatus: "PENDING", paymentId: orderId,
    deliveryId: "", deliveryName: "", deliveryPhone: ""
  };
  const payment = {
    orderId, contextType: "ORDER", clientId: uid, merchantId, merchantIds: accessMerchantIds,
    courierId: "", provider: method, providerReference: "", method, status: "PENDING",
    amount: { minorUnits: total, currencyCode: "USD" },
    // Keep the schema explicit, but zero shares mean "not allocated yet". Trusted settlement
    // must replace this only after commission settings and provider fees are verified.
    breakdown: {
      merchantShare: { minorUnits: 0, currencyCode: "USD" },
      courierShare: { minorUnits: 0, currencyCode: "USD" },
      platformShare: { minorUnits: 0, currencyCode: "USD" },
      gatewayFee: { minorUnits: 0, currencyCode: "USD" }
    },
    providerPaymentId: "", checkoutUrl: "", expiresAtEpochMillis: 0,
    refundAmount: { minorUnits: 0, currencyCode: "USD" }, updatedAtEpochMillis: now
  };
  await db.ref().update({ [`orders/${orderId}`]: order, [`payments/${orderId}`]: payment });
  return { orderId, subtotalMinor: subtotal, deliveryFeeMinor: fee, totalMinor: total, currencyCode: "USD" };
});


/**
 * Creates a ride and its payment record atomically. Fare is calculated on the server;
 * a fare estimate from the client is never used as a settlement amount.
 */
export const createRideTrip = onCall({ enforceAppCheck: true }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before requesting a ride.");
  const body = request.data as {
    pickupLocation?: string; pickupLat?: number; pickupLng?: number;
    dropoffLocation?: string; dropoffLat?: number; dropoffLng?: number;
    passengerName?: string; passengerPhone?: string; targetDriverUid?: string | null; paymentMethod?: string;
  };
  const pickupLocation = typeof body.pickupLocation === "string" ? body.pickupLocation.trim() : "";
  const dropoffLocation = typeof body.dropoffLocation === "string" ? body.dropoffLocation.trim() : "";
  const pickupLat = Number(body.pickupLat), pickupLng = Number(body.pickupLng);
  const dropoffLat = Number(body.dropoffLat), dropoffLng = Number(body.dropoffLng);
  if (!pickupLocation || !dropoffLocation ||
      !Number.isFinite(pickupLat) || !Number.isFinite(pickupLng) ||
      !Number.isFinite(dropoffLat) || !Number.isFinite(dropoffLng) ||
      Math.abs(pickupLat) > 90 || Math.abs(dropoffLat) > 90 ||
      Math.abs(pickupLng) > 180 || Math.abs(dropoffLng) > 180) {
    throw new HttpsError("invalid-argument", "Valid pickup and destination points are required.");
  }
  const method = normalizePaymentMethod(body.paymentMethod);
  if (!method) throw new HttpsError("invalid-argument", "Choose a supported payment method.");
  const distance = distanceKm(pickupLat, pickupLng, dropoffLat, dropoffLng);
  if (!Number.isFinite(distance) || distance < 0.05 || distance > 1000) {
    throw new HttpsError("invalid-argument", "Ride distance is not valid.");
  }
  // Mirrors the displayed estimate (base $2.00, $1.20/km, $4.00 minimum), but is
  // recalculated from validated coordinates on the server and rounded to cents.
  const fareMinorUnits = Math.round(Math.max(4.00, 2.00 + distance * 1.20) * 100);
  const db = getDatabase();
  const targetDriverUid = typeof body.targetDriverUid === "string" ? body.targetDriverUid.trim() : "";
  let driverName = "";
  let driverPhone = "";
  let status = "REQUESTED";
  if (targetDriverUid) {
    const driver = await db.ref(`users/${targetDriverUid}`).get();
    if (!driver.exists() || driver.child("isDriverOnline").val() !== true ||
        driver.child("driverService").child("status").val() !== "APPROVED") {
      throw new HttpsError("failed-precondition", "That driver is not available or not administrator-approved.");
    }
    driverName = String(driver.child("fullName").val() ?? "Driver");
    driverPhone = String(driver.child("phoneNumber").val() ?? driver.child("phone").val() ?? "");
    // Only cash can be treated as immediately accepted. Online payment must be completed first.
    status = method === "CASH" ? "ACCEPTED" : "REQUESTED";
  }
  const tripRef = db.ref("trips").push();
  const tripId = tripRef.key;
  if (!tripId) throw new HttpsError("internal", "Could not allocate a ride ID.");
  const now = Date.now();
  const trip = {
    id: tripId, passengerId: uid,
    passengerName: typeof body.passengerName === "string" ? body.passengerName.trim() : "Passenger",
    passengerPhone: typeof body.passengerPhone === "string" ? body.passengerPhone.trim() : "",
    pickupLocation, pickupLat, pickupLng, dropoffLocation, dropoffLat, dropoffLng,
    fare: fareMinorUnits / 100,
    driverId: targetDriverUid, driverName, driverPhone, vehicleDetails: "",
    status, createdAtTimestamp: now,
    paymentMethod: method, paymentStatus: "PENDING", paymentId: tripId
  };
  const payment = {
    orderId: tripId, contextType: "RIDE", clientId: uid, merchantId: "", merchantIds: {},
    courierId: targetDriverUid, provider: method, providerReference: "", method, status: "PENDING",
    amount: { minorUnits: fareMinorUnits, currencyCode: "USD" },
    breakdown: {
      merchantShare: { minorUnits: 0, currencyCode: "USD" },
      courierShare: { minorUnits: 0, currencyCode: "USD" },
      platformShare: { minorUnits: 0, currencyCode: "USD" },
      gatewayFee: { minorUnits: 0, currencyCode: "USD" }
    },
    providerPaymentId: "", checkoutUrl: "", expiresAtEpochMillis: 0,
    refundAmount: { minorUnits: 0, currencyCode: "USD" }, updatedAtEpochMillis: now
  };
  await db.ref().update({ [`trips/${tripId}`]: trip, [`payments/${tripId}`]: payment });
  return { tripId, fareMinorUnits, currencyCode: "USD" };
});


/**
 * Creates hosted provider checkout sessions from server-owned payment amounts.
 * Only the hosted URL, opaque session ID, and expiry are returned to Android.
 */
export const createPaymentSession = onCall({
  enforceAppCheck: true,
  secrets: [stripeSecretKey, paypalClientId, paypalClientSecret]
}, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before starting checkout.");
  const body = request.data as { orderId?: string; method?: string };
  const orderId = typeof body.orderId === "string" ? body.orderId.trim() : "";
  const method = normalizePaymentMethod(body.method);
  if (!orderId) throw new HttpsError("invalid-argument", "orderId is required.");
  if (method !== "STRIPE" && method !== "PAYPAL") {
    throw new HttpsError("invalid-argument", "Hosted checkout requires Stripe or PayPal.");
  }

  const context = await getOwnedPaymentContext(orderId, uid);
  const payment = context.payment;
  const currentStatus = String(payment.status ?? "PENDING");
  if (currentStatus === "SUCCEEDED" || currentStatus === "REFUNDED" || currentStatus === "PARTIALLY_REFUNDED") {
    throw new HttpsError("failed-precondition", "This payment has already been settled.");
  }

  const amount = payment.amount as Record<string, unknown> | undefined;
  const amountMinorUnits = Number(amount?.minorUnits);
  const currencyCode = String(amount?.currencyCode ?? "USD").toLowerCase();
  if (!Number.isSafeInteger(amountMinorUnits) || amountMinorUnits <= 0 || !/^[a-z]{3}$/.test(currencyCode)) {
    throw new HttpsError("failed-precondition", "The server payment amount is invalid.");
  }

  const existingSessionId = String(payment.providerPaymentId ?? "");
  const existingCheckoutUrl = String(payment.checkoutUrl ?? "");
  const existingExpiry = Number(payment.expiresAtEpochMillis ?? 0);
  const currentMethod = normalizePaymentMethod(payment.method);
  if (currentStatus === "REQUIRES_ACTION" && existingSessionId && existingCheckoutUrl && existingExpiry > Date.now()) {
    if (currentMethod !== method) {
      throw new HttpsError("failed-precondition", "Finish or cancel the current checkout before changing payment methods.");
    }
    return { orderId, sessionId: existingSessionId, checkoutUrl: existingCheckoutUrl, expiresAtEpochMillis: existingExpiry };
  }

  const returnParams = "provider=" + method.toLowerCase() + "&orderId=" + encodeURIComponent(orderId);
  let sessionId = "";
  let checkoutUrl = "";
  let expiresAtEpochMillis = Date.now() + 30 * 60 * 1000;

  if (method === "STRIPE") {
    const key = stripeSecretKey.value().trim();
    if (!key) throw new HttpsError("failed-precondition", "Stripe server credentials are not configured.");
    const form = new URLSearchParams();
    form.set("mode", "payment");
    form.set("client_reference_id", orderId);
    form.set("metadata[orderId]", orderId);
    form.set("metadata[contextType]", context.rootPath === "trips" ? "RIDE" : "ORDER");
    form.set("payment_intent_data[metadata][orderId]", orderId);
    form.set("line_items[0][quantity]", "1");
    form.set("line_items[0][price_data][currency]", currencyCode);
    form.set("line_items[0][price_data][unit_amount]", String(amountMinorUnits));
    form.set("line_items[0][price_data][product_data][name]", context.rootPath === "trips" ? "Ride fare" : "Order payment");
    form.set("success_url", "labmda://payment-return?" + returnParams + "&sessionId={CHECKOUT_SESSION_ID}&token={CHECKOUT_SESSION_ID}");
    form.set("cancel_url", "labmda://payment-return?" + returnParams + "&token=cancel");

    const response = await fetch("https://api.stripe.com/v1/checkout/sessions", {
      method: "POST",
      headers: {
        Authorization: "Bearer " + key,
        "Content-Type": "application/x-www-form-urlencoded"
      },
      body: form
    });
    const data = await response.json() as {
      id?: string; url?: string; expires_at?: number; error?: { message?: string };
    };
    if (!response.ok || !data.id || !data.url || !data.url.startsWith("https://")) {
      throw new HttpsError("failed-precondition", data.error?.message || "Stripe could not create checkout.");
    }
    sessionId = data.id;
    checkoutUrl = data.url;
    if (typeof data.expires_at === "number") expiresAtEpochMillis = data.expires_at * 1000;
  } else {
    const access = await getPayPalAccessToken();
    const returnUrl = "labmda://payment-return?" + returnParams;
    const paypalBody = {
      intent: "CAPTURE",
      purchase_units: [{
        reference_id: orderId,
        custom_id: orderId,
        description: context.rootPath === "trips" ? "Ride fare" : "Order payment",
        amount: { currency_code: currencyCode.toUpperCase(), value: (amountMinorUnits / 100).toFixed(2) }
      }],
      application_context: {
        return_url: returnUrl,
        cancel_url: returnUrl + "&cancelled=1",
        user_action: "PAY_NOW",
        shipping_preference: "NO_SHIPPING"
      }
    };
    const response = await fetch(access.baseUrl + "/v2/checkout/orders", {
      method: "POST",
      headers: {
        Authorization: "Bearer " + access.token,
        "Content-Type": "application/json"
      },
      body: JSON.stringify(paypalBody)
    });
    const data = await response.json() as {
      id?: string; links?: Array<{ rel?: string; href?: string }>;
    };
    const approvalLink = data.links?.find(link => link.rel === "payer-action" || link.rel === "approve")?.href;
    if (!response.ok || !data.id || !approvalLink || !approvalLink.startsWith("https://")) {
      throw new HttpsError("failed-precondition", "PayPal could not create an approval session. Check server configuration.");
    }
    sessionId = data.id;
    checkoutUrl = approvalLink;
  }

  const now = Date.now();
  await getDatabase().ref().update({
    ["payments/" + orderId + "/provider"]: method,
    ["payments/" + orderId + "/providerReference"]: sessionId,
    ["payments/" + orderId + "/method"]: method,
    ["payments/" + orderId + "/providerPaymentId"]: sessionId,
    ["payments/" + orderId + "/checkoutUrl"]: checkoutUrl,
    ["payments/" + orderId + "/expiresAtEpochMillis"]: expiresAtEpochMillis,
    ["payments/" + orderId + "/status"]: "REQUIRES_ACTION",
    ["payments/" + orderId + "/updatedAtEpochMillis"]: now,
    [context.rootPath + "/" + orderId + "/paymentMethod"]: method,
    [context.rootPath + "/" + orderId + "/paymentStatus"]: "REQUIRES_ACTION",
    [context.rootPath + "/" + orderId + "/paymentId"]: orderId
  });
  return { orderId, sessionId, checkoutUrl, expiresAtEpochMillis };
});

/** Mark cash-on-delivery as requested, never as paid. */
export const requestCashPayment = onCall({ enforceAppCheck: true }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before requesting cash payment.");
  const body = request.data as { orderId?: string };
  const orderId = typeof body.orderId === "string" ? body.orderId.trim() : "";
  if (!orderId) throw new HttpsError("invalid-argument", "orderId is required.");
  const context = await getOwnedPaymentContext(orderId, uid);
  const status = String(context.payment.status ?? "PENDING");
  if (status === "SUCCEEDED" || status === "REFUNDED" || status === "PARTIALLY_REFUNDED") {
    throw new HttpsError("failed-precondition", "This payment has already been settled.");
  }
  const existingSessionId = String(context.payment.providerPaymentId ?? "");
  const existingExpiry = Number(context.payment.expiresAtEpochMillis ?? 0);
  if (status === "REQUIRES_ACTION" && existingSessionId && existingExpiry > Date.now()) {
    throw new HttpsError("failed-precondition", "Finish or cancel the current checkout before switching to cash.");
  }
  await setPaymentStatus(orderId, context, "PENDING", {
    provider: "CASH",
    providerReference: "",
    method: "CASH",
    providerPaymentId: "",
    checkoutUrl: "",
    expiresAtEpochMillis: 0
  });
  return { accepted: true, message: "Cash on delivery selected. Payment remains pending until collected." };
});

/**
 * Verify a provider return against the provider API before marking the local record succeeded.
 * Redirect query parameters are untrusted and cannot themselves prove payment.
 */
export const verifyPaymentReturn = onCall({
  enforceAppCheck: true,
  secrets: [stripeSecretKey, paypalClientId, paypalClientSecret]
}, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before verifying a payment.");
  const body = request.data as { orderId?: string; provider?: string; sessionId?: string; resultToken?: string };
  const orderId = typeof body.orderId === "string" ? body.orderId.trim() : "";
  const provider = normalizePaymentMethod(body.provider);
  const sessionId = typeof body.sessionId === "string" ? body.sessionId.trim() : "";
  const resultToken = typeof body.resultToken === "string" ? body.resultToken.trim() : "";
  if (!orderId || (provider !== "STRIPE" && provider !== "PAYPAL") || !resultToken) {
    throw new HttpsError("invalid-argument", "Invalid provider return.");
  }
  const context = await getOwnedPaymentContext(orderId, uid);
  const payment = context.payment;
  if (normalizePaymentMethod(payment.method) !== provider) {
    throw new HttpsError("permission-denied", "This provider does not match the payment method.");
  }
  const currentStatus = String(payment.status ?? "PENDING");
  if (currentStatus === "SUCCEEDED") return { accepted: true, message: "Payment already verified." };
  if (currentStatus === "REFUNDED" || currentStatus === "PARTIALLY_REFUNDED") {
    throw new HttpsError("failed-precondition", "This payment has already been refunded.");
  }
  const recordedSessionId = String(payment.providerPaymentId ?? "");
  const amount = payment.amount as Record<string, unknown> | undefined;
  const expectedMinorUnits = Number(amount?.minorUnits);
  const expectedCurrency = String(amount?.currencyCode ?? "USD").toUpperCase();
  if (!Number.isSafeInteger(expectedMinorUnits) || expectedMinorUnits <= 0) {
    throw new HttpsError("failed-precondition", "The server payment amount is invalid.");
  }

  // The cancel redirect is only a request. Query the provider before changing the state.
  if (resultToken === "cancel") {
    if (!recordedSessionId) {
      await setPaymentStatus(orderId, context, "CANCELLED", { checkoutUrl: "", expiresAtEpochMillis: 0 });
      return { accepted: true, message: "Checkout was cancelled before a provider session was created." };
    }
    if (provider === "STRIPE") {
      const key = stripeSecretKey.value().trim();
      if (!key) throw new HttpsError("failed-precondition", "Stripe server credentials are not configured.");
      const statusResponse = await fetch("https://api.stripe.com/v1/checkout/sessions/" + encodeURIComponent(recordedSessionId), {
        headers: { Authorization: "Bearer " + key }
      });
      const providerSession = await statusResponse.json() as {
        id?: string; status?: string; payment_status?: string; amount_total?: number; currency?: string;
        payment_intent?: string | { id?: string };
      };
      if (!statusResponse.ok || providerSession.id !== recordedSessionId) {
        throw new HttpsError("failed-precondition", "Stripe could not verify the cancellation request.");
      }
      if (providerSession.payment_status === "paid") {
        if (providerSession.amount_total !== expectedMinorUnits ||
            String(providerSession.currency ?? "").toUpperCase() !== expectedCurrency) {
          throw new HttpsError("failed-precondition", "Stripe's settled amount does not match the server payment.");
        }
        const intentId = typeof providerSession.payment_intent === "string"
          ? providerSession.payment_intent : String(providerSession.payment_intent?.id ?? "");
        await setPaymentStatus(orderId, context, "SUCCEEDED", {
          providerPaymentId: recordedSessionId, providerReceiptId: intentId, verifiedAtEpochMillis: Date.now()
        });
        return { accepted: true, message: "Stripe confirmed the payment despite the return redirect." };
      }
      if (providerSession.status === "complete") {
        await setPaymentStatus(orderId, context, "PROCESSING");
        return { accepted: true, message: "The Stripe session completed; payment confirmation is still pending." };
      }
      if (providerSession.status === "open") {
        const expireResponse = await fetch(
          "https://api.stripe.com/v1/checkout/sessions/" + encodeURIComponent(recordedSessionId) + "/expire",
          { method: "POST", headers: { Authorization: "Bearer " + key } }
        );
        if (!expireResponse.ok) {
          throw new HttpsError("failed-precondition", "Stripe could not close the checkout session safely.");
        }
      }
    } else {
      const access = await getPayPalAccessToken();
      const statusResponse = await fetch(
        access.baseUrl + "/v2/checkout/orders/" + encodeURIComponent(recordedSessionId),
        { headers: { Authorization: "Bearer " + access.token } }
      );
      const providerOrder = await statusResponse.json() as {
        id?: string; status?: string;
        purchase_units?: Array<{ amount?: { currency_code?: string; value?: string }; payments?: { captures?: Array<{ id?: string }> } }>;
      };
      if (!statusResponse.ok || providerOrder.id !== recordedSessionId) {
        throw new HttpsError("failed-precondition", "PayPal could not verify the cancellation request.");
      }
      if (providerOrder.status === "COMPLETED") {
        const unit = providerOrder.purchase_units?.[0];
        const actualCurrency = String(unit?.amount?.currency_code ?? "").toUpperCase();
        const actualMinor = Math.round(Number(unit?.amount?.value ?? "NaN") * 100);
        if (actualCurrency !== expectedCurrency || actualMinor !== expectedMinorUnits) {
          throw new HttpsError("failed-precondition", "PayPal's settled amount does not match the server payment.");
        }
        await setPaymentStatus(orderId, context, "SUCCEEDED", {
          providerPaymentId: recordedSessionId,
          providerReceiptId: String(unit?.payments?.captures?.[0]?.id ?? recordedSessionId),
          verifiedAtEpochMillis: Date.now()
        });
        return { accepted: true, message: "PayPal confirmed the payment despite the return redirect." };
      }
    }
    await setPaymentStatus(orderId, context, "CANCELLED", { checkoutUrl: "", expiresAtEpochMillis: 0 });
    return { accepted: true, message: "Provider confirmed no successful payment; checkout was cancelled." };
  }

  if (!recordedSessionId || sessionId !== recordedSessionId || resultToken !== recordedSessionId) {
    throw new HttpsError("permission-denied", "The returned session does not match this payment.");
  }

  if (provider === "STRIPE") {
    const key = stripeSecretKey.value().trim();
    if (!key) throw new HttpsError("failed-precondition", "Stripe server credentials are not configured.");
    const response = await fetch("https://api.stripe.com/v1/checkout/sessions/" + encodeURIComponent(sessionId), {
      headers: { Authorization: "Bearer " + key }
    });
    const session = await response.json() as {
      id?: string; status?: string; payment_status?: string; amount_total?: number;
      currency?: string; payment_intent?: string | { id?: string };
    };
    if (!response.ok || session.id !== recordedSessionId) {
      throw new HttpsError("failed-precondition", "Stripe could not verify this checkout session.");
    }
    if (session.status === "expired") {
      await setPaymentStatus(orderId, context, "CANCELLED");
      throw new HttpsError("failed-precondition", "The Stripe checkout session expired.");
    }
    if (session.payment_status !== "paid") {
      throw new HttpsError("failed-precondition", "Stripe has not confirmed that payment is complete.");
    }
    if (session.amount_total !== expectedMinorUnits || String(session.currency ?? "").toUpperCase() !== expectedCurrency) {
      throw new HttpsError("failed-precondition", "The Stripe amount or currency does not match the server order.");
    }
    const intentId = typeof session.payment_intent === "string" ? session.payment_intent : String(session.payment_intent?.id ?? "");
    await setPaymentStatus(orderId, context, "SUCCEEDED", {
      providerPaymentId: session.id,
      providerReceiptId: intentId,
      verifiedAtEpochMillis: Date.now()
    });
    return { accepted: true, message: "Stripe confirmed the payment." };
  }

  const access = await getPayPalAccessToken();
  const getResponse = await fetch(access.baseUrl + "/v2/checkout/orders/" + encodeURIComponent(sessionId), {
    headers: { Authorization: "Bearer " + access.token }
  });
  let paypalOrder = await getResponse.json() as {
    id?: string; status?: string;
    purchase_units?: Array<{ amount?: { currency_code?: string; value?: string }; payments?: { captures?: Array<{ id?: string; status?: string }> } }>;
  };
  if (!getResponse.ok || paypalOrder.id !== recordedSessionId) {
    throw new HttpsError("failed-precondition", "PayPal could not verify this order.");
  }
  if (paypalOrder.status !== "COMPLETED") {
    const captureResponse = await fetch(access.baseUrl + "/v2/checkout/orders/" + encodeURIComponent(sessionId) + "/capture", {
      method: "POST",
      headers: {
        Authorization: "Bearer " + access.token,
        "Content-Type": "application/json"
      },
      body: "{}"
    });
    paypalOrder = await captureResponse.json() as typeof paypalOrder;
    if (!captureResponse.ok || paypalOrder.status !== "COMPLETED") {
      throw new HttpsError("failed-precondition", "PayPal has not confirmed that capture is complete.");
    }
  }
  const unit = paypalOrder.purchase_units?.[0];
  const paypalCurrency = String(unit?.amount?.currency_code ?? "").toUpperCase();
  const paypalAmount = Math.round(Number(unit?.amount?.value ?? "NaN") * 100);
  if (paypalCurrency !== expectedCurrency || paypalAmount !== expectedMinorUnits) {
    throw new HttpsError("failed-precondition", "The PayPal amount or currency does not match the server payment.");
  }
  const captureId = String(unit?.payments?.captures?.[0]?.id ?? sessionId);
  await setPaymentStatus(orderId, context, "SUCCEEDED", {
    providerPaymentId: sessionId,
    providerReceiptId: captureId,
    verifiedAtEpochMillis: Date.now()
  });
  return { accepted: true, message: "PayPal confirmed the payment." };
});

/**
 * All order lifecycle writes pass through this callable. The caller cannot update arbitrary
 * fields or transition another user's order.
 */
export const updateOrderStatus = onCall({ enforceAppCheck: true }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to update an order.");
  const body = request.data as { orderId?: string; status?: string };
  const orderId = typeof body.orderId === "string" ? body.orderId.trim() : "";
  const nextStatus = typeof body.status === "string" ? body.status : "";
  const allowed = ["CANCELLED", "ACCEPTED", "PREPARING", "OUT_FOR_DELIVERY", "DELIVERED"];
  if (!orderId || !allowed.includes(nextStatus)) throw new HttpsError("invalid-argument", "Invalid order ID or status.");
  const db = getDatabase();
  const ref = db.ref(`orders/${orderId}`);
  const snap = await ref.get();
  if (!snap.exists()) throw new HttpsError("not-found", "Order not found.");
  const order = snap.val() as Record<string, unknown>;
  const currentStatus = String(order.status ?? "PLACED");
  const profile = await db.ref(`users/${uid}`).get();
  const isAdmin = profile.child("role").val() === "ADMIN";
  const clientOwns = order.clientId === uid;
  const sourceId = String(order.sourceId ?? "");
  const restaurantManagers = (await db.ref(`restaurants/${sourceId}/managers`).get()).val();
  const foodManagerMatch = Array.isArray(restaurantManagers)
    ? restaurantManagers.includes(uid)
    : Boolean(restaurantManagers && typeof restaurantManagers === "object" && restaurantManagers[uid] === true);
  const isFoodManager = order.orderType === "FOOD" && (sourceId === uid || foodManagerMatch);
  const shop = await db.ref(`shops/${sourceId}`).get();
  const shopManagers = shop.child("managers").val();
  const shopManagerMatch = Array.isArray(shopManagers)
    ? shopManagers.includes(uid)
    : Boolean(shopManagers && typeof shopManagers === "object" && shopManagers[uid] === true);
  const isShopManager = order.orderType === "RETAIL" && (shop.child("ownerUid").val() === uid || shopManagerMatch);
  const courierOwns = order.deliveryId === uid;
  const allowedByRole =
    (nextStatus === "CANCELLED" && clientOwns && currentStatus === "PLACED") ||
    (["ACCEPTED", "PREPARING", "OUT_FOR_DELIVERY"].includes(nextStatus) && (isFoodManager || isShopManager) &&
      ["PLACED", "ACCEPTED", "PREPARING"].includes(currentStatus)) ||
    (nextStatus === "DELIVERED" && courierOwns && currentStatus === "OUT_FOR_DELIVERY");
  if (!isAdmin && !allowedByRole) throw new HttpsError("permission-denied", "You are not allowed to make this order transition.");
  const now = Date.now();
  await ref.update({ status: nextStatus, updatedAtTimestamp: now });
  if (nextStatus === "OUT_FOR_DELIVERY" && !String(order.deliveryId ?? "")) {
    await db.ref(`deliveryQueue/${orderId}`).set({ ...order, status: nextStatus, updatedAtTimestamp: now, clientId: "" });
  } else if (nextStatus === "DELIVERED" || nextStatus === "CANCELLED") {
    await db.ref(`deliveryQueue/${orderId}`).remove();
  }
  return { accepted: true, status: nextStatus };
});

export const claimCourierOrder = onCall({ enforceAppCheck: true }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in as a courier.");
  const orderId = typeof request.data?.orderId === "string" ? request.data.orderId.trim() : "";
  if (!orderId) throw new HttpsError("invalid-argument", "orderId is required.");
  const db = getDatabase();
  const profile = await db.ref(`users/${uid}`).get();
  if (profile.child("deliveryServices").child("status").val() !== "APPROVED") {
    throw new HttpsError("permission-denied", "Courier approval is required.");
  }
  const orderRef = db.ref(`orders/${orderId}`);
  const transaction = await orderRef.transaction((current) => {
    if (!current || current.status !== "OUT_FOR_DELIVERY" || (current.deliveryId && current.deliveryId !== uid)) return;
    current.deliveryId = uid;
    current.deliveryName = String(profile.child("fullName").val() ?? "Delivery");
    current.deliveryPhone = String(profile.child("phoneNumber").val() ?? "");
    current.updatedAtTimestamp = Date.now();
    return current;
  });
  if (!transaction.committed) throw new HttpsError("failed-precondition", "This delivery has already been claimed.");
  await db.ref(`payments/${orderId}/courierId`).set(uid);
  await db.ref(`deliveryQueue/${orderId}`).remove();
  return { accepted: true, orderId };
});

export const releaseCourierOrder = onCall({ enforceAppCheck: true }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in as a courier.");
  const orderId = typeof request.data?.orderId === "string" ? request.data.orderId.trim() : "";
  if (!orderId) throw new HttpsError("invalid-argument", "orderId is required.");
  const db = getDatabase();
  const orderRef = db.ref(`orders/${orderId}`);
  const transaction = await orderRef.transaction((current) => {
    if (!current || current.deliveryId !== uid || current.status !== "OUT_FOR_DELIVERY") return;
    current.deliveryId = "";
    current.deliveryName = "";
    current.deliveryPhone = "";
    current.updatedAtTimestamp = Date.now();
    return current;
  });
  if (!transaction.committed) throw new HttpsError("permission-denied", "You cannot release this delivery.");
  const snap = await orderRef.get();
  const order = snap.val() as Record<string, unknown>;
  await db.ref(`payments/${orderId}/courierId`).set("");
  await db.ref(`deliveryQueue/${orderId}`).set({ ...order, clientId: "" });
  return { accepted: true, orderId };
});

/**
 * Payout execution deliberately fails closed until the wallet ledger and provider payout
 * integration are implemented. This guard ensures unapproved accounts cannot start payouts.
 */
export const requestPayout = onCall({ enforceAppCheck: true }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before requesting a payout.");
  const profile = await getDatabase().ref(`users/${uid}`).get();
  const approvedMerchant = profile.child("foodStatus").val() === "APPROVED" ||
    profile.child("shopStatus").val() === "APPROVED";
  const approvedCourier = profile.child("deliveryServices").child("status").val() === "APPROVED";
  if (!approvedMerchant && !approvedCourier) {
    throw new HttpsError("permission-denied", "Only administrator-approved merchants and couriers may request payouts.");
  }
  throw new HttpsError("failed-precondition", "Payouts are disabled until server-side wallet reservation and provider payout processing are configured.");
});
