import { onCall, onRequest, HttpsError } from "firebase-functions/v2/https";
import { onValueUpdated } from "firebase-functions/v2/database";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { createHash, createHmac, timingSafeEqual } from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { getDatabase } from "firebase-admin/database";
import { defineSecret, defineString } from "firebase-functions/params";
import { Buffer } from "node:buffer";

initializeApp();

const stripeSecretKey = defineSecret("STRIPE_SECRET_KEY");
const paypalClientId = defineSecret("PAYPAL_CLIENT_ID");
const paypalClientSecret = defineSecret("PAYPAL_CLIENT_SECRET");
const paypalEnvironment = defineString("PAYPAL_ENVIRONMENT", { default: "sandbox" });
const stripeWebhookSecret = defineSecret("STRIPE_WEBHOOK_SECRET");
const paypalWebhookId = defineString("PAYPAL_WEBHOOK_ID", { default: "" });
const payoutsEnabled = defineString("PAYOUTS_ENABLED", { default: "false" });

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

const SETTLED_PAYMENT_STATUSES = new Set(["SUCCEEDED", "REFUNDED", "PARTIALLY_REFUNDED"]);
const OPEN_STRIPE_INTENT_STATUSES = new Set(["requires_payment_method", "requires_confirmation", "requires_action"]);
/** RTDB key guard: rejects empty, oversized, or path-like values such as "a/b". */
function isSafeKey(value: string): boolean {
  return value.length > 0 && value.length <= 768 && !/[.#$\[\]\/]/.test(value);
}

type OrderQuote = {
  source: Record<string, unknown>;
  subtotal: number;
  fee: number;
  total: number;
  normalizedItems: Array<Record<string, unknown>>;
};

/**
 * Prices an order from the current server catalog and fee settings. Used both when an order is
 * created and again when checkout starts, so a stored or client-supplied total is never trusted.
 */
async function quoteOrder(orderType: OrderType, sourceId: string, rawItems: unknown): Promise<OrderQuote> {
  if (!sourceId) throw new HttpsError("invalid-argument", "A valid source is required.");
  const items = Array.isArray(rawItems) ? rawItems as ItemRequest[] : [];
  if (!items.length || items.length > 100) throw new HttpsError("invalid-argument", "An order must contain 1–100 items.");
  const seen = new Set<string>();
  for (const item of items) {
    if (!item || typeof item.id !== "string" || !item.id.trim() || !isSafeKey(item.id) ||
        !Number.isInteger(item.quantity) || item.quantity < 1 || item.quantity > 99 || seen.has(item.id))
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
  return { source, subtotal, fee, total, normalizedItems };
}

/** Mirrors the displayed estimate (base $2.00, $1.20/km, $4.00 minimum), rounded to cents. */
function rideFareMinorUnits(pickupLat: number, pickupLng: number, dropoffLat: number, dropoffLng: number): number {
  const distance = distanceKm(pickupLat, pickupLng, dropoffLat, dropoffLng);
  if (!Number.isFinite(distance) || distance < 0.05 || distance > 1000) {
    throw new HttpsError("invalid-argument", "Ride distance is not valid.");
  }
  return Math.round(Math.max(4.00, 2.00 + distance * 1.20) * 100);
}

type StripeIntent = {
  id?: string; client_secret?: string; status?: string; capture_method?: string;
  amount?: number; amount_capturable?: number; amount_received?: number; currency?: string;
  latest_charge?: string | { id?: string } | null; error?: { message?: string };
};

async function stripeApi<T>(
  secretKey: string,
  path: string,
  form?: URLSearchParams,
  idempotencyKey?: string
): Promise<{ ok: boolean; data: T }> {
  const headers: Record<string, string> = { Authorization: "Bearer " + secretKey };
  if (form) headers["Content-Type"] = "application/x-www-form-urlencoded";
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
  const response = await fetch("https://api.stripe.com/v1/" + path, { method: form ? "POST" : "GET", headers, body: form });
  return { ok: response.ok, data: await response.json() as T };
}

async function getStripeIntent(secretKey: string, intentId: string): Promise<StripeIntent> {
  const { ok, data } = await stripeApi<StripeIntent>(secretKey, "payment_intents/" + encodeURIComponent(intentId));
  if (!ok || data.id !== intentId) throw new HttpsError("failed-precondition", "Stripe could not verify this payment.");
  return data;
}

/**
 * Cancels a Stripe PaymentIntent that has not been authorized yet, so switching method or amount
 * can never leave two live authorizations for one order. Authorized intents are never cancelled here.
 */
async function cancelOpenStripeIntent(secretKey: string, intentId: string): Promise<void> {
  const intent = await getStripeIntent(secretKey, intentId);
  if (intent.status === "canceled") return;
  if (!OPEN_STRIPE_INTENT_STATUSES.has(String(intent.status))) {
    throw new HttpsError("failed-precondition", "The current Stripe payment is already authorized or processing.");
  }
  const cancelled = await stripeApi<StripeIntent>(
    secretKey, "payment_intents/" + encodeURIComponent(intentId) + "/cancel", new URLSearchParams()
  );
  if (!cancelled.ok || cancelled.data.status !== "canceled") {
    throw new HttpsError("failed-precondition", "Stripe could not cancel the current payment safely.");
  }
}

/** Maps a provider-verified manual-capture PaymentIntent to the local payment state. */
async function applyStripeIntentState(
  orderId: string,
  context: PaymentContext,
  secretKey: string,
  intentId: string,
  expectedMinorUnits: number,
  expectedCurrency: string,
  cancelIfOpen: boolean
): Promise<{ accepted: boolean; message: string }> {
  const intent = await getStripeIntent(secretKey, intentId);
  if (intent.amount !== expectedMinorUnits || String(intent.currency ?? "").toUpperCase() !== expectedCurrency) {
    throw new HttpsError("failed-precondition", "The Stripe amount or currency does not match the server payment.");
  }
  const now = Date.now();
  if (intent.status === "requires_capture") {
    if (intent.amount_capturable !== expectedMinorUnits) {
      throw new HttpsError("failed-precondition", "Stripe's authorized amount does not match the server payment.");
    }
    await setPaymentStatus(orderId, context, "AUTHORIZED", {
      providerPaymentId: intentId, authorizedMinorUnits: expectedMinorUnits, authorizedAtEpochMillis: now,
      checkoutUrl: "", expiresAtEpochMillis: 0
    });
    return { accepted: true, message: "Stripe authorized the payment. It is captured only after fulfilment." };
  }
  if (intent.status === "succeeded") {
    if (intent.amount_received !== expectedMinorUnits) {
      throw new HttpsError("failed-precondition", "Stripe's received amount does not match the server payment.");
    }
    const chargeId = typeof intent.latest_charge === "string" ? intent.latest_charge : String(intent.latest_charge?.id ?? intentId);
    await setPaymentStatus(orderId, context, "SUCCEEDED", {
      providerPaymentId: intentId, providerReceiptId: chargeId, verifiedAtEpochMillis: now
    });
    return { accepted: true, message: "Stripe confirmed the payment." };
  }
  if (intent.status === "processing") {
    await setPaymentStatus(orderId, context, "PROCESSING");
    return { accepted: true, message: "Stripe is still processing the payment." };
  }
  if (intent.status === "canceled") {
    await setPaymentStatus(orderId, context, "CANCELLED", { checkoutUrl: "", expiresAtEpochMillis: 0 });
    return { accepted: true, message: "The Stripe payment was cancelled." };
  }
  if (cancelIfOpen) {
    await cancelOpenStripeIntent(secretKey, intentId);
    await setPaymentStatus(orderId, context, "CANCELLED", { checkoutUrl: "", expiresAtEpochMillis: 0 });
    return { accepted: true, message: "Provider confirmed no authorization; checkout was cancelled." };
  }
  throw new HttpsError("failed-precondition", "Stripe has not authorized this payment yet.");
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

function moneyValue(value: unknown, currencyCode = "USD"): { minorUnits: number; currencyCode: string } {
  if (!value || typeof value !== "object") return { minorUnits: 0, currencyCode };
  const money = value as Record<string, unknown>;
  const amount = Number(money.minorUnits ?? 0);
  return {
    minorUnits: Number.isSafeInteger(amount) ? amount : 0,
    currencyCode: typeof money.currencyCode === "string" ? money.currencyCode : currencyCode
  };
}

/**
 * Create wallet schema only through trusted code. Balance postings are deliberately not
 * inferred from checkout redirects: ledger accounting must allocate verified amounts first.
 */
async function ensureWallet(uid: string, currencyCode = "USD"): Promise<Record<string, unknown>> {
  if (!uid) throw new HttpsError("invalid-argument", "A wallet owner is required.");
  const ref = getDatabase().ref(`wallets/${uid}`);
  const zero = { minorUnits: 0, currencyCode };
  const result = await ref.transaction((current) => {
    const wallet = current && typeof current === "object"
      ? current as Record<string, unknown>
      : {};
    return {
      ...wallet,
      available: moneyValue(wallet.available, currencyCode),
      pending: moneyValue(wallet.pending, currencyCode),
      cashOwed: moneyValue(wallet.cashOwed, currencyCode),
      reservedForPayouts: moneyValue(wallet.reservedForPayouts, currencyCode),
      lifetimeEarnings: moneyValue(wallet.lifetimeEarnings, currencyCode),
      currencyCode: typeof wallet.currencyCode === "string" ? wallet.currencyCode : currencyCode,
      updatedAtEpochMillis: typeof wallet.updatedAtEpochMillis === "number"
        ? wallet.updatedAtEpochMillis : Date.now()
    };
  });
  return (result.snapshot.val() ?? { available: zero, pending: zero, cashOwed: zero, lifetimeEarnings: zero }) as Record<string, unknown>;
}

function walletSnapshot(wallet: Record<string, unknown>, currencyCode = "USD"): Record<string, unknown> {
  return {
    summary: {
      availableBalance: moneyValue(wallet.available, currencyCode),
      pendingBalance: moneyValue(wallet.pending, currencyCode),
      cashOwed: moneyValue(wallet.cashOwed, currencyCode),
      lifetimeEarnings: moneyValue(wallet.lifetimeEarnings, currencyCode),
      updatedAtEpochMillis: Number(wallet.updatedAtEpochMillis ?? 0)
    },
    transactions: [],
    payoutAccount: null
  };
}

async function getOrCreateStripeCustomer(uid: string, secretKey: string): Promise<string> {
  const db = getDatabase();
  const ref = db.ref(`paymentCustomers/${uid}`);
  const stored = await ref.get();
  const storedValue = stored.val() as unknown;
  // Accept the earlier object form if present, but write the canonical value as the provider ID
  // string at paymentCustomers/{uid}, keeping the schema small and outside users/{uid}.
  const existingId = typeof storedValue === "string"
    ? storedValue
    : storedValue && typeof storedValue === "object"
      ? String((storedValue as Record<string, unknown>).stripeCustomerId ?? "")
      : "";
  if (existingId.startsWith("cus_")) return existingId;

  // A stable idempotency key prevents concurrent first checkouts from creating duplicate
  // Stripe customers. Contact fields are intentionally not copied into the customer node.
  const form = new URLSearchParams();
  form.set("metadata[firebaseUid]", uid);
  const response = await fetch("https://api.stripe.com/v1/customers", {
    method: "POST",
    headers: {
      Authorization: "Bearer " + secretKey,
      "Content-Type": "application/x-www-form-urlencoded",
      "Idempotency-Key": "labmda-customer-" + uid
    },
    body: form
  });
  const customer = await response.json() as { id?: string; error?: { message?: string } };
  if (!response.ok || !customer.id || !customer.id.startsWith("cus_")) {
    throw new HttpsError("failed-precondition", customer.error?.message || "Stripe could not create a customer.");
  }
  await ref.set(customer.id);
  return customer.id;
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


type AuthoritativeAmount = { minorUnits: number; currencyCode: string };

async function recomputeAuthoritativeAmount(orderId: string, context: PaymentContext): Promise<AuthoritativeAmount> {
  const db = getDatabase();
  const paymentAmount = moneyValue(context.payment.amount);
  if (context.rootPath === "trips") {
    const snapshot = await db.ref("trips/" + orderId).get();
    if (!snapshot.exists()) throw new HttpsError("not-found", "The ride was not found.");
    const trip = snapshot.val() as Record<string, unknown>;
    const lat1 = Number(trip.pickupLat), lng1 = Number(trip.pickupLng);
    const lat2 = Number(trip.dropoffLat), lng2 = Number(trip.dropoffLng);
    if (![lat1, lng1, lat2, lng2].every(Number.isFinite)) {
      throw new HttpsError("failed-precondition", "Ride coordinates are invalid.");
    }
    const distance = distanceKm(lat1, lng1, lat2, lng2);
    if (!Number.isFinite(distance) || distance < 0.05 || distance > 1000) {
      throw new HttpsError("failed-precondition", "The stored ride distance is invalid.");
    }
    const expected = Math.round(Math.max(4, 2 + distance * 1.2) * 100);
    const storedFare = minorUnits(trip.fare, "stored ride fare");
    if (storedFare !== expected || paymentAmount.minorUnits !== expected) {
      throw new HttpsError("failed-precondition", "The ride fare changed or the payment record is inconsistent. Recreate the ride before checkout.");
    }
    return { minorUnits: expected, currencyCode: paymentAmount.currencyCode.toUpperCase() || "USD" };
  }

  const orderSnap = await db.ref("orders/" + orderId).get();
  if (!orderSnap.exists()) throw new HttpsError("not-found", "The order was not found.");
  const order = orderSnap.val() as Record<string, unknown>;
  const orderType = String(order.orderType ?? "");
  const sourceId = String(order.sourceId ?? "");
  if ((orderType !== "FOOD" && orderType !== "RETAIL") || !sourceId) {
    throw new HttpsError("failed-precondition", "The stored order type or source is invalid.");
  }
  const sourcePath = orderType === "FOOD" ? "restaurants/" + sourceId : "shops/" + sourceId;
  const sourceSnap = await db.ref(sourcePath).get();
  if (!sourceSnap.exists()) throw new HttpsError("failed-precondition", "The order source is no longer available.");
  const source = sourceSnap.val() as Record<string, unknown>;
  if (orderType === "FOOD") {
    const profile = await db.ref("users/" + sourceId).get();
    if ((profile.child("foodStatus").val() !== "APPROVED" && source.status !== "APPROVED") || source.isOpen === false) {
      throw new HttpsError("failed-precondition", "The restaurant is no longer approved or open.");
    }
  } else if (source.status !== "APPROVED" || source.isOnline === false) {
    throw new HttpsError("failed-precondition", "The shop is no longer approved or online.");
  }

  const items = Array.isArray(order.items) ? order.items as Array<Record<string, unknown>> : [];
  if (!items.length || items.length > 100) throw new HttpsError("failed-precondition", "The stored order items are invalid.");
  let subtotal = 0;
  for (const item of items) {
    const itemId = typeof item.id === "string" ? item.id.trim() : "";
    const quantity = Number(item.quantity);
    if (!itemId || !Number.isInteger(quantity) || quantity < 1 || quantity > 99) {
      throw new HttpsError("failed-precondition", "A stored order item or quantity is invalid.");
    }
    const itemPath = sourcePath + (orderType === "FOOD" ? "/meals/" : "/products/") + itemId;
    const itemSnap = await db.ref(itemPath).get();
    if (!itemSnap.exists()) throw new HttpsError("failed-precondition", "An item is no longer available.");
    const catalog = itemSnap.val() as Record<string, unknown>;
    if (catalog.isAvailable === false) throw new HttpsError("failed-precondition", "An order item is no longer available.");
    const stock = typeof catalog.stockQuantity === "number" ? catalog.stockQuantity : Number.MAX_SAFE_INTEGER;
    if (orderType === "RETAIL" && stock < quantity) throw new HttpsError("failed-precondition", "An order item is out of stock.");
    const unitMinor = minorUnits(orderType === "FOOD" ? catalog.basePrice : catalog.price, "current catalog price");
    subtotal += unitMinor * quantity;
    if (!Number.isSafeInteger(subtotal)) throw new HttpsError("out-of-range", "The order total is too large.");
  }
  let deliveryFee = 0;
  if (orderType === "FOOD") {
    deliveryFee = minorUnits(source.deliveryFee ?? 0, "current delivery fee");
  } else {
    const configuredFee = await db.ref("settings/deliveryFees/RETAIL").get();
    if (!configuredFee.exists()) throw new HttpsError("failed-precondition", "Retail delivery fee is not configured.");
    deliveryFee = minorUnits(configuredFee.val(), "current retail delivery fee");
  }
  const total = subtotal + deliveryFee;
  const storedSubtotal = minorUnits(order.subtotal, "stored subtotal");
  const storedDeliveryFee = minorUnits(order.deliveryFee, "stored delivery fee");
  const storedTotal = minorUnits(order.totalPrice, "stored order total");
  if (subtotal !== storedSubtotal || deliveryFee !== storedDeliveryFee || total !== storedTotal ||
      total !== paymentAmount.minorUnits) {
    throw new HttpsError("failed-precondition", "Prices or fees changed since this order was created. Review and place a new order before checkout.");
  }
  return { minorUnits: total, currencyCode: paymentAmount.currencyCode.toUpperCase() || "USD" };
}

async function loadPaymentContext(orderId: string): Promise<PaymentContext | null> {
  const snapshot = await getDatabase().ref("payments/" + orderId).get();
  if (!snapshot.exists()) return null;
  const payment = snapshot.val() as Record<string, unknown>;
  return { payment, rootPath: String(payment.contextType ?? "") === "RIDE" ? "trips" : "orders" };
}

async function stripeApi<T extends Record<string, unknown>>(
  path: string,
  secretKey: string,
  method = "GET",
  body?: URLSearchParams,
  idempotencyKey?: string,
  accountId?: string
): Promise<T> {
  const headers: Record<string, string> = { Authorization: "Bearer " + secretKey };
  if (body) headers["Content-Type"] = "application/x-www-form-urlencoded";
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
  if (accountId) headers["Stripe-Account"] = accountId;
  const response = await fetch("https://api.stripe.com/v1/" + path, {
    method, headers, body: body ? body.toString() : undefined
  });
  const data = await response.json() as T & { error?: { message?: string } };
  if (!response.ok) {
    throw new Error("Stripe API " + method + " " + path + " failed (" + response.status + "): " +
      String(data.error?.message ?? "provider request failed"));
  }
  return data;
}

async function stripeGatewayFee(payment: Record<string, unknown>, secretKey: string): Promise<number | null> {
  const intentId = String(payment.providerPaymentIntentId ?? "");
  if (!intentId) return null;
  const intent = await stripeApi<Record<string, unknown>>(
    "payment_intents/" + encodeURIComponent(intentId) + "?expand%5B%5D=latest_charge.balance_transaction",
    secretKey
  );
  let charge = intent.latest_charge;
  if (!charge) return null;
  if (typeof charge === "string") {
    charge = await stripeApi<Record<string, unknown>>(
      "charges/" + encodeURIComponent(charge) + "?expand%5B%5D=balance_transaction", secretKey
    );
  }
  if (!charge || typeof charge !== "object") return null;
  let balance = (charge as Record<string, unknown>).balance_transaction;
  if (typeof balance === "string") balance = await stripeApi<Record<string, unknown>>("balance_transactions/" + encodeURIComponent(balance), secretKey);
  if (!balance || typeof balance !== "object") return null;
  const balanceRecord = balance as Record<string, unknown>;
  const fee = Number(balanceRecord.fee);
  const currency = String(balanceRecord.currency ?? "").toUpperCase();
  const paymentCurrency = moneyValue(payment.amount).currencyCode.toUpperCase();
  if (currency !== paymentCurrency) return null;
  return Number.isSafeInteger(fee) && fee >= 0 ? fee : null;
}

async function postWalletSettlement(
  uid: string,
  postingId: string,
  orderId: string,
  role: string,
  creditMinorUnits: number,
  cashOwedDelta: number,
  currencyCode: string,
  provider: string
): Promise<void> {
  if (!uid || (creditMinorUnits === 0 && cashOwedDelta === 0)) return;
  await ensureWallet(uid, currencyCode);
  const walletRef = getDatabase().ref("wallets/" + uid);
  await walletRef.transaction((current) => {
    const wallet = current && typeof current === "object" ? current as Record<string, unknown> : {};
    const applied = wallet.appliedLedgerIds && typeof wallet.appliedLedgerIds === "object"
      ? wallet.appliedLedgerIds as Record<string, unknown> : {};
    if (applied[postingId] === true) return;
    const available = moneyValue(wallet.available, currencyCode);
    const cashOwed = moneyValue(wallet.cashOwed, currencyCode);
    const lifetime = moneyValue(wallet.lifetimeEarnings, currencyCode);
    if (String(wallet.currencyCode ?? currencyCode).toUpperCase() !== currencyCode.toUpperCase() ||
        available.currencyCode.toUpperCase() !== currencyCode.toUpperCase() ||
        cashOwed.currencyCode.toUpperCase() !== currencyCode.toUpperCase()) return;
    return {
      ...wallet,
      available: { minorUnits: available.minorUnits + creditMinorUnits, currencyCode },
      cashOwed: { minorUnits: cashOwed.minorUnits + cashOwedDelta, currencyCode },
      lifetimeEarnings: { minorUnits: lifetime.minorUnits + creditMinorUnits, currencyCode },
      appliedLedgerIds: { ...applied, [postingId]: true },
      updatedAtEpochMillis: Date.now()
    };
  });
  const appliedSnapshot = await walletRef.child("appliedLedgerIds/" + postingId).get();
  if (appliedSnapshot.val() !== true) throw new Error("Wallet ledger posting did not commit: " + postingId);
  const now = Date.now();
  await getDatabase().ref("ledger/" + postingId).set({
    id: postingId,
    uid,
    orderId,
    type: role === "COURIER" && cashOwedDelta > 0 ? "EARNING_AND_CASH_REMITTANCE_DUE" : "EARNING",
    status: "POSTED",
    amount: { minorUnits: creditMinorUnits, currencyCode },
    cashOwedDelta: { minorUnits: cashOwedDelta, currencyCode },
    provider,
    affectsWallet: true,
    createdAtEpochMillis: now,
    updatedAtEpochMillis: now
  });
}

async function settleCompletedPayment(orderId: string): Promise<void> {
  const context = await loadPaymentContext(orderId);
  if (!context) return;
  const payment = context.payment;
  if (String(payment.status ?? "") !== "SUCCEEDED") return;
  if (String(payment.breakdownStatus ?? "") === "ALLOCATED") return;
  const rootSnap = await getDatabase().ref(context.rootPath + "/" + orderId).get();
  if (!rootSnap.exists()) return;
  const root = rootSnap.val() as Record<string, unknown>;
  const terminal = context.rootPath === "trips"
    ? String(root.status ?? "") === "COMPLETED"
    : String(root.status ?? "") === "DELIVERED";
  if (!terminal) return;

  const amount = moneyValue(payment.amount);
  const provider = normalizePaymentMethod(payment.provider ?? payment.method);
  let gatewayFee: number | null = null;
  if (provider === "CASH") {
    gatewayFee = 0;
  } else if (payment.gatewayFeeVerified === true) {
    gatewayFee = moneyValue(payment.breakdown && (payment.breakdown as Record<string, unknown>).gatewayFee, amount.currencyCode).minorUnits;
  } else if (provider === "STRIPE") {
    try { gatewayFee = await stripeGatewayFee(payment, stripeSecretKey.value().trim()); } catch { gatewayFee = null; }
  } else if (provider === "PAYPAL" && typeof payment.gatewayFeeMinorUnits === "number" &&
      String(payment.gatewayFeeCurrencyCode ?? "").toUpperCase() === amount.currencyCode.toUpperCase()) {
    gatewayFee = Number(payment.gatewayFeeMinorUnits);
  }
  if (gatewayFee === null || !Number.isSafeInteger(gatewayFee) || gatewayFee < 0) {
    await getDatabase().ref("payments/" + orderId).update({ breakdownStatus: "AWAITING_GATEWAY_FEE", updatedAtEpochMillis: Date.now() });
    return;
  }

  const settingsSnap = await getDatabase().ref("config/commission").get();
  const settings = (settingsSnap.val() ?? {}) as Record<string, unknown>;
  const platformBps = Number(settings.platformCommissionBps ?? 1000);
  const courierBps = Number(settings.courierShareBps ?? 0);
  if (!Number.isInteger(platformBps) || platformBps < 0 || platformBps > 10000 ||
      !Number.isInteger(courierBps) || courierBps < 0 || courierBps > 10000 ||
      platformBps + courierBps > 10000) {
    throw new Error("Commission configuration is invalid; refusing wallet settlement.");
  }
  const currencyCode = amount.currencyCode.toUpperCase();
  if (typeof settings.currencyCode === "string" && settings.currencyCode.toUpperCase() !== currencyCode) {
    throw new Error("Payment currency does not match commission configuration; refusing settlement.");
  }

  let merchantShare = 0;
  let courierShare = 0;
  let platformShare = 0;
  const courierId = String(payment.courierId || (context.rootPath === "trips" ? root.driverId ?? "" : root.deliveryId ?? "") || "");
  if (context.rootPath === "trips") {
    if (!courierId) throw new Error("Completed ride has no assigned driver; refusing wallet settlement.");
    platformShare = Math.floor(amount.minorUnits * platformBps / 10000);
    courierShare = amount.minorUnits - platformShare - gatewayFee;
  } else {
    const subtotal = minorUnits(root.subtotal, "order subtotal");
    const deliveryFee = minorUnits(root.deliveryFee, "order delivery fee");
    const commission = Math.floor(subtotal * platformBps / 10000);
    merchantShare = subtotal - commission;
    courierShare = courierId ? Math.floor(deliveryFee * courierBps / 10000) : 0;
    platformShare = amount.minorUnits - merchantShare - courierShare - gatewayFee;
  }
  if ([merchantShare, courierShare, platformShare].some(value => !Number.isSafeInteger(value) || value < 0) ||
      merchantShare + courierShare + platformShare + gatewayFee !== amount.minorUnits) {
    throw new Error("Computed settlement split is invalid; refusing wallet settlement.");
  }

  await getDatabase().ref("payments/" + orderId).update({ breakdownStatus: "ALLOCATING", updatedAtEpochMillis: Date.now() });
  const merchantId = String(payment.merchantId ?? "");
  if (merchantId && merchantShare > 0) {
    await postWalletSettlement(merchantId, "settlement_" + orderId + "_merchant", orderId, "MERCHANT", merchantShare, 0, currencyCode, provider);
  }
  if (courierId && (courierShare > 0 || (provider === "CASH" && amount.minorUnits - courierShare > 0))) {
    const cashOwed = provider === "CASH" ? amount.minorUnits - courierShare : 0;
    await postWalletSettlement(courierId, "settlement_" + orderId + "_courier", orderId, "COURIER", courierShare, cashOwed, currencyCode, provider);
  }
  if (platformShare > 0) {
    await postWalletSettlement("platform", "settlement_" + orderId + "_platform", orderId, "PLATFORM", platformShare, 0, currencyCode, provider);
  }
  const breakdown = {
    merchantShare: { minorUnits: merchantShare, currencyCode },
    courierShare: { minorUnits: courierShare, currencyCode },
    platformShare: { minorUnits: platformShare, currencyCode },
    gatewayFee: { minorUnits: gatewayFee, currencyCode }
  };
  await getDatabase().ref("payments/" + orderId).update({
    breakdown,
    breakdownStatus: "ALLOCATED",
    gatewayFeeVerified: true,
    settledAtEpochMillis: Date.now(),
    updatedAtEpochMillis: Date.now()
  });
  await getDatabase().ref("ledger/settlement_" + orderId + "_summary").set({
    id: "settlement_" + orderId + "_summary",
    orderId,
    entryType: "PAYMENT_SPLIT_ALLOCATED",
    clientId: payment.clientId ?? "",
    merchantId,
    courierId,
    provider,
    status: "POSTED",
    amount,
    breakdown,
    breakdownStatus: "ALLOCATED",
    affectsWallet: false,
    createdAtEpochMillis: Date.now()
  });
}

async function captureAuthorizedPayment(orderId: string, context: PaymentContext): Promise<void> {
  const payment = context.payment;
  const provider = normalizePaymentMethod(payment.provider ?? payment.method);
  if (provider === "STRIPE") {
    const intentId = String(payment.providerPaymentIntentId ?? "");
    if (!intentId) return; // The authorization webhook will retry capture after it records the intent.
    const key = stripeSecretKey.value().trim();
    const intent = await stripeApi<Record<string, unknown>>("payment_intents/" + encodeURIComponent(intentId), key);
    if (intent.status === "succeeded") return;
    if (intent.status !== "requires_capture") return;
    await stripeApi<Record<string, unknown>>(
      "payment_intents/" + encodeURIComponent(intentId) + "/capture", key, "POST",
      new URLSearchParams(), "labmda-capture-" + orderId
    );
  } else if (provider === "PAYPAL") {
    const authorizationId = String(payment.providerAuthorizationId ?? "");
    if (!authorizationId) return;
    const access = await getPayPalAccessToken();
    const statusResponse = await fetch(access.baseUrl + "/v2/payments/authorizations/" + encodeURIComponent(authorizationId), {
      headers: { Authorization: "Bearer " + access.token }
    });
    const authorization = await statusResponse.json() as { id?: string; status?: string };
    if (!statusResponse.ok || authorization.id !== authorizationId) throw new Error("PayPal authorization lookup failed.");
    if (authorization.status === "CAPTURED") return;
    if (authorization.status !== "CREATED") return;
    const captureResponse = await fetch(access.baseUrl + "/v2/payments/authorizations/" + encodeURIComponent(authorizationId) + "/capture", {
      method: "POST",
      headers: { Authorization: "Bearer " + access.token, "Content-Type": "application/json", "PayPal-Request-Id": "labmda-capture-" + orderId },
      body: "{}"
    });
    const capture = await captureResponse.json() as { id?: string; status?: string };
    if (!captureResponse.ok) throw new Error("PayPal authorization capture failed.");
    // Payment status is set by the verified capture webhook, not by this request response.
  }
}

async function releasePaymentAuthorization(orderId: string, context: PaymentContext): Promise<void> {
  const payment = context.payment;
  const provider = normalizePaymentMethod(payment.provider ?? payment.method);
  if (provider === "CASH") {
    await setPaymentStatus(orderId, context, "CANCELLED", { cancelReason: "ORDER_CANCELLED" });
    return;
  }
  if (provider === "STRIPE") {
    const key = stripeSecretKey.value().trim();
    const intentId = String(payment.providerPaymentIntentId ?? "");
    if (intentId) {
      const intent = await stripeApi<Record<string, unknown>>("payment_intents/" + encodeURIComponent(intentId), key);
      if (intent.status === "requires_capture") {
        await stripeApi<Record<string, unknown>>(
          "payment_intents/" + encodeURIComponent(intentId) + "/cancel", key, "POST",
          new URLSearchParams(), "labmda-cancel-" + orderId
        );
      } else if (intent.status === "succeeded" && String(payment.breakdownStatus ?? "") !== "ALLOCATED") {
        const total = moneyValue(payment.amount);
        const refunded = moneyValue(payment.refundAmount, total.currencyCode);
        const remaining = total.minorUnits - refunded.minorUnits;
        if (remaining > 0) await submitProviderRefund(orderId, context, remaining, "Order cancelled before settlement");
      }
      return;
    }
    const sessionId = String(payment.providerReference || payment.providerPaymentId || "");
    if (sessionId.startsWith("cs_")) {
      const session = await stripeApi<Record<string, unknown>>("checkout/sessions/" + encodeURIComponent(sessionId), key);
      if (session.status === "open") {
        await stripeApi<Record<string, unknown>>(
          "checkout/sessions/" + encodeURIComponent(sessionId) + "/expire", key, "POST",
          new URLSearchParams(), "labmda-expire-" + orderId
        );
        return;
      }
      if (session.status === "expired") {
        await setPaymentStatus(orderId, context, "CANCELLED", { cancelReason: "CHECKOUT_EXPIRED" });
        return;
      }
      const linkedIntent = typeof session.payment_intent === "string" ? session.payment_intent :
        String((session.payment_intent as Record<string, unknown> | undefined)?.id ?? "");
      if (linkedIntent) {
        const intent = await stripeApi<Record<string, unknown>>("payment_intents/" + encodeURIComponent(linkedIntent), key);
        await getDatabase().ref("payments/" + orderId).update({ providerPaymentIntentId: linkedIntent });
        if (intent.status === "requires_capture") {
          await stripeApi<Record<string, unknown>>(
            "payment_intents/" + encodeURIComponent(linkedIntent) + "/cancel", key, "POST",
            new URLSearchParams(), "labmda-cancel-" + orderId
          );
          return;
        }
        if (intent.status === "succeeded" && String(payment.breakdownStatus ?? "") !== "ALLOCATED") {
          const total = moneyValue(payment.amount);
          const refunded = moneyValue(payment.refundAmount, total.currencyCode);
          const remaining = total.minorUnits - refunded.minorUnits;
          if (remaining > 0) await submitProviderRefund(orderId, context, remaining, "Order cancelled before settlement");
          return;
        }
      }
      await getDatabase().ref("payments/" + orderId).update({
        requiresAdminReconciliation: true,
        reconciliationReason: "Order cancellation could not release or refund the provider payment automatically.",
        updatedAtEpochMillis: Date.now()
      });
      return;
    }
    await setPaymentStatus(orderId, context, "CANCELLED", { cancelReason: "ORDER_CANCELLED_BEFORE_PROVIDER_SESSION" });
  } else if (provider === "PAYPAL") {
    const authorizationId = String(payment.providerAuthorizationId ?? "");
    const access = await getPayPalAccessToken();
    if (!authorizationId) {
      const orderIdRef = String(payment.providerReference ?? payment.providerPaymentId ?? "");
      if (orderIdRef) {
        const orderResponse = await fetch(access.baseUrl + "/v2/checkout/orders/" + encodeURIComponent(orderIdRef), {
          headers: { Authorization: "Bearer " + access.token }
        });
        const providerOrder = await orderResponse.json() as {
          purchase_units?: Array<{ payments?: { authorizations?: Array<{ id?: string }> } }>;
        };
        const storedAuthorizationId = String(providerOrder.purchase_units?.[0]?.payments?.authorizations?.[0]?.id ?? "");
        if (orderResponse.ok && storedAuthorizationId) {
          await getDatabase().ref("payments/" + orderId + "/providerAuthorizationId").set(storedAuthorizationId);
          const voidResponse = await fetch(access.baseUrl + "/v2/payments/authorizations/" + encodeURIComponent(storedAuthorizationId) + "/void", {
            method: "POST",
            headers: { Authorization: "Bearer " + access.token, "PayPal-Request-Id": "labmda-void-" + orderId }
          });
          if (!voidResponse.ok && voidResponse.status !== 422) throw new Error("PayPal authorization void failed.");
          return;
        }
      }
      await setPaymentStatus(orderId, context, "CANCELLED", { cancelReason: "ORDER_CANCELLED_BEFORE_AUTHORIZATION" });
      return;
    }
    const response = await fetch(access.baseUrl + "/v2/payments/authorizations/" + encodeURIComponent(authorizationId) + "/void", {
      method: "POST",
      headers: { Authorization: "Bearer " + access.token, "PayPal-Request-Id": "labmda-void-" + orderId }
    });
    if (!response.ok && response.status !== 422) throw new Error("PayPal authorization void failed.");
  }
}

async function maybeCaptureAfterAuthorization(orderId: string): Promise<void> {
  const context = await loadPaymentContext(orderId);
  if (!context) return;
  const root = await getDatabase().ref(context.rootPath + "/" + orderId).get();
  if (!root.exists()) return;
  const status = String(root.child("status").val() ?? "");
  // Handle delayed/reordered provider events: if acceptance has already advanced the
  // order before the authorization webhook arrives, still capture the valid authorization.
  const shouldCapture = context.rootPath === "trips"
    ? status === "COMPLETED"
    : ["ACCEPTED", "PREPARING", "OUT_FOR_DELIVERY", "DELIVERED"].includes(status);
  if (shouldCapture && String(context.payment.status ?? "") === "AUTHORIZED") {
    await captureAuthorizedPayment(orderId, context);
  } else if (context.rootPath === "orders" && status === "DELIVERED" && String(context.payment.status ?? "") === "SUCCEEDED") {
    await settleCompletedPayment(orderId);
  } else if (context.rootPath === "trips" && status === "COMPLETED" && String(context.payment.status ?? "") === "SUCCEEDED") {
    await settleCompletedPayment(orderId);
  }
}

async function findPaymentByProviderReference(reference: string): Promise<{ orderId: string; context: PaymentContext } | null> {
  if (!reference) return null;
  const snapshot = await getDatabase().ref("payments").orderByChild("providerReference").equalTo(reference).limitToFirst(2).get();
  const raw = snapshot.val() as Record<string, unknown> | null;
  const entries = Object.entries(raw ?? {});
  if (entries.length !== 1) return null;
  const orderId = entries[0][0];
  const payment = entries[0][1] as Record<string, unknown>;
  return { orderId, context: { payment, rootPath: String(payment.contextType ?? "") === "RIDE" ? "trips" : "orders" } };
}

function stripeSignatureValid(rawBody: Buffer, signatureHeader: string, secret: string): boolean {
  const parts = signatureHeader.split(",").map(part => part.trim());
  const timestamp = parts.find(part => part.startsWith("t="))?.slice(2) ?? "";
  const signatures = parts.filter(part => part.startsWith("v1=")).map(part => part.slice(3));
  const seconds = Number(timestamp);
  if (!timestamp || !Number.isFinite(seconds) || Math.abs(Date.now() / 1000 - seconds) > 300 || signatures.length === 0) return false;
  const expected = createHmac("sha256", secret).update(timestamp + "." + rawBody.toString("utf8")).digest();
  return signatures.some(signature => {
    try {
      const received = Buffer.from(signature, "hex");
      return received.length === expected.length && timingSafeEqual(received, expected);
    } catch { return false; }
  });
}

async function verifyPayPalWebhook(event: Record<string, unknown>, headers: Record<string, string | string[] | undefined>): Promise<boolean> {
  const webhookId = paypalWebhookId.value().trim();
  if (!webhookId) return false;
  const access = await getPayPalAccessToken();
  const payload = {
    auth_algo: firstHeader(headers["paypal-auth-algo"]),
    cert_url: firstHeader(headers["paypal-cert-url"]),
    transmission_id: firstHeader(headers["paypal-transmission-id"]),
    transmission_sig: firstHeader(headers["paypal-transmission-sig"]),
    transmission_time: firstHeader(headers["paypal-transmission-time"]),
    webhook_id: webhookId,
    webhook_event: event
  };
  const response = await fetch(access.baseUrl + "/v1/notifications/verify-webhook-signature", {
    method: "POST",
    headers: { Authorization: "Bearer " + access.token, "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  const result = await response.json() as { verification_status?: string };
  return response.ok && result.verification_status === "SUCCESS";
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
      typeof extraPaymentFields.provider === "string" ? extraPaymentFields.provider :
      typeof extraPaymentFields.method === "string" ? extraPaymentFields.method :
      String(context.payment.provider ?? context.payment.method ?? "CASH"),
    [`payments/${orderId}/providerReference`]:
      typeof extraPaymentFields.providerReference === "string" ? extraPaymentFields.providerReference :
      typeof extraPaymentFields.providerPaymentId === "string" ? extraPaymentFields.providerPaymentId :
      String(context.payment.providerReference || context.payment.providerPaymentId || ""),
    [`${context.rootPath}/${orderId}/paymentStatus`]: status,
    [`${context.rootPath}/${orderId}/paymentId`]: orderId,
    [`${context.rootPath}/${orderId}/paymentMethod`]:
      typeof extraPaymentFields.provider === "string" ? extraPaymentFields.provider :
      typeof extraPaymentFields.method === "string" ? extraPaymentFields.method : String(context.payment.provider ?? context.payment.method ?? "CASH")
  });

  if (status === "SUCCEEDED") {
    // Deterministic ledger key makes repeated verification idempotent. This records the gross
    // provider confirmation only; it explicitly does not post any wallet balance until the
    // final shares and actual gateway fee are allocated by the trusted accounting flow.
    const ledgerId = "payment_" + orderId + "_settled";
    const paymentProvider = String(extraPaymentFields.provider ?? context.payment.provider ?? context.payment.method ?? "CASH");
    const providerReference = String(
      extraPaymentFields.providerReference ??
      extraPaymentFields.providerPaymentId ??
      context.payment.providerReference ??
      context.payment.providerPaymentId ?? ""
    );
    await getDatabase().ref("ledger/" + ledgerId).set({
      id: ledgerId,
      entryType: "PAYMENT_CONFIRMED_GROSS",
      orderId,
      clientId: context.payment.clientId ?? "",
      merchantId: context.payment.merchantId ?? "",
      merchantIds: context.payment.merchantIds ?? {},
      courierId: context.payment.courierId ?? "",
      provider: paymentProvider,
      providerReference,
      status: "SUCCEEDED",
      amount: context.payment.amount ?? { minorUnits: 0, currencyCode: "USD" },
      breakdown: context.payment.breakdown ?? {},
      breakdownStatus: String(context.payment.breakdownStatus ?? "UNALLOCATED"),
      affectsWallet: false,
      createdAtEpochMillis: now
    });
  }
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
  const db = getDatabase();
  const { source, subtotal, fee, total, normalizedItems } = await quoteOrder(orderType, sourceId, body.items);
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
    breakdownStatus: "UNALLOCATED",
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
  await ensureWallet(merchantId, "USD");
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
  // Recalculated from validated coordinates on the server; a client estimate is never used.
  const fareMinorUnits = rideFareMinorUnits(pickupLat, pickupLng, dropoffLat, dropoffLng);
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
    breakdownStatus: "UNALLOCATED",
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
  if (targetDriverUid) await ensureWallet(targetDriverUid, "USD");
  return { tripId, fareMinorUnits, currencyCode: "USD" };
});


/**
 * Starts provider checkout for a server-owned order or ride.
 *
 * Reads `orders/{id}` (or `trips/{id}` for rides), recomputes the amount from trusted RTDB data
 * (current catalog prices, delivery fees, ride coordinates) and refuses to continue if it no longer
 * matches the total the customer was shown. Stripe uses a PaymentIntent with manual capture, so
 * funds are only authorized here and captured later by trusted fulfilment code; PayPal uses a
 * hosted order approval. The result is written to `payments/{id}`. Only display-safe values are
 * returned: the PaymentIntent client secret, the PayPal approval URL, and opaque provider IDs.
 */
export const createPaymentSession = onCall({
  enforceAppCheck: true,
  secrets: [stripeSecretKey, paypalClientId, paypalClientSecret]
}, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before starting checkout.");
  const body = (request.data ?? {}) as { orderId?: string; method?: string };
  const orderId = typeof body.orderId === "string" ? body.orderId.trim() : "";
  const method = normalizePaymentMethod(body.method);
  if (!isSafeKey(orderId)) throw new HttpsError("invalid-argument", "A valid orderId is required.");
  if (method !== "STRIPE" && method !== "PAYPAL") {
    throw new HttpsError("invalid-argument", "Online checkout requires Stripe or PayPal.");
  }

  const db = getDatabase();
  const [orderSnap, paymentSnap] = await Promise.all([
    db.ref(`orders/${orderId}`).get(),
    db.ref(`payments/${orderId}`).get()
  ]);
  let rootPath: "orders" | "trips" = "orders";
  let record: Record<string, unknown>;
  if (orderSnap.exists()) {
    record = orderSnap.val() as Record<string, unknown>;
  } else {
    const tripSnap = await db.ref(`trips/${orderId}`).get();
    if (!tripSnap.exists()) throw new HttpsError("not-found", "Order not found.");
    rootPath = "trips";
    record = tripSnap.val() as Record<string, unknown>;
  }
  const existingPayment = paymentSnap.exists() ? paymentSnap.val() as Record<string, unknown> : null;
  const ownerId = String((rootPath === "orders" ? record.clientId : record.passengerId) ?? "");
  if (ownerId !== uid || (existingPayment && existingPayment.clientId !== uid)) {
    throw new HttpsError("permission-denied", "This order does not belong to you.");
  }
  const recordStatus = String(record.status ?? "");
  const payable = rootPath === "orders"
    ? recordStatus === "PLACED"
    : recordStatus !== "CANCELLED" && recordStatus !== "COMPLETED";
  if (!payable) throw new HttpsError("failed-precondition", "This order can no longer be paid online.");

  const currentStatus = String(existingPayment?.status ?? "PENDING");
  if (SETTLED_PAYMENT_STATUSES.has(currentStatus)) {
    throw new HttpsError("failed-precondition", "This payment has already been settled.");
  }
  if (currentStatus === "AUTHORIZED" || currentStatus === "PROCESSING") {
    throw new HttpsError("failed-precondition", "This payment is already authorized or processing.");
  }

  // Recompute the amount from trusted data. Never use a client value or the stored payment amount.
  const currencyCode = "USD";
  let amountMinorUnits: number;
  let merchantIds: Record<string, true> = {};
  if (rootPath === "orders") {
    const orderType = record.orderType;
    if (orderType !== "FOOD" && orderType !== "RETAIL") {
      throw new HttpsError("failed-precondition", "This order has an unsupported type.");
    }
    const sourceId = String(record.sourceId ?? "");
    const quote = await quoteOrder(orderType, sourceId, record.items);
    amountMinorUnits = quote.total;
    merchantIds = merchantAccessIds(quote.source, sourceId);
    if (amountMinorUnits !== Math.round(Number(record.totalPrice) * 100)) {
      throw new HttpsError("failed-precondition",
        "Prices or fees changed since this order was placed. Place the order again to see the current total.");
    }
  } else {
    amountMinorUnits = rideFareMinorUnits(
      Number(record.pickupLat), Number(record.pickupLng), Number(record.dropoffLat), Number(record.dropoffLng)
    );
    if (amountMinorUnits !== Math.round(Number(record.fare) * 100)) {
      throw new HttpsError("failed-precondition", "The ride fare could not be confirmed. Request the ride again.");
    }
  }
  if (!Number.isSafeInteger(amountMinorUnits) || amountMinorUnits <= 0) {
    throw new HttpsError("failed-precondition", "The server payment amount is invalid.");
  }

  const stripeKey = stripeSecretKey.value().trim();
  const requireStripeKey = () => {
    if (!stripeKey) throw new HttpsError("failed-precondition", "Stripe server credentials are not configured.");
    return stripeKey;
  };
  const existingMethod = normalizePaymentMethod(existingPayment?.method);
  const existingId = String(existingPayment?.providerPaymentId ?? "");
  const existingAmount = moneyValue(existingPayment?.amount, currencyCode);
  const sameAmount = existingAmount.minorUnits === amountMinorUnits && existingAmount.currencyCode === currencyCode;
  const description = rootPath === "trips" ? "Ride fare" : "Order payment";
  const contextType = rootPath === "trips" ? "RIDE" : "ORDER";

  // Reuse or retire any checkout that is still open, so one order never has two live payments.
  if (currentStatus === "REQUIRES_ACTION" && existingId) {
    if (existingId.startsWith("pi_")) {
      const key = requireStripeKey();
      const intent = await getStripeIntent(key, existingId);
      if (method === "STRIPE" && sameAmount && OPEN_STRIPE_INTENT_STATUSES.has(String(intent.status)) &&
          intent.capture_method === "manual" && intent.amount === amountMinorUnits &&
          String(intent.currency ?? "").toUpperCase() === currencyCode && intent.client_secret) {
        return {
          orderId, provider: method, sessionId: existingId, checkoutUrl: "", clientSecret: intent.client_secret,
          captureMethod: "MANUAL", amountMinorUnits, currencyCode, expiresAtEpochMillis: 0
        };
      }
      if (intent.status !== "canceled") await cancelOpenStripeIntent(key, existingId);
    } else if (Number(existingPayment?.expiresAtEpochMillis ?? 0) > Date.now()) {
      const existingUrl = String(existingPayment?.checkoutUrl ?? "");
      if (existingMethod === "PAYPAL" && method === "PAYPAL" && sameAmount && existingUrl.startsWith("https://")) {
        return {
          orderId, provider: method, sessionId: existingId, checkoutUrl: existingUrl, clientSecret: "",
          captureMethod: "AUTOMATIC", amountMinorUnits, currencyCode,
          expiresAtEpochMillis: Number(existingPayment?.expiresAtEpochMillis)
        };
      }
      throw new HttpsError("failed-precondition", "Finish or cancel the current checkout before changing payment methods.");
    }
  }

  // Deterministic per order/amount/previous attempt: concurrent retries reuse one provider object,
  // while a new attempt after a cancellation gets a fresh one.
  const idempotencyKey = ["labmda", method.toLowerCase(), orderId, String(amountMinorUnits), currencyCode, existingId || "initial"]
    .join("-").slice(0, 100);
  let sessionId = "";
  let checkoutUrl = "";
  let clientSecret = "";
  let expiresAtEpochMillis = 0;

  if (method === "STRIPE") {
    const key = requireStripeKey();
    const customerId = await getOrCreateStripeCustomer(uid, key);
    const form = new URLSearchParams();
    form.set("amount", String(amountMinorUnits));
    form.set("currency", currencyCode.toLowerCase());
    form.set("customer", customerId);
    form.set("capture_method", "manual");
    form.set("automatic_payment_methods[enabled]", "true");
    // Keep confirmation inside the in-app payment sheet; no redirect-based methods.
    form.set("automatic_payment_methods[allow_redirects]", "never");
    form.set("description", description);
    form.set("metadata[orderId]", orderId);
    form.set("metadata[contextType]", contextType);
    form.set("metadata[firebaseUid]", uid);
    const { ok, data } = await stripeApi<StripeIntent>(key, "payment_intents", form, idempotencyKey);
    if (!ok || !data.id?.startsWith("pi_") || !data.client_secret || data.capture_method !== "manual" ||
        data.amount !== amountMinorUnits || !OPEN_STRIPE_INTENT_STATUSES.has(String(data.status))) {
      throw new HttpsError("failed-precondition", data.error?.message || "Stripe could not create the payment.");
    }
    sessionId = data.id;
    clientSecret = data.client_secret;
  } else {
    const access = await getPayPalAccessToken();
    const returnUrl = "labmda://payment-return?provider=paypal&orderId=" + encodeURIComponent(orderId);
    const paypalBody = {
      intent: "AUTHORIZE",
      purchase_units: [{
        reference_id: orderId,
        custom_id: orderId,
        description,
        amount: { currency_code: currencyCode, value: (amountMinorUnits / 100).toFixed(2) }
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
        "Content-Type": "application/json",
        "PayPal-Request-Id": idempotencyKey
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
    expiresAtEpochMillis = Date.now() + 30 * 60 * 1000;
  }

  // Re-read immediately before storing the hosted URL. An order may be cancelled while
  // the provider API call is in flight; do not return a live checkout URL in that case.
  const latestRoot = await getDatabase().ref(context.rootPath + "/" + orderId).get();
  const latestPaymentSnapshot = await getDatabase().ref("payments/" + orderId).get();
  const latestRootStatus = String(latestRoot.child("status").val() ?? "");
  const latestPayment = latestPaymentSnapshot.val() as Record<string, unknown> | null;
  if (!latestRoot.exists() || ["CANCELLED", "CANCELED", "DELIVERED", "COMPLETED"].includes(latestRootStatus) ||
      String(latestPayment?.status ?? "") === "CANCELLED") {
    if (method === "STRIPE") {
      try {
        await stripeApi<Record<string, unknown>>(
          "checkout/sessions/" + encodeURIComponent(sessionId) + "/expire",
          stripeSecretKey.value().trim(), "POST", new URLSearchParams(), "labmda-cancel-session-" + orderId
        );
      } catch { /* Best effort only; no checkout URL is returned to the client. */ }
    }
    throw new HttpsError("failed-precondition", "The order was cancelled while checkout was being prepared.");
  }

  const now = Date.now();
  const money = (minor: number) => ({ minorUnits: minor, currencyCode });
  const paymentFields: Record<string, unknown> = {
    // Backfill the server-owned base record if it is missing (normally written by createOrder/createRideTrip).
    ...(existingPayment ? {} : {
      orderId, contextType, clientId: uid,
      merchantId: rootPath === "orders" ? String(record.merchantId ?? "") : "",
      merchantIds,
      courierId: String((rootPath === "orders" ? record.deliveryId : record.driverId) ?? ""),
      breakdownStatus: "UNALLOCATED",
      breakdown: {
        merchantShare: money(0), courierShare: money(0), platformShare: money(0), gatewayFee: money(0)
      },
      refundAmount: money(0)
    }),
    provider: method,
    providerReference: sessionId,
    method,
    providerPaymentId: sessionId,
    captureMethod: method === "STRIPE" ? "MANUAL" : "AUTOMATIC",
    amount: money(amountMinorUnits),
    checkoutUrl,
    expiresAtEpochMillis,
    status: "REQUIRES_ACTION",
    updatedAtEpochMillis: now
  };
  await db.ref().update({
    ...Object.fromEntries(Object.entries(paymentFields).map(([field, value]) => [`payments/${orderId}/${field}`, value])),
    [`${rootPath}/${orderId}/paymentMethod`]: method,
    [`${rootPath}/${orderId}/paymentStatus`]: "REQUIRES_ACTION",
    [`${rootPath}/${orderId}/paymentId`]: orderId
  });
  return {
    orderId, provider: method, sessionId, checkoutUrl, clientSecret,
    captureMethod: method === "STRIPE" ? "MANUAL" : "AUTOMATIC",
    amountMinorUnits, currencyCode, expiresAtEpochMillis
  };
});

/** Mark cash-on-delivery as requested, never as paid. */
export const requestCashPayment = onCall({ enforceAppCheck: true, secrets: [stripeSecretKey] }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before requesting cash payment.");
  const body = request.data as { orderId?: string };
  const orderId = typeof body.orderId === "string" ? body.orderId.trim() : "";
  if (!orderId) throw new HttpsError("invalid-argument", "orderId is required.");
  const context = await getOwnedPaymentContext(orderId, uid);
  const status = String(context.payment.status ?? "PENDING");
  if (SETTLED_PAYMENT_STATUSES.has(status)) {
    throw new HttpsError("failed-precondition", "This payment has already been settled.");
  }
  if (status === "AUTHORIZED" || status === "PROCESSING") {
    throw new HttpsError("failed-precondition", "An online payment is already authorized or processing for this order.");
  }
  const existingSessionId = String(context.payment.providerPaymentId ?? "");
  const existingExpiry = Number(context.payment.expiresAtEpochMillis ?? 0);
  if (status === "REQUIRES_ACTION" && existingSessionId.startsWith("pi_")) {
    const key = stripeSecretKey.value().trim();
    if (!key) throw new HttpsError("failed-precondition", "Stripe server credentials are not configured.");
    await cancelOpenStripeIntent(key, existingSessionId);
  } else if (status === "REQUIRES_ACTION" && existingSessionId && existingExpiry > Date.now()) {
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
  if (normalizePaymentMethod(payment.provider ?? payment.method) !== provider) {
    throw new HttpsError("permission-denied", "This provider does not match the payment method.");
  }
  const recordedSessionId = String(payment.providerReference || payment.providerPaymentId || "");
  if (!recordedSessionId || (resultToken !== "cancel" && (sessionId !== recordedSessionId || resultToken !== recordedSessionId))) {
    throw new HttpsError("permission-denied", "The returned session does not match this payment.");
  }
  if (String(payment.status ?? "") === "SUCCEEDED") {
    return { accepted: true, message: "Payment is already confirmed by the server." };
  }

  // The cancel redirect is only a request. Query the provider before changing the state.
  if (resultToken === "cancel") {
    if (!recordedSessionId) {
      await setPaymentStatus(orderId, context, "CANCELLED", { checkoutUrl: "", expiresAtEpochMillis: 0 });
      return { accepted: true, message: "Checkout was cancelled before a provider session was created." };
    }
    if (provider === "STRIPE" && recordedSessionId.startsWith("pi_")) {
      const key = stripeSecretKey.value().trim();
      if (!key) throw new HttpsError("failed-precondition", "Stripe server credentials are not configured.");
      return applyStripeIntentState(orderId, context, key, recordedSessionId, expectedMinorUnits, expectedCurrency, true);
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

  const expected = await recomputeAuthoritativeAmount(orderId, context);
  const keyAmount = moneyValue(payment.amount);
  if (expected.minorUnits !== keyAmount.minorUnits ||
      expected.currencyCode.toUpperCase() !== keyAmount.currencyCode.toUpperCase()) {
    throw new HttpsError("failed-precondition", "The server payment amount is inconsistent.");
  }
  if (resultToken === "cancel") {
    // A provider redirect is not the order-cancellation authority. The order status trigger
    // will release a real authorization only after the order itself enters CANCELLED.
    return { accepted: true, message: "Checkout return received. Payment state remains provider-webhook authoritative." };
  }

  if (provider === "STRIPE" && recordedSessionId.startsWith("pi_")) {
    const key = stripeSecretKey.value().trim();
    if (!key) throw new HttpsError("failed-precondition", "Stripe server credentials are not configured.");
    return applyStripeIntentState(orderId, context, key, recordedSessionId, expectedMinorUnits, expectedCurrency, false);
  }
  if (provider === "STRIPE") {
    const secret = stripeSecretKey.value().trim();
    if (!secret) throw new HttpsError("failed-precondition", "Stripe server credentials are not configured.");
    const response = await fetch("https://api.stripe.com/v1/checkout/sessions/" + encodeURIComponent(sessionId), {
      headers: { Authorization: "Bearer " + secret }
    });
    const session = await response.json() as {
      id?: string; status?: string; payment_status?: string; amount_total?: number;
      currency?: string; payment_intent?: string | { id?: string };
    };
    if (!response.ok || session.id !== recordedSessionId) {
      throw new HttpsError("failed-precondition", "Stripe could not verify this checkout session.");
    }
    if (session.amount_total !== expected.minorUnits ||
        String(session.currency ?? "").toUpperCase() !== expected.currencyCode.toUpperCase()) {
      throw new HttpsError("failed-precondition", "Stripe's amount or currency does not match the server order.");
    }
    if (session.status === "expired") {
      return { accepted: true, message: "Stripe checkout expired; payment state will be reconciled by provider events." };
    }
    const intentId = typeof session.payment_intent === "string"
      ? session.payment_intent : String(session.payment_intent?.id ?? "");
    if (!intentId) throw new HttpsError("failed-precondition", "Stripe has not created a PaymentIntent for this session.");
    const intentResponse = await fetch("https://api.stripe.com/v1/payment_intents/" + encodeURIComponent(intentId), {
      headers: { Authorization: "Bearer " + secret }
    });
    const intent = await intentResponse.json() as {
      id?: string; status?: string; amount?: number; amount_capturable?: number;
      currency?: string; latest_charge?: string | { id?: string };
    };
    if (!intentResponse.ok || intent.id !== intentId ||
        intent.amount !== expected.minorUnits ||
        String(intent.currency ?? "").toUpperCase() !== expected.currencyCode.toUpperCase()) {
      throw new HttpsError("failed-precondition", "Stripe's PaymentIntent does not match the server order.");
    }
    if (intent.status !== "requires_capture" && intent.status !== "succeeded") {
      throw new HttpsError("failed-precondition", "Stripe has not created a valid authorization or captured payment.");
    }
    await getDatabase().ref("payments/" + orderId).update({
      providerPaymentIntentId: intentId,
      updatedAtEpochMillis: Date.now()
    });
    return { accepted: true, message: "Stripe status checked. The signed webhook is the source of truth for payment state." };
  }

  const access = await getPayPalAccessToken();
  const response = await fetch(access.baseUrl + "/v2/checkout/orders/" + encodeURIComponent(sessionId), {
    headers: { Authorization: "Bearer " + access.token }
  });
  const paypalOrder = await response.json() as {
    id?: string; status?: string;
    purchase_units?: Array<{
      amount?: { currency_code?: string; value?: string };
      payments?: { authorizations?: Array<{ id?: string; status?: string }> };
    }>;
  };
  if (!response.ok || paypalOrder.id !== recordedSessionId) {
    throw new HttpsError("failed-precondition", "PayPal could not verify this order.");
  }
  const unit = paypalOrder.purchase_units?.[0];
  const actualCurrency = String(unit?.amount?.currency_code ?? "").toUpperCase();
  const actualMinor = Math.round(Number(unit?.amount?.value ?? "NaN") * 100);
  if (actualCurrency !== expected.currencyCode.toUpperCase() || actualMinor !== expected.minorUnits) {
    throw new HttpsError("failed-precondition", "PayPal's amount or currency does not match the server order.");
  }
  if (paypalOrder.status === "COMPLETED") {
    return { accepted: true, message: "PayPal reports a completed payment; the verified webhook will update payment status." };
  }
  if (paypalOrder.status !== "APPROVED") {
    throw new HttpsError("failed-precondition", "PayPal checkout is not approved for authorization.");
  }
  const authorizeResponse = await fetch(access.baseUrl + "/v2/checkout/orders/" + encodeURIComponent(sessionId) + "/authorize", {
    method: "POST",
    headers: {
      Authorization: "Bearer " + access.token,
      "Content-Type": "application/json",
      "PayPal-Request-Id": "labmda-authorize-" + orderId
    },
    body: "{}"
  });
  const authorized = await authorizeResponse.json() as {
    id?: string;
    purchase_units?: Array<{ payments?: { authorizations?: Array<{ id?: string; status?: string }> } }>;
  };
  const authorizationId = String(authorized.purchase_units?.[0]?.payments?.authorizations?.[0]?.id ?? "");
  if (!authorizeResponse.ok || authorized.id !== sessionId || !authorizationId) {
    throw new HttpsError("failed-precondition", "PayPal could not create an authorization.");
  }
  await getDatabase().ref("payments/" + orderId).update({
    providerAuthorizationId: authorizationId,
    updatedAtEpochMillis: Date.now()
  });
  return { accepted: true, message: "PayPal authorization requested. The verified webhook will update payment state." };
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
  await ensureWallet(uid, "USD");
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


async function requireAdmin(uid: string): Promise<void> {
  const profile = await getDatabase().ref("users/" + uid).get();
  if (profile.child("role").val() !== "ADMIN") {
    throw new HttpsError("permission-denied", "Administrator access is required.");
  }
}

function walletTransactions(raw: unknown, uid: string): Array<Record<string, unknown>> {
  if (!raw || typeof raw !== "object") return [];
  return Object.entries(raw as Record<string, unknown>)
    .map(([id, value]) => {
      const entry = value && typeof value === "object" ? value as Record<string, unknown> : {};
      return { ...entry, id } as Record<string, unknown>;
    })
    .filter(entry => entry.uid === uid && entry.amount !== undefined)
    .sort((a, b) => Number(b.createdAtEpochMillis ?? 0) - Number(a.createdAtEpochMillis ?? 0))
    .slice(0, 50);
}

/** Only approved merchants can read their own server-calculated wallet snapshot. */
export const getMerchantWallet = onCall({ enforceAppCheck: true }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to view your wallet.");
  const profile = await getDatabase().ref("users/" + uid).get();
  const approved = profile.child("foodStatus").val() === "APPROVED" ||
    profile.child("shopStatus").val() === "APPROVED";
  if (!approved) throw new HttpsError("permission-denied", "Administrator-approved merchant access is required.");
  const wallet = await ensureWallet(uid, "USD");
  const transactionSnap = await getDatabase().ref("ledger").orderByChild("uid").equalTo(uid).limitToLast(50).get();
  return {
    ...walletSnapshot(wallet, "USD"),
    transactions: walletTransactions(transactionSnap.val(), uid)
  };
});

/** Only approved couriers can read their own server-calculated wallet snapshot. */
export const getCourierWallet = onCall({ enforceAppCheck: true }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to view your wallet.");
  const profile = await getDatabase().ref("users/" + uid).get();
  if (profile.child("deliveryServices").child("status").val() !== "APPROVED") {
    throw new HttpsError("permission-denied", "Administrator-approved courier access is required.");
  }
  const wallet = await ensureWallet(uid, "USD");
  const transactionSnap = await getDatabase().ref("ledger").orderByChild("uid").equalTo(uid).limitToLast(50).get();
  return {
    ...walletSnapshot(wallet, "USD"),
    transactions: walletTransactions(transactionSnap.val(), uid)
  };
});

/** Read the current server-owned commission policy, initializing safe defaults on first access. */
export const adminGetCommissionSettings = onCall({ enforceAppCheck: true }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in as an administrator.");
  await requireAdmin(uid);
  const ref = getDatabase().ref("config/commission");
  const defaults = {
    currencyCode: "USD",
    platformCommissionBps: 1000,
    courierShareBps: 0,
    updatedAtEpochMillis: Date.now()
  };
  await ref.transaction(current => current ?? defaults);
  const snapshot = await ref.get();
  return snapshot.val() ?? defaults;
});

/** Only an administrator callable can write config/commission; direct client writes are denied. */
export const adminUpdateCommissionSettings = onCall({ enforceAppCheck: true }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in as an administrator.");
  await requireAdmin(uid);
  const body = request.data as {
    currencyCode?: string; platformCommissionBps?: number; courierShareBps?: number;
  };
  const currencyCode = typeof body.currencyCode === "string" ? body.currencyCode.trim().toUpperCase() : "";
  const platformCommissionBps = Number(body.platformCommissionBps);
  const courierShareBps = Number(body.courierShareBps);
  if (!/^[A-Z]{3}$/.test(currencyCode) ||
      !Number.isInteger(platformCommissionBps) || platformCommissionBps < 0 || platformCommissionBps > 10000 ||
      !Number.isInteger(courierShareBps) || courierShareBps < 0 || courierShareBps > 10000 ||
      platformCommissionBps + courierShareBps > 10000) {
    throw new HttpsError("invalid-argument", "Currency or commission basis points are invalid.");
  }
  await getDatabase().ref("config/commission").set({
    currencyCode,
    platformCommissionBps,
    courierShareBps,
    updatedAtEpochMillis: Date.now()
  });
  return { accepted: true, message: "Commission settings saved." };
});

/** Server-side admin listing; ordinary clients have no direct payment-list access. */
export const adminListPayments = onCall({ enforceAppCheck: true }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in as an administrator.");
  await requireAdmin(uid);
  const body = request.data as { cursor?: string | null };
  const db = getDatabase();
  let query = db.ref("payments").orderByChild("updatedAtEpochMillis");
  if (typeof body.cursor === "string" && body.cursor.trim()) {
    const cursor = Number(body.cursor);
    if (!Number.isSafeInteger(cursor) || cursor < 0) {
      throw new HttpsError("invalid-argument", "Payment cursor is invalid.");
    }
    query = query.endAt(Math.max(0, cursor - 1));
  }
  const snapshot = await query.limitToLast(51).get();
  const raw = snapshot.val() as Record<string, unknown> | null;
  const all = Object.entries(raw ?? {})
    .map(([key, value]) => {
      const payment = value && typeof value === "object" ? value as Record<string, unknown> : {};
      return { ...payment, orderId: String(payment.orderId ?? key) } as Record<string, unknown>;
    })
    .sort((a, b) => Number(b.updatedAtEpochMillis ?? 0) - Number(a.updatedAtEpochMillis ?? 0));
  const hasMore = all.length > 50;
  const payments = all.slice(0, 50);
  const nextCursor = hasMore && payments.length
    ? String(payments[payments.length - 1].updatedAtEpochMillis ?? 0)
    : null;
  return { payments, nextCursor };
});


type CashoutInput = {
  amount?: { minorUnits?: number; currencyCode?: string };
  idempotencyKey?: string;
};

function cashoutRecordId(uid: string, key: string): string {
  // 30 chars also fits PayPal sender-batch and sender-item ID limits.
  return "c_" + createHash("sha256").update(uid + "|" + key).digest("hex").slice(0, 28);
}

async function reserveCashout(uid: string, payoutId: string, amount: AuthoritativeAmount): Promise<boolean> {
  const walletRef = getDatabase().ref("wallets/" + uid);
  await ensureWallet(uid, amount.currencyCode);
  await walletRef.transaction(current => {
    if (!current || typeof current !== "object") return;
    const wallet = current as Record<string, unknown>;
    const reservations = wallet.payoutReservations && typeof wallet.payoutReservations === "object"
      ? wallet.payoutReservations as Record<string, unknown> : {};
    if (reservations[payoutId] === true) return;
    const available = moneyValue(wallet.available, amount.currencyCode);
    const reserved = moneyValue(wallet.reservedForPayouts, amount.currencyCode);
    if (available.currencyCode.toUpperCase() !== amount.currencyCode.toUpperCase() ||
        available.minorUnits < amount.minorUnits) return;
    return {
      ...wallet,
      available: { minorUnits: available.minorUnits - amount.minorUnits, currencyCode: amount.currencyCode },
      reservedForPayouts: { minorUnits: reserved.minorUnits + amount.minorUnits, currencyCode: amount.currencyCode },
      payoutReservations: { ...reservations, [payoutId]: true },
      updatedAtEpochMillis: Date.now()
    };
  });
  const after = await walletRef.get();
  const wallet = after.val() as Record<string, unknown> | null;
  const reservations = wallet?.payoutReservations as Record<string, unknown> | undefined;
  return reservations?.[payoutId] === true;
}

async function closeCashoutReservation(payout: Record<string, unknown>, payoutId: string, releaseAvailable: boolean): Promise<void> {
  const uid = String(payout.uid ?? "");
  if (!uid) throw new Error("Payout has no wallet owner.");
  const amount = moneyValue(payout.amount);
  const walletRef = getDatabase().ref("wallets/" + uid);
  await walletRef.transaction(current => {
    if (!current || typeof current !== "object") return;
    const wallet = current as Record<string, unknown>;
    const reservations = wallet.payoutReservations && typeof wallet.payoutReservations === "object"
      ? wallet.payoutReservations as Record<string, unknown> : {};
    const closed = wallet.closedPayouts && typeof wallet.closedPayouts === "object"
      ? wallet.closedPayouts as Record<string, unknown> : {};
    if (closed[payoutId] === true || reservations[payoutId] !== true) return;
    const available = moneyValue(wallet.available, amount.currencyCode);
    const reserved = moneyValue(wallet.reservedForPayouts, amount.currencyCode);
    return {
      ...wallet,
      available: {
        minorUnits: available.minorUnits + (releaseAvailable ? amount.minorUnits : 0),
        currencyCode: amount.currencyCode
      },
      reservedForPayouts: {
        minorUnits: Math.max(0, reserved.minorUnits - amount.minorUnits),
        currencyCode: amount.currencyCode
      },
      closedPayouts: { ...closed, [payoutId]: true },
      updatedAtEpochMillis: Date.now()
    };
  });
  await getDatabase().ref("ledger/payout_" + payoutId + (releaseAvailable ? "_released" : "_paid")).set({
    id: "payout_" + payoutId + (releaseAvailable ? "_released" : "_paid"),
    uid,
    payoutId,
    entryType: releaseAvailable ? "PAYOUT_RESERVATION_RELEASED" : "PAYOUT_PAID",
    status: releaseAvailable ? "REVERSED" : "POSTED",
    amount,
    affectsWallet: true,
    createdAtEpochMillis: Date.now()
  });
}

async function requestCashoutForUid(uid: string, body: CashoutInput): Promise<Record<string, unknown>> {
  const amount = body.amount;
  const minorUnitsRequested = Number(amount?.minorUnits);
  const currencyCode = typeof amount?.currencyCode === "string" ? amount.currencyCode.trim().toUpperCase() : "";
  if (!Number.isSafeInteger(minorUnitsRequested) || minorUnitsRequested <= 0 || !/^[A-Z]{3}$/.test(currencyCode)) {
    throw new HttpsError("invalid-argument", "A positive payout amount and currency are required.");
  }
  const db = getDatabase();
  const profile = await db.ref("users/" + uid).get();
  const approvedMerchant = profile.child("foodStatus").val() === "APPROVED" || profile.child("shopStatus").val() === "APPROVED";
  const approvedCourier = profile.child("deliveryServices").child("status").val() === "APPROVED";
  if (!approvedMerchant && !approvedCourier) {
    throw new HttpsError("permission-denied", "Only administrator-approved merchants and couriers may request cashout.");
  }
  const settings = await db.ref("config/commission").get();
  const configuredCurrency = String(settings.child("currencyCode").val() ?? "USD").toUpperCase();
  if (currencyCode !== configuredCurrency) {
    throw new HttpsError("failed-precondition", "Cashout currency does not match the configured wallet currency.");
  }

  const now = Date.now();
  const requestedKey = typeof body.idempotencyKey === "string" ? body.idempotencyKey.trim() : "";
  const key = requestedKey && /^[A-Za-z0-9_-]{8,100}$/.test(requestedKey)
    ? requestedKey : "minute_" + Math.floor(now / 60000) + "_" + minorUnitsRequested + "_" + currencyCode;
  const payoutId = cashoutRecordId(uid, key);
  const payoutRef = db.ref("payouts/" + payoutId);
  const existing = await payoutRef.get();
  if (existing.exists()) {
    const record = existing.val() as Record<string, unknown>;
    const recordedAmount = record.amount as Record<string, unknown> | undefined;
    if (String(record.uid ?? "") !== uid ||
        Number(recordedAmount?.minorUnits) !== minorUnitsRequested ||
        String(recordedAmount?.currencyCode ?? "").toUpperCase() !== currencyCode) {
      throw new HttpsError("failed-precondition", "This idempotency key was already used with a different cashout request.");
    }
    if (record.status === "QUEUED" || record.status === "TRANSFERRED" || record.status === "PROCESSING" ||
        record.status === "SUBMITTED" || record.status === "PAID") {
      return { accepted: true, payoutId, status: record.status };
    }
    if (record.status !== "RESERVING") {
      throw new HttpsError("failed-precondition", "This idempotent cashout request has already been rejected.");
    }
    // Resume an interrupted reservation safely; reserveCashout is idempotent per payout ID.
  }

  const accountSnap = await db.ref("payoutAccounts/" + uid).get();
  const account = accountSnap.val() as Record<string, unknown> | null;
  const enabled = payoutsEnabled.value().toLowerCase() === "true";
  const rejection = !enabled ? "PAYOUTS_DISABLED"
    : !account || account.status !== "VERIFIED" ? "PAYOUT_ACCOUNT_NOT_VERIFIED"
    : String(account.provider ?? "") !== "STRIPE" && String(account.provider ?? "") !== "PAYPAL" ? "UNSUPPORTED_PAYOUT_PROVIDER"
    : "";
  const record = {
    id: payoutId, uid,
    merchantId: approvedMerchant ? uid : "",
    courierId: approvedCourier ? uid : "",
    provider: String(account?.provider ?? ""),
    providerAccountId: String(account?.providerAccountId ?? ""),
    amount: { minorUnits: minorUnitsRequested, currencyCode },
    status: rejection ? "REJECTED" : "RESERVING",
    rejectionReason: rejection,
    providerReference: "",
    idempotencyKeyHash: createHash("sha256").update(key).digest("hex"),
    createdAtEpochMillis: now, updatedAtEpochMillis: now
  };
  if (rejection) {
    const existingWallet = await db.ref("wallets/" + uid).get();
    const reservations = (existingWallet.child("payoutReservations").val() ?? {}) as Record<string, unknown>;
    if (reservations[payoutId] === true) await closeCashoutReservation(record, payoutId, true);
    await payoutRef.set(record);
    throw new HttpsError("failed-precondition", rejection === "PAYOUTS_DISABLED"
      ? "Cashouts are disabled until provider payout credentials and account verification are configured."
      : "A verified payout account is required before cashout.");
  }
  await payoutRef.set(record);
  const reserved = await reserveCashout(uid, payoutId, { minorUnits: minorUnitsRequested, currencyCode });
  if (!reserved) {
    await payoutRef.update({ status: "REJECTED_INSUFFICIENT_BALANCE", rejectionReason: "INSUFFICIENT_AVAILABLE_BALANCE", updatedAtEpochMillis: Date.now() });
    throw new HttpsError("failed-precondition", "Available wallet balance is insufficient.");
  }
  await payoutRef.update({ status: "QUEUED", updatedAtEpochMillis: Date.now() });
  return { accepted: true, payoutId, status: "QUEUED" };
}

export const requestCashout = onCall({ enforceAppCheck: true }, async request => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before requesting a cashout.");
  return requestCashoutForUid(uid, request.data as CashoutInput);
});

/** Compatibility callable used by the existing Android wallet UI. */
export const requestPayout = onCall({ enforceAppCheck: true }, async request => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before requesting a payout.");
  return requestCashoutForUid(uid, request.data as CashoutInput);
});

export const getPayoutAccount = onCall({ enforceAppCheck: true }, async request => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before viewing payout settings.");
  const snapshot = await getDatabase().ref("payoutAccounts/" + uid).get();
  if (!snapshot.exists()) return { account: null };
  const value = snapshot.val() as Record<string, unknown>;
  return { account: {
    provider: value.provider ?? "",
    providerAccountId: value.providerAccountId ?? "",
    countryCode: value.countryCode ?? "",
    accountLabel: value.accountLabel ?? "",
    updatedAtEpochMillis: value.updatedAtEpochMillis ?? 0,
    status: value.status ?? "PENDING_VERIFICATION"
  } };
});

export const updatePayoutAccount = onCall({
  enforceAppCheck: true,
  secrets: [stripeSecretKey]
}, async request => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before updating payout settings.");
  const body = request.data as { provider?: string; providerAccountId?: string; countryCode?: string; accountLabel?: string; recipientType?: string };
  const provider = typeof body.provider === "string" ? body.provider.trim().toUpperCase() : "";
  const providerAccountId = typeof body.providerAccountId === "string" ? body.providerAccountId.trim() : "";
  const countryCode = typeof body.countryCode === "string" ? body.countryCode.trim().toUpperCase() : "";
  const accountLabel = typeof body.accountLabel === "string" ? body.accountLabel.trim().slice(0, 80) : "";
  const recipientType = typeof body.recipientType === "string" ? body.recipientType.trim().toUpperCase() : "EMAIL";
  if ((provider !== "STRIPE" && provider !== "PAYPAL") || !providerAccountId ||
      providerAccountId.length > 200 || /[\r\n]/.test(providerAccountId) ||
      !/^[A-Z]{2}$/.test(countryCode) ||
      (provider === "STRIPE" && !/^acct_[A-Za-z0-9]+$/.test(providerAccountId)) ||
      (provider === "PAYPAL" && !["EMAIL", "PAYPAL_ID", "PHONE"].includes(recipientType))) {
    throw new HttpsError("invalid-argument", "Payout provider or account details are invalid.");
  }

  let status = "PENDING_VERIFICATION";
  if (provider === "STRIPE") {
    const key = stripeSecretKey.value().trim();
    if (!key) throw new HttpsError("failed-precondition", "Stripe credentials are not configured.");
    const response = await fetch("https://api.stripe.com/v1/accounts/" + encodeURIComponent(providerAccountId), {
      headers: { Authorization: "Bearer " + key }
    });
    const account = await response.json() as {
      id?: string; payouts_enabled?: boolean; metadata?: Record<string, string>; country?: string;
    };
    if (!response.ok || account.id !== providerAccountId || account.metadata?.firebaseUid !== uid || account.payouts_enabled !== true) {
      throw new HttpsError("failed-precondition", "Stripe account ownership or payout readiness could not be verified.");
    }
    status = "VERIFIED";
  } else if (recipientType === "EMAIL" && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(providerAccountId)) {
    throw new HttpsError("invalid-argument", "PayPal EMAIL recipients must be valid email addresses.");
  }

  const activePayoutSnapshot = await getDatabase().ref("payouts").orderByChild("uid").equalTo(uid).limitToLast(100).get();
  const activePayouts = Object.values((activePayoutSnapshot.val() ?? {}) as Record<string, unknown>)
    .filter(value => value && typeof value === "object")
    .map(value => value as Record<string, unknown>)
    .some(value => ["RESERVING", "QUEUED", "TRANSFERRED", "PROCESSING", "SUBMITTED"].includes(String(value.status ?? "")));
  if (activePayouts) {
    throw new HttpsError("failed-precondition", "A payout is in progress. Wait for it to finish before changing the payout account.");
  }

  const value = {
    provider, providerAccountId, countryCode, accountLabel, recipientType,
    status, verifiedBy: status === "VERIFIED" ? "STRIPE_ACCOUNT_API" : "",
    verifiedAtEpochMillis: status === "VERIFIED" ? Date.now() : 0,
    updatedAtEpochMillis: Date.now()
  };
  await getDatabase().ref("payoutAccounts/" + uid).set(value);
  return { accepted: true, message: status === "VERIFIED"
    ? "Stripe payout account ownership and payout readiness verified."
    : "Payout account saved and is awaiting administrator verification." };
});

export const adminVerifyPayoutAccount = onCall({ enforceAppCheck: true }, async request => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in as an administrator.");
  await requireAdmin(uid);
  const targetUid = typeof request.data?.uid === "string" ? request.data.uid.trim() : "";
  if (!targetUid) throw new HttpsError("invalid-argument", "Account owner UID is required.");
  const ref = getDatabase().ref("payoutAccounts/" + targetUid);
  const snapshot = await ref.get();
  if (!snapshot.exists()) throw new HttpsError("not-found", "Payout account was not found.");
  const account = snapshot.val() as Record<string, unknown>;
  if (account.provider !== "PAYPAL" || account.status !== "PENDING_VERIFICATION") {
    throw new HttpsError("failed-precondition", "Only pending PayPal accounts require this administrator verification step.");
  }
  await ref.update({ status: "VERIFIED", verifiedBy: uid, verifiedAtEpochMillis: Date.now(), updatedAtEpochMillis: Date.now() });
  return { accepted: true, message: "Payout account marked verified by an administrator." };
});

async function updatePayoutUnlessFinal(payoutId: string, fields: Record<string, unknown>): Promise<void> {
  await getDatabase().ref("payouts/" + payoutId).transaction(current => {
    if (!current || typeof current !== "object") return;
    const status = String((current as Record<string, unknown>).status ?? "");
    if (status === "PAID" || status === "FAILED" || status.startsWith("REJECTED")) return;
    return { ...current, ...fields };
  });
}

async function finalizePayoutEvent(payoutId: string, status: "PAID" | "FAILED", providerReference: string): Promise<void> {
  const ref = getDatabase().ref("payouts/" + payoutId);
  const snapshot = await ref.get();
  if (!snapshot.exists()) return;
  const payout = snapshot.val() as Record<string, unknown>;
  if (payout.status === "PAID" || payout.status === "FAILED") return;
  if (status === "PAID") {
    await closeCashoutReservation(payout, payoutId, false);
    await ref.update({ status: "PAID", providerReference, completedAtEpochMillis: Date.now(), updatedAtEpochMillis: Date.now() });
  } else {
    await closeCashoutReservation(payout, payoutId, true);
    await ref.update({ status: "FAILED", providerReference, failedAtEpochMillis: Date.now(), updatedAtEpochMillis: Date.now() });
  }
}

async function processPayout(payoutId: string, initialStatus: string): Promise<void> {
  const ref = getDatabase().ref("payouts/" + payoutId);
  const claim = await ref.transaction(current => {
    if (!current || !["QUEUED", "TRANSFERRED"].includes(String(current.status ?? ""))) return;
    return { ...current, status: "PROCESSING", lastAttemptAtEpochMillis: Date.now() };
  });
  if (!claim.committed) return;
  const payout = claim.snapshot.val() as Record<string, unknown>;
  const accountSnap = await getDatabase().ref("payoutAccounts/" + String(payout.uid ?? "")).get();
  const account = accountSnap.val() as Record<string, unknown> | null;
  try {
    if (!account || account.status !== "VERIFIED" ||
        account.provider !== payout.provider || account.providerAccountId !== payout.providerAccountId) {
      await finalizePayoutEvent(payoutId, "FAILED", "PAYOUT_ACCOUNT_MISSING_OR_NOT_VERIFIED");
      return;
    }
    const amount = moneyValue(payout.amount);
    if (amount.minorUnits <= 0 || !/^[A-Z]{3}$/.test(amount.currencyCode.toUpperCase())) throw new Error("Payout amount is invalid.");
    if (account.provider === "STRIPE") {
      const secret = stripeSecretKey.value().trim();
      const acct = String(account.providerAccountId);
      const accountInfo = await stripeApi<Record<string, unknown>>("accounts/" + encodeURIComponent(acct), secret);
      const metadata = accountInfo.metadata as Record<string, unknown> | undefined;
      if (accountInfo.id !== acct || metadata?.firebaseUid !== payout.uid || accountInfo.payouts_enabled !== true) {
        throw new Error("Stripe connected account is not verified for this user or cannot receive payouts.");
      }
      if (initialStatus === "QUEUED") {
        const transferBody = new URLSearchParams();
        transferBody.set("amount", String(amount.minorUnits));
        transferBody.set("currency", amount.currencyCode.toLowerCase());
        transferBody.set("destination", acct);
        transferBody.set("transfer_group", payoutId);
        transferBody.set("metadata[payoutId]", payoutId);
        transferBody.set("metadata[firebaseUid]", String(payout.uid));
        const transfer = await stripeApi<Record<string, unknown>>(
          "transfers", secret, "POST", transferBody, "labmda-transfer-" + payoutId
        );
        if (typeof transfer.id !== "string") throw new Error("Stripe transfer response did not contain an ID.");
        await updatePayoutUnlessFinal(payoutId, { status: "TRANSFERRED", transferReference: transfer.id, updatedAtEpochMillis: Date.now() });
        return;
      }
      const payoutBody = new URLSearchParams();
      payoutBody.set("amount", String(amount.minorUnits));
      payoutBody.set("currency", amount.currencyCode.toLowerCase());
      payoutBody.set("metadata[payoutId]", payoutId);
      payoutBody.set("metadata[firebaseUid]", String(payout.uid));
      const result = await stripeApi<Record<string, unknown>>(
        "payouts", secret, "POST", payoutBody, "labmda-payout-" + payoutId, acct
      );
      if (typeof result.id !== "string") throw new Error("Stripe payout response did not contain an ID.");
      await updatePayoutUnlessFinal(payoutId, { status: "SUBMITTED", providerReference: result.id, submittedAtEpochMillis: Date.now(), updatedAtEpochMillis: Date.now() });
      return;
    }
    if (account.provider === "PAYPAL") {
      if (initialStatus !== "QUEUED") throw new Error("Unexpected PayPal payout state.");
      const access = await getPayPalAccessToken();
      const recipientType = String(account.recipientType ?? "EMAIL");
      const requestBody = {
        sender_batch_header: {
          sender_batch_id: payoutId,
          email_subject: "Your Labmda payout is on the way"
        },
        items: [{
          recipient_type: recipientType,
          amount: { value: (amount.minorUnits / 100).toFixed(2), currency: amount.currencyCode.toUpperCase() },
          receiver: String(account.providerAccountId),
          sender_item_id: payoutId,
          note: "Labmda wallet cashout"
        }]
      };
      const response = await fetch(access.baseUrl + "/v1/payments/payouts", {
        method: "POST",
        headers: {
          Authorization: "Bearer " + access.token,
          "Content-Type": "application/json",
          "PayPal-Request-Id": payoutId
        },
        body: JSON.stringify(requestBody)
      });
      const result = await response.json() as { batch_header?: { payout_batch_id?: string; batch_status?: string } };
      const batchId = String(result.batch_header?.payout_batch_id ?? "");
      if (!response.ok || !batchId) throw new Error("PayPal payout request failed.");
      await updatePayoutUnlessFinal(payoutId, { status: "SUBMITTED", providerReference: batchId, submittedAtEpochMillis: Date.now(), updatedAtEpochMillis: Date.now() });
      return;
    }
    throw new Error("Unsupported payout provider.");
  } catch (error) {
    await updatePayoutUnlessFinal(payoutId, {
      status: initialStatus,
      lastError: error instanceof Error ? error.message.slice(0, 250) : "Unknown payout processing error",
      updatedAtEpochMillis: Date.now()
    });
    throw error;
  }
}

/** Periodically submits queued cashouts to verified provider accounts; webhooks confirm the final outcome. */
export const runPayouts = onSchedule({
  schedule: "every 5 minutes",
  timeoutSeconds: 540,
  secrets: [stripeSecretKey, paypalClientId, paypalClientSecret]
}, async () => {
  if (payoutsEnabled.value().toLowerCase() !== "true") return;
  const db = getDatabase();
  const now = Date.now();

  // Recover work interrupted after a provider API request. All provider-side mutations use
  // stable idempotency/request IDs, so retrying after the lease expires is safe.
  const processingSnapshot = await db.ref("payouts").orderByChild("status").equalTo("PROCESSING").limitToFirst(100).get();
  for (const [id, raw] of Object.entries((processingSnapshot.val() ?? {}) as Record<string, unknown>)) {
    const record = raw as Record<string, unknown>;
    if (Number(record.lastAttemptAtEpochMillis ?? 0) > now - 10 * 60 * 1000) continue;
    await db.ref("payouts/" + id).transaction(current => {
      if (!current || typeof current !== "object") return;
      const item = current as Record<string, unknown>;
      if (item.status !== "PROCESSING" || Number(item.lastAttemptAtEpochMillis ?? 0) > now - 10 * 60 * 1000) return;
      return { ...item, status: item.transferReference ? "TRANSFERRED" : "QUEUED", recoveredAtEpochMillis: now, updatedAtEpochMillis: now };
    });
  }

  const reservingSnapshot = await db.ref("payouts").orderByChild("status").equalTo("RESERVING").limitToFirst(100).get();
  for (const [id, raw] of Object.entries((reservingSnapshot.val() ?? {}) as Record<string, unknown>)) {
    const record = raw as Record<string, unknown>;
    if (Number(record.updatedAtEpochMillis ?? record.createdAtEpochMillis ?? 0) > now - 10 * 60 * 1000) continue;
    const walletSnap = await db.ref("wallets/" + String(record.uid ?? "") + "/payoutReservations/" + id).get();
    await db.ref("payouts/" + id).transaction(current => {
      if (!current || typeof current !== "object" || (current as Record<string, unknown>).status !== "RESERVING") return;
      const item = current as Record<string, unknown>;
      return walletSnap.val() === true
        ? { ...item, status: "QUEUED", recoveredAtEpochMillis: now, updatedAtEpochMillis: now }
        : { ...item, status: "REJECTED_INTERRUPTED_BEFORE_RESERVATION", rejectionReason: "INTERRUPTED_BEFORE_RESERVATION", updatedAtEpochMillis: now };
    });
  }

  for (const status of ["QUEUED", "TRANSFERRED"]) {
    const snapshot = await db.ref("payouts").orderByChild("status").equalTo(status).limitToFirst(20).get();
    const raw = snapshot.val() as Record<string, unknown> | null;
    for (const [id] of Object.entries(raw ?? {})) {
      try { await processPayout(id, status); }
      catch (error) { console.error("Payout processing deferred for", id, error instanceof Error ? error.message : "unknown error"); }
    }
  }
});

async function submitProviderRefund(orderId: string, context: PaymentContext, amountMinorUnits: number, reason: string): Promise<string> {
  const payment = context.payment;
  if (String(payment.status ?? "") !== "SUCCEEDED" && String(payment.status ?? "") !== "PARTIALLY_REFUNDED") {
    throw new HttpsError("failed-precondition", "Only captured payments can be refunded.");
  }
  if (String(payment.breakdownStatus ?? "") === "ALLOCATED" || String(payment.breakdownStatus ?? "") === "ALLOCATING") {
    throw new HttpsError("failed-precondition", "This payment has already affected wallets. Reversal accounting is required before refunding it.");
  }
  if (payment.refundRequest && (payment.refundRequest as Record<string, unknown>).status === "SUBMITTED") {
    throw new HttpsError("failed-precondition", "A refund request is already in progress for this payment.");
  }
  const total = moneyValue(payment.amount);
  const refunded = moneyValue(payment.refundAmount, total.currencyCode);
  const remaining = total.minorUnits - refunded.minorUnits;
  if (!Number.isSafeInteger(amountMinorUnits) || amountMinorUnits <= 0 || amountMinorUnits > remaining) {
    throw new HttpsError("invalid-argument", "Refund amount exceeds the remaining refundable balance.");
  }
  const provider = normalizePaymentMethod(payment.provider ?? payment.method);
  let refundId = "";
  if (provider === "STRIPE") {
    const intentId = String(payment.providerPaymentIntentId ?? "");
    if (!intentId) throw new HttpsError("failed-precondition", "Stripe PaymentIntent reference is missing.");
    const secret = stripeSecretKey.value().trim();
    const form = new URLSearchParams();
    form.set("payment_intent", intentId);
    form.set("amount", String(amountMinorUnits));
    form.set("metadata[orderId]", orderId);
    form.set("metadata[reason]", reason.slice(0, 200));
    const result = await stripeApi<Record<string, unknown>>(
      "refunds", secret, "POST", form, "labmda-refund-" + orderId + "-" + (refunded.minorUnits + amountMinorUnits)
    );
    refundId = String(result.id ?? "");
    if (!refundId) throw new HttpsError("internal", "Stripe did not return a refund identifier.");
  } else if (provider === "PAYPAL") {
    const captureId = String(payment.providerReceiptId ?? "");
    if (!captureId) throw new HttpsError("failed-precondition", "PayPal capture reference is missing.");
    const access = await getPayPalAccessToken();
    const response = await fetch(access.baseUrl + "/v2/payments/captures/" + encodeURIComponent(captureId) + "/refund", {
      method: "POST",
      headers: {
        Authorization: "Bearer " + access.token,
        "Content-Type": "application/json",
        "PayPal-Request-Id": "rf_" + createHash("sha256").update(orderId + "|" + (refunded.minorUnits + amountMinorUnits)).digest("hex").slice(0, 32)
      },
      body: JSON.stringify({
        amount: { value: (amountMinorUnits / 100).toFixed(2), currency_code: total.currencyCode.toUpperCase() },
        invoice_id: orderId,
        note_to_payer: reason.slice(0, 200)
      })
    });
    const result = await response.json() as { id?: string };
    if (!response.ok || !result.id) throw new HttpsError("failed-precondition", "PayPal did not accept the refund request.");
    refundId = result.id;
  } else {
    throw new HttpsError("failed-precondition", "This payment provider does not support remote refunds.");
  }
  await getDatabase().ref("payments/" + orderId + "/refundRequest").set({
    id: refundId, amount: { minorUnits: amountMinorUnits, currencyCode: total.currencyCode },
    reason: reason.slice(0, 200), status: "SUBMITTED", submittedAtEpochMillis: Date.now()
  });
  return refundId;
}

async function handleCaptureAfterCancellation(orderId: string): Promise<boolean> {
  const context = await loadPaymentContext(orderId);
  if (!context) return false;
  const root = await getDatabase().ref(context.rootPath + "/" + orderId).get();
  if (!root.exists() || String(root.child("status").val() ?? "") !== "CANCELLED") return false;

  const payment = context.payment;
  if (String(payment.breakdownStatus ?? "") === "ALLOCATED" || String(payment.breakdownStatus ?? "") === "ALLOCATING") {
    await getDatabase().ref("payments/" + orderId).update({
      requiresAdminReconciliation: true,
      reconciliationReason: "Provider capture completed after cancellation, but wallets were already allocated.",
      updatedAtEpochMillis: Date.now()
    });
    return true;
  }
  if ((payment.refundRequest as Record<string, unknown> | undefined)?.status === "SUBMITTED") return true;
  const total = moneyValue(payment.amount);
  const refunded = moneyValue(payment.refundAmount, total.currencyCode);
  const remaining = total.minorUnits - refunded.minorUnits;
  if (remaining <= 0) return true;
  try {
    await submitProviderRefund(orderId, context, remaining, "Order or ride cancelled while provider capture was completing");
  } catch (error) {
    await getDatabase().ref("payments/" + orderId).update({
      requiresAdminReconciliation: true,
      reconciliationReason: error instanceof Error ? error.message.slice(0, 250) : "Automatic refund after cancellation failed",
      updatedAtEpochMillis: Date.now()
    });
  }
  return true;
}

async function refundPaymentForAdmin(uid: string, body: {
  orderId?: string; amountMinorUnits?: number | null; currencyCode?: string | null; reason?: string;
}): Promise<Record<string, unknown>> {
  await requireAdmin(uid);
  const orderId = typeof body.orderId === "string" ? body.orderId.trim() : "";
  if (!orderId) throw new HttpsError("invalid-argument", "orderId is required.");
  const context = await loadPaymentContext(orderId);
  if (!context) throw new HttpsError("not-found", "Payment record was not found.");
  const total = moneyValue(context.payment.amount);
  const currentRefund = moneyValue(context.payment.refundAmount, total.currencyCode);
  const remaining = total.minorUnits - currentRefund.minorUnits;
  const currency = typeof body.currencyCode === "string" ? body.currencyCode.trim().toUpperCase() : total.currencyCode.toUpperCase();
  if (currency !== total.currencyCode.toUpperCase()) throw new HttpsError("invalid-argument", "Refund currency does not match the payment.");
  const amount = body.amountMinorUnits === undefined || body.amountMinorUnits === null ? remaining : Number(body.amountMinorUnits);
  if (!Number.isSafeInteger(amount) || amount <= 0 || amount > remaining) throw new HttpsError("invalid-argument", "Refund amount is invalid.");
  const reason = typeof body.reason === "string" && body.reason.trim() ? body.reason.trim() : "Admin refund";
  const refundId = await submitProviderRefund(orderId, context, amount, reason);
  return { accepted: true, refundId, message: "Refund submitted to the provider; final refund status will be recorded by its verified webhook." };
}

export const refundPayment = onCall({
  enforceAppCheck: true,
  secrets: [stripeSecretKey, paypalClientId, paypalClientSecret]
}, async request => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in as an administrator.");
  return refundPaymentForAdmin(uid, request.data as {
    orderId?: string; amountMinorUnits?: number | null; currencyCode?: string | null; reason?: string;
  });
});

/** Compatibility callable referenced by the existing admin presentation layer. */
export const adminRefundPayment = onCall({
  enforceAppCheck: true,
  secrets: [stripeSecretKey, paypalClientId, paypalClientSecret]
}, async request => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in as an administrator.");
  return refundPaymentForAdmin(uid, request.data as {
    orderId?: string; amountMinorUnits?: number | null; currencyCode?: string | null; reason?: string;
  });
});

export const setCommissionRules = onCall({ enforceAppCheck: true }, async request => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in as an administrator.");
  await requireAdmin(uid);
  const body = request.data as { currencyCode?: string; platformCommissionBps?: number; courierShareBps?: number };
  const currencyCode = typeof body.currencyCode === "string" ? body.currencyCode.trim().toUpperCase() : "";
  const platformCommissionBps = Number(body.platformCommissionBps);
  const courierShareBps = Number(body.courierShareBps);
  if (!/^[A-Z]{3}$/.test(currencyCode) ||
      !Number.isInteger(platformCommissionBps) || platformCommissionBps < 0 || platformCommissionBps > 10000 ||
      !Number.isInteger(courierShareBps) || courierShareBps < 0 || courierShareBps > 10000 ||
      platformCommissionBps + courierShareBps > 10000) {
    throw new HttpsError("invalid-argument", "Currency or commission basis points are invalid.");
  }
  await getDatabase().ref("config/commission").set({
    currencyCode, platformCommissionBps, courierShareBps, updatedAtEpochMillis: Date.now()
  });
  return { accepted: true, message: "Commission rules saved." };
});

export const confirmCashCollected = onCall({ enforceAppCheck: true }, async request => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in as the assigned courier or driver.");
  const body = request.data as { orderId?: string };
  const orderId = typeof body.orderId === "string" ? body.orderId.trim() : "";
  if (!orderId) throw new HttpsError("invalid-argument", "orderId is required.");
  const context = await loadPaymentContext(orderId);
  if (!context) throw new HttpsError("not-found", "Cash order or ride was not found.");
  if (normalizePaymentMethod(context.payment.provider ?? context.payment.method) !== "CASH") {
    throw new HttpsError("failed-precondition", "This order or ride does not use cash payment.");
  }
  const rootRef = getDatabase().ref(context.rootPath + "/" + orderId);
  const rootSnap = await rootRef.get();
  if (!rootSnap.exists()) throw new HttpsError("not-found", "The linked order or ride was not found.");
  const root = rootSnap.val() as Record<string, unknown>;
  const assignedUid = context.rootPath === "trips" ? String(root.driverId ?? "") : String(root.deliveryId ?? "");
  const completed = context.rootPath === "trips" ? root.status === "COMPLETED" : root.status === "DELIVERED";
  if (assignedUid !== uid || !completed) {
    throw new HttpsError("permission-denied", "Only the assigned courier or driver can confirm cash after completion.");
  }
  if (String(context.payment.status ?? "") === "SUCCEEDED") {
    await setPaymentStatus(orderId, context, "SUCCEEDED", {
      provider: "CASH",
      method: "CASH",
      cashCollectedBy: String(context.payment.cashCollectedBy ?? uid),
      cashCollectedAtEpochMillis: Number(context.payment.cashCollectedAtEpochMillis ?? Date.now())
    });
    await settleCompletedPayment(orderId);
    return { accepted: true, message: "Cash collection was already recorded." };
  }
  if (["REFUNDED", "PARTIALLY_REFUNDED"].includes(String(context.payment.status ?? ""))) {
    throw new HttpsError("failed-precondition", "A refunded payment cannot be marked collected.");
  }
  const amount = moneyValue(context.payment.amount);
  const transaction = await getDatabase().ref("payments/" + orderId).transaction(current => {
    if (!current || typeof current !== "object") return;
    if (String((current as Record<string, unknown>).status ?? "") === "SUCCEEDED") return;
    return {
      ...current,
      status: "SUCCEEDED",
      cashCollectedBy: uid,
      cashCollectedAtEpochMillis: Date.now(),
      provider: "CASH",
      updatedAtEpochMillis: Date.now()
    };
  });
  const after = await loadPaymentContext(orderId);
  if (!after || String(after.payment.status ?? "") !== "SUCCEEDED") {
    throw new HttpsError("failed-precondition", "Cash collection status could not be committed.");
  }
  if (transaction.committed) {
    await setPaymentStatus(orderId, after, "SUCCEEDED", {
      provider: "CASH",
      method: "CASH",
      cashCollectedBy: uid,
      cashCollectedAtEpochMillis: Number(after.payment.cashCollectedAtEpochMillis ?? Date.now())
    });
  }
  await settleCompletedPayment(orderId);
  return { accepted: true, amount, message: "Cash collection recorded and settlement attempted." };
});

async function handleOrderStatusChange(orderId: string, status: string): Promise<void> {
  const context = await loadPaymentContext(orderId);
  if (!context) return;
  const paymentStatus = String(context.payment.status ?? "PENDING");
  if (status === "ACCEPTED") {
    if (paymentStatus === "AUTHORIZED") await captureAuthorizedPayment(orderId, context);
    else if (paymentStatus === "SUCCEEDED") await maybeCaptureAfterAuthorization(orderId);
    return;
  }
  if (status === "CANCELLED") {
    if (paymentStatus === "AUTHORIZED" || paymentStatus === "REQUIRES_ACTION" || paymentStatus === "PENDING") {
      await releasePaymentAuthorization(orderId, context);
      return;
    }
    if (["SUCCEEDED", "PARTIALLY_REFUNDED"].includes(paymentStatus)) {
      const provider = normalizePaymentMethod(context.payment.provider ?? context.payment.method);
      const allocated = ["ALLOCATED", "ALLOCATING"].includes(String(context.payment.breakdownStatus ?? ""));
      if (provider === "CASH") {
        await getDatabase().ref("payments/" + orderId).update({
          requiresAdminReconciliation: true,
          reconciliationReason: "Order cancelled after customer cash collection; a manual cash refund/remittance decision is required.",
          updatedAtEpochMillis: Date.now()
        });
        return;
      }
      if (!allocated) {
        try {
          const total = moneyValue(context.payment.amount);
          const refunded = moneyValue(context.payment.refundAmount, total.currencyCode);
          const remaining = total.minorUnits - refunded.minorUnits;
          if (remaining > 0 && (context.payment.refundRequest as Record<string, unknown> | undefined)?.status !== "SUBMITTED") {
            await submitProviderRefund(orderId, context, remaining, "Order cancelled before settlement");
          }
        } catch (error) {
          await getDatabase().ref("payments/" + orderId).update({
            requiresAdminReconciliation: true,
            reconciliationReason: error instanceof Error ? error.message.slice(0, 250) : "Automatic cancellation refund failed",
            updatedAtEpochMillis: Date.now()
          });
        }
        return;
      }
      await getDatabase().ref("payments/" + orderId).update({
        requiresAdminReconciliation: true,
        reconciliationReason: "Order cancelled after wallet settlement/allocation began; reversal accounting is required.",
        updatedAtEpochMillis: Date.now()
      });
    }
    return;
  }
  if (status === "DELIVERED" && paymentStatus === "SUCCEEDED") await settleCompletedPayment(orderId);
}

export const onOrderStatusChange = onValueUpdated({
  ref: "/orders/{orderId}/status",
  retry: true,
  secrets: [stripeSecretKey, paypalClientId, paypalClientSecret]
}, async event => {
  const orderId = String(event.params.orderId ?? "");
  const status = String(event.data.after.val() ?? "");
  if (!orderId || !status || event.data.before.val() === status) return;
  await handleOrderStatusChange(orderId, status);
});

export const onTripStatusChange = onValueUpdated({
  ref: "/trips/{tripId}/status",
  retry: true,
  secrets: [stripeSecretKey, paypalClientId, paypalClientSecret]
}, async event => {
  const tripId = String(event.params.tripId ?? "");
  const status = String(event.data.after.val() ?? "");
  if (!tripId || event.data.before.val() === status) return;
  if (status === "COMPLETED") {
    await maybeCaptureAfterAuthorization(tripId);
  } else if (status === "CANCELLED") {
    const context = await loadPaymentContext(tripId);
    if (!context) return;
    if (["AUTHORIZED", "REQUIRES_ACTION", "PENDING"].includes(String(context.payment.status ?? ""))) {
      await releasePaymentAuthorization(tripId, context);
    } else if (String(context.payment.status ?? "") === "SUCCEEDED") {
      await handleCaptureAfterCancellation(tripId);
    }
  }
});

async function claimProviderEvent(provider: "stripe" | "paypal", eventId: string): Promise<"PROCESS" | "DONE"> {
  const ref = getDatabase().ref("providerEvents/" + provider + "/" + eventId);
  const now = Date.now();
  const result = await ref.transaction(current => {
    if (current && typeof current === "object") {
      const record = current as Record<string, unknown>;
      if (record.status === "PROCESSED") return;
      if (record.status === "PROCESSING" && Number(record.leaseUntilEpochMillis ?? 0) > now) return;
    }
    return { status: "PROCESSING", leaseUntilEpochMillis: now + 120000, updatedAtEpochMillis: now };
  });
  if (result.committed) return "PROCESS";
  const snapshot = await ref.get();
  if (snapshot.child("status").val() === "PROCESSED") return "DONE";
  throw new Error("Provider event is already being processed; retry later.");
}

async function completeProviderEvent(provider: "stripe" | "paypal", eventId: string): Promise<void> {
  await getDatabase().ref("providerEvents/" + provider + "/" + eventId).update({
    status: "PROCESSED", processedAtEpochMillis: Date.now(), updatedAtEpochMillis: Date.now()
  });
}

async function failProviderEvent(provider: "stripe" | "paypal", eventId: string, error: unknown): Promise<void> {
  await getDatabase().ref("providerEvents/" + provider + "/" + eventId).update({
    status: "FAILED",
    lastError: error instanceof Error ? error.message.slice(0, 250) : "Unknown provider webhook error",
    leaseUntilEpochMillis: 0,
    updatedAtEpochMillis: Date.now()
  });
}

async function handleStripeIntent(orderId: string, intent: Record<string, unknown>, eventType: string): Promise<void> {
  const context = await loadPaymentContext(orderId);
  if (!context) throw new Error("Payment not found for Stripe event.");
  if (normalizePaymentMethod(context.payment.provider ?? context.payment.method) !== "STRIPE") {
    throw new Error("Stripe event does not match the payment provider.");
  }
  const expected = moneyValue(context.payment.amount);
  const actualAmount =
    eventType === "payment_intent.amount_capturable_updated" || intent.status === "requires_capture"
      ? Number(intent.amount_capturable ?? 0)
      : eventType === "payment_intent.succeeded" || intent.status === "succeeded"
        ? Number(intent.amount_received ?? intent.amount ?? 0)
        : Number(intent.amount ?? 0);
  const actualCurrency = String(intent.currency ?? "").toUpperCase();
  if (actualAmount !== expected.minorUnits || actualCurrency !== expected.currencyCode.toUpperCase()) {
    throw new Error("Stripe amount or currency mismatch for payment " + orderId);
  }
  const intentId = String(intent.id ?? "");
  if (!intentId) throw new Error("Stripe PaymentIntent ID is missing.");
  if (eventType === "payment_intent.amount_capturable_updated" ||
      (eventType === "checkout.session.completed" && intent.status === "requires_capture")) {
    if (intent.status !== "requires_capture") throw new Error("Stripe PaymentIntent is not awaiting capture.");
    await setPaymentStatus(orderId, context, "AUTHORIZED", {
      provider: "STRIPE", providerPaymentIntentId: intentId, authorizedAtEpochMillis: Date.now()
    });
    const latestContext = await loadPaymentContext(orderId);
    const latestRoot = await getDatabase().ref(context.rootPath + "/" + orderId).get();
    if (latestContext && String(latestRoot.child("status").val() ?? "") === "CANCELLED") {
      await releasePaymentAuthorization(orderId, latestContext);
      return;
    }
    await maybeCaptureAfterAuthorization(orderId);
    return;
  }
  if (eventType === "payment_intent.succeeded" || intent.status === "succeeded") {
    if (["REFUNDED", "PARTIALLY_REFUNDED"].includes(String(context.payment.status ?? ""))) {
      await getDatabase().ref("payments/" + orderId).update({ requiresAdminReconciliation: true, reconciliationReason: "Stripe reported success after local refund status." });
      return;
    }
    const charge = intent.latest_charge;
    const chargeId = typeof charge === "string" ? charge : String((charge as Record<string, unknown> | undefined)?.id ?? "");
    await setPaymentStatus(orderId, context, "SUCCEEDED", {
      provider: "STRIPE", providerPaymentIntentId: intentId,
      providerReceiptId: chargeId || intentId, providerReference: String(context.payment.providerReference || ""),
      verifiedAtEpochMillis: Date.now()
    });
    if (await handleCaptureAfterCancellation(orderId)) return;
    await maybeCaptureAfterAuthorization(orderId);
    return;
  }
  if (eventType === "payment_intent.canceled") {
    await setPaymentStatus(orderId, context, "CANCELLED", { provider: "STRIPE", providerPaymentIntentId: intentId });
    return;
  }
  if (eventType === "payment_intent.payment_failed") {
    await setPaymentStatus(orderId, context, "FAILED", {
      provider: "STRIPE", providerPaymentIntentId: intentId,
      failureMessage: String((intent.last_payment_error as Record<string, unknown> | undefined)?.message ?? "Payment failed")
    });
  }
}

async function findPaymentByIntentId(intentId: string): Promise<{ orderId: string; context: PaymentContext } | null> {
  const snapshot = await getDatabase().ref("payments").orderByChild("providerPaymentIntentId").equalTo(intentId).limitToFirst(2).get();
  const entries = Object.entries((snapshot.val() ?? {}) as Record<string, unknown>);
  if (entries.length !== 1) return null;
  const [orderId, raw] = entries[0];
  const payment = raw as Record<string, unknown>;
  return { orderId, context: { payment, rootPath: String(payment.contextType ?? "") === "RIDE" ? "trips" : "orders" } };
}

export const stripeWebhook = onRequest({
  secrets: [stripeWebhookSecret, stripeSecretKey, paypalClientId, paypalClientSecret],
  timeoutSeconds: 120
}, async (request, response) => {
  const eventId = String((request.body as Record<string, unknown> | undefined)?.id ?? "");
  try {
    const signature = String(request.headers["stripe-signature"] ?? "");
    if (!stripeSignatureValid(request.rawBody, signature, stripeWebhookSecret.value().trim())) {
      response.status(400).send("Invalid Stripe signature");
      return;
    }
    const event = request.body as { id?: string; type?: string; data?: { object?: Record<string, unknown> } };
    if (!event.id || !event.type || !event.data?.object) {
      response.status(400).send("Invalid Stripe event");
      return;
    }
    const claim = await claimProviderEvent("stripe", event.id);
    if (claim === "DONE") {
      response.status(200).send("Duplicate event");
      return;
    }
    try {
      const object = event.data.object;
      if (event.type.startsWith("payment_intent.")) {
        const metadata = object.metadata as Record<string, unknown> | undefined;
        const orderId = String(metadata?.orderId ?? "");
        if (orderId && [
          "payment_intent.amount_capturable_updated", "payment_intent.succeeded",
          "payment_intent.canceled", "payment_intent.payment_failed"
        ].includes(event.type)) await handleStripeIntent(orderId, object, event.type);
      } else if (event.type === "checkout.session.completed") {
        const orderId = String(object.client_reference_id ?? (object.metadata as Record<string, unknown> | undefined)?.orderId ?? "");
        const intentId = typeof object.payment_intent === "string" ? object.payment_intent :
          String((object.payment_intent as Record<string, unknown> | undefined)?.id ?? "");
        if (orderId && intentId) {
          const intent = await stripeApi<Record<string, unknown>>("payment_intents/" + encodeURIComponent(intentId), stripeSecretKey.value().trim());
          await handleStripeIntent(orderId, intent, event.type);
        }
      } else if (event.type === "checkout.session.expired") {
        const orderId = String(object.client_reference_id ?? (object.metadata as Record<string, unknown> | undefined)?.orderId ?? "");
        if (orderId) {
          const context = await loadPaymentContext(orderId);
          if (context && String(context.payment.status ?? "") !== "SUCCEEDED") {
            await setPaymentStatus(orderId, context, "CANCELLED", { provider: "STRIPE" });
          }
        }
      } else if (event.type === "charge.refunded") {
        const intentId = String(object.payment_intent ?? "");
        const located = intentId ? await findPaymentByIntentId(intentId) : null;
        if (located) {
          const total = moneyValue(located.context.payment.amount);
          const refundedMinor = Number(object.amount_refunded ?? 0);
          if (!Number.isSafeInteger(refundedMinor) || refundedMinor < 0 || refundedMinor > total.minorUnits) throw new Error("Stripe refund amount is invalid.");
          const status = refundedMinor >= total.minorUnits ? "REFUNDED" : refundedMinor > 0 ? "PARTIALLY_REFUNDED" : String(located.context.payment.status ?? "SUCCEEDED");
          await getDatabase().ref("payments/" + located.orderId).update({
            status,
            refundAmount: { minorUnits: refundedMinor, currencyCode: total.currencyCode },
            refundRequest: { ...(located.context.payment.refundRequest as Record<string, unknown> ?? {}), status: "COMPLETED", updatedAtEpochMillis: Date.now() },
            requiresAdminReconciliation: String(located.context.payment.breakdownStatus ?? "") === "ALLOCATED",
            updatedAtEpochMillis: Date.now()
          });
          await getDatabase().ref("ledger/refund_" + located.orderId + "_" + refundedMinor).set({
            id: "refund_" + located.orderId + "_" + refundedMinor,
            orderId: located.orderId, entryType: "PROVIDER_REFUND_CONFIRMED",
            amount: { minorUnits: refundedMinor, currencyCode: total.currencyCode }, status: "CONFIRMED",
            affectsWallet: false,
            requiresReversal: String(located.context.payment.breakdownStatus ?? "") === "ALLOCATED" ||
              (status === "PARTIALLY_REFUNDED" && String(located.context.payment.breakdownStatus ?? "") !== "ALLOCATED"),
            createdAtEpochMillis: Date.now()
          });
          await handleCaptureAfterCancellation(located.orderId);
        }
      } else if (event.type === "payout.paid" || event.type === "payout.failed") {
        const payoutId = String((object.metadata as Record<string, unknown> | undefined)?.payoutId ?? "");
        const accountId = String((event as unknown as Record<string, unknown>).account ?? "");
        if (payoutId) {
          const payout = await getDatabase().ref("payouts/" + payoutId).get();
          const record = payout.val() as Record<string, unknown> | null;
          if (record && record.provider === "STRIPE" && record.providerAccountId === accountId) {
            await finalizePayoutEvent(payoutId, event.type === "payout.paid" ? "PAID" : "FAILED", String(object.id ?? ""));
          }
        }
      }
      await completeProviderEvent("stripe", event.id);
      response.status(200).send("OK");
    } catch (error) {
      await failProviderEvent("stripe", event.id, error);
      console.error("Stripe webhook failed", error instanceof Error ? error.message : "unknown error");
      response.status(500).send("Retry webhook processing");
    }
  } catch (error) {
    console.error("Stripe webhook rejected", error instanceof Error ? error.message : "unknown error");
    response.status(500).send("Webhook processing error");
  }
});

function firstHeader(value: string | string[] | undefined): string {
  return Array.isArray(value) ? String(value[0] ?? "") : String(value ?? "");
}

export const paypalWebhook = onRequest({
  secrets: [paypalClientId, paypalClientSecret, stripeSecretKey],
  timeoutSeconds: 120
}, async (request, response) => {
  const body = request.body as Record<string, unknown>;
  const eventId = String(body?.id ?? "");
  try {
    if (!eventId || !await verifyPayPalWebhook(body, request.headers)) {
      response.status(400).send("Invalid PayPal signature");
      return;
    }
    const claim = await claimProviderEvent("paypal", eventId);
    if (claim === "DONE") {
      response.status(200).send("Duplicate event");
      return;
    }
    try {
      const eventType = String(body.event_type ?? "");
      const resource = body.resource && typeof body.resource === "object" ? body.resource as Record<string, unknown> : {};
      const related = ((resource.supplementary_data as Record<string, unknown> | undefined)?.related_ids ?? {}) as Record<string, unknown>;
      const orderReference = String(
        related.order_id ?? resource.custom_id ?? resource.invoice_id ?? resource.id ?? ""
      );
      const located = await findPaymentByProviderReference(orderReference);
      if (eventType === "CHECKOUT.ORDER.APPROVED" && located) {
        const orderId = located.orderId;
        const linkedRoot = await getDatabase().ref(located.context.rootPath + "/" + orderId).get();
        if (!linkedRoot.exists() || ["CANCELLED", "CANCELED", "DELIVERED", "COMPLETED"].includes(String(linkedRoot.child("status").val() ?? ""))) {
          await setPaymentStatus(orderId, located.context, "CANCELLED", { cancelReason: "ORDER_TERMINAL_BEFORE_PAYPAL_AUTHORIZATION" });
          await completeProviderEvent("paypal", eventId);
          response.status(200).send("Payment cancelled; PayPal authorization skipped");
          return;
        }
        const access = await getPayPalAccessToken();
        const authResponse = await fetch(access.baseUrl + "/v2/checkout/orders/" + encodeURIComponent(orderReference) + "/authorize", {
          method: "POST",
          headers: {
            Authorization: "Bearer " + access.token,
            "Content-Type": "application/json",
            "PayPal-Request-Id": "labmda-authorize-" + orderId
          },
          body: "{}"
        });
        const authorized = await authResponse.json() as {
          id?: string; purchase_units?: Array<{ payments?: { authorizations?: Array<{ id?: string }> } }>;
        };
        const authorizationId = String(authorized.purchase_units?.[0]?.payments?.authorizations?.[0]?.id ?? "");
        if (!authResponse.ok || authorized.id !== orderReference || !authorizationId) {
          throw new Error("PayPal order authorization failed.");
        }
        await getDatabase().ref("payments/" + orderId).update({ providerAuthorizationId: authorizationId, updatedAtEpochMillis: Date.now() });
      } else if (eventType === "PAYMENT.AUTHORIZATION.CREATED" && located) {
        const amount = moneyValue(located.context.payment.amount);
        const actualMinor = Math.round(Number((resource.amount as Record<string, unknown> | undefined)?.value ?? "NaN") * 100);
        const actualCurrency = String((resource.amount as Record<string, unknown> | undefined)?.currency_code ?? "").toUpperCase();
        const authorizationId = String(resource.id ?? "");
        if (actualMinor !== amount.minorUnits || actualCurrency !== amount.currencyCode.toUpperCase() || !authorizationId) {
          throw new Error("PayPal authorization amount, currency, or ID is invalid.");
        }
        await setPaymentStatus(located.orderId, located.context, "AUTHORIZED", {
          provider: "PAYPAL", providerAuthorizationId: authorizationId, authorizedAtEpochMillis: Date.now()
        });
        const latestContext = await loadPaymentContext(located.orderId);
        const latestRoot = await getDatabase().ref(located.context.rootPath + "/" + located.orderId).get();
        if (latestContext && String(latestRoot.child("status").val() ?? "") === "CANCELLED") {
          await releasePaymentAuthorization(located.orderId, latestContext);
        } else {
          await maybeCaptureAfterAuthorization(located.orderId);
        }
      } else if (eventType === "PAYMENT.CAPTURE.COMPLETED" && located) {
        const amount = moneyValue(located.context.payment.amount);
        const actualMinor = Math.round(Number((resource.amount as Record<string, unknown> | undefined)?.value ?? "NaN") * 100);
        const actualCurrency = String((resource.amount as Record<string, unknown> | undefined)?.currency_code ?? "").toUpperCase();
        if (actualMinor !== amount.minorUnits || actualCurrency !== amount.currencyCode.toUpperCase()) {
          throw new Error("PayPal capture amount or currency does not match the server payment.");
        }
        const feeValue = ((resource.seller_receivable_breakdown as Record<string, unknown> | undefined)?.paypal_fee) as Record<string, unknown> | undefined;
        const gatewayFeeMinorUnits = feeValue ? Math.round(Number(feeValue.value ?? "NaN") * 100) : undefined;
        const gatewayFeeCurrencyCode = String(feeValue?.currency_code ?? "").toUpperCase();
        await setPaymentStatus(located.orderId, located.context, "SUCCEEDED", {
          provider: "PAYPAL", providerReceiptId: String(resource.id ?? ""),
          gatewayFeeMinorUnits: Number.isSafeInteger(gatewayFeeMinorUnits) && Number(gatewayFeeMinorUnits) >= 0 ? gatewayFeeMinorUnits : undefined,
          gatewayFeeCurrencyCode: gatewayFeeCurrencyCode || undefined,
          verifiedAtEpochMillis: Date.now()
        });
        if (!(await handleCaptureAfterCancellation(located.orderId))) {
          await maybeCaptureAfterAuthorization(located.orderId);
        }
      } else if (eventType === "PAYMENT.AUTHORIZATION.VOIDED" && located) {
        await setPaymentStatus(located.orderId, located.context, "CANCELLED", {
          provider: "PAYPAL", providerAuthorizationId: String(resource.id ?? "")
        });
      } else if (eventType === "PAYMENT.CAPTURE.REFUNDED" && located) {
        const total = moneyValue(located.context.payment.amount);
        const previous = moneyValue(located.context.payment.refundAmount, total.currencyCode);
        const refund = Math.round(Number((resource.amount as Record<string, unknown> | undefined)?.value ?? "NaN") * 100);
        if (!Number.isSafeInteger(refund) || refund <= 0) throw new Error("PayPal refund amount is invalid.");
        const cumulative = Math.min(total.minorUnits, previous.minorUnits + refund);
        const refundStatus = cumulative >= total.minorUnits ? "REFUNDED" : "PARTIALLY_REFUNDED";
        await getDatabase().ref("payments/" + located.orderId).update({
          status: refundStatus,
          refundAmount: { minorUnits: cumulative, currencyCode: total.currencyCode },
          refundRequest: { ...(located.context.payment.refundRequest as Record<string, unknown> ?? {}), status: "COMPLETED", updatedAtEpochMillis: Date.now() },
          requiresAdminReconciliation: String(located.context.payment.breakdownStatus ?? "") === "ALLOCATED" ||
            (refundStatus === "PARTIALLY_REFUNDED" && String(located.context.payment.breakdownStatus ?? "") !== "ALLOCATED"),
          updatedAtEpochMillis: Date.now()
        });
        await handleCaptureAfterCancellation(located.orderId);
      } else if (eventType === "PAYMENT.PAYOUTS-ITEM.SUCCEEDED" || eventType === "PAYMENT.PAYOUTS-ITEM.FAILED") {
        const payoutItem = resource;
        const payoutId = String(payoutItem.sender_item_id ?? (payoutItem.payout_item as Record<string, unknown> | undefined)?.sender_item_id ?? "");
        if (payoutId) {
          const locatedPayout = await getDatabase().ref("payouts/" + payoutId).get();
          const record = locatedPayout.val() as Record<string, unknown> | null;
          if (record && record.provider === "PAYPAL") {
            await finalizePayoutEvent(
              payoutId,
              eventType === "PAYMENT.PAYOUTS-ITEM.SUCCEEDED" ? "PAID" : "FAILED",
              String(payoutItem.payout_item_id ?? payoutItem.id ?? "")
            );
          }
        }
      }
      await completeProviderEvent("paypal", eventId);
      response.status(200).send("OK");
    } catch (error) {
      await failProviderEvent("paypal", eventId, error);
      console.error("PayPal webhook failed", error instanceof Error ? error.message : "unknown error");
      response.status(500).send("Retry webhook processing");
    }
  } catch (error) {
    console.error("PayPal webhook rejected", error instanceof Error ? error.message : "unknown error");
    response.status(500).send("Webhook processing error");
  }
});

/** Retry authorizations when a status-trigger or transient provider API attempt was interrupted. */
export const retryAuthorizedPaymentCapture = onSchedule({
  schedule: "every 5 minutes",
  timeoutSeconds: 540,
  secrets: [stripeSecretKey, paypalClientId, paypalClientSecret]
}, async () => {
  const snapshot = await getDatabase().ref("payments").orderByChild("status").equalTo("AUTHORIZED").limitToFirst(100).get();
  const raw = snapshot.val() as Record<string, unknown> | null;
  for (const [orderId] of Object.entries(raw ?? {})) {
    try {
      const context = await loadPaymentContext(orderId);
      if (!context) continue;
      const root = await getDatabase().ref(context.rootPath + "/" + orderId).get();
      const status = String(root.child("status").val() ?? "");
      if (status === "CANCELLED" || status === "CANCELED") await releasePaymentAuthorization(orderId, context);
      else await maybeCaptureAfterAuthorization(orderId);
    } catch (error) {
      console.error("Authorization retry deferred for", orderId, error instanceof Error ? error.message : "unknown error");
    }
  }
});

/** Retry terminal-order settlements while waiting for provider fee data or a transient provider response. */
export const retryPaymentSettlement = onSchedule({
  schedule: "every 15 minutes",
  timeoutSeconds: 540,
  secrets: [stripeSecretKey, paypalClientId, paypalClientSecret]
}, async () => {
  const snapshot = await getDatabase().ref("payments").orderByChild("status").equalTo("SUCCEEDED").limitToFirst(100).get();
  const raw = snapshot.val() as Record<string, unknown> | null;
  for (const [orderId, value] of Object.entries(raw ?? {})) {
    const payment = value as Record<string, unknown>;
    if (payment.breakdownStatus === "ALLOCATED") continue;
    try { await settleCompletedPayment(orderId); }
    catch (error) { console.error("Settlement retry deferred for", orderId, error instanceof Error ? error.message : "unknown error"); }
  }
});

