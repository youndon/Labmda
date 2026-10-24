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
      String(context.payment.providerReference ?? context.payment.providerPaymentId ?? ""),
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
      intent: "CAPTURE",
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

  if (!recordedSessionId || sessionId !== recordedSessionId || resultToken !== recordedSessionId) {
    throw new HttpsError("permission-denied", "The returned session does not match this payment.");
  }

  if (provider === "STRIPE" && recordedSessionId.startsWith("pi_")) {
    const key = stripeSecretKey.value().trim();
    if (!key) throw new HttpsError("failed-precondition", "Stripe server credentials are not configured.");
    return applyStripeIntentState(orderId, context, key, recordedSessionId, expectedMinorUnits, expectedCurrency, false);
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

/**
 * Payout execution deliberately fails closed until the wallet ledger and provider payout
 * integration are implemented. This guard ensures unapproved accounts cannot start payouts.
 */
export const requestPayout = onCall({ enforceAppCheck: true }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before requesting a payout.");
  const body = request.data as { amount?: { minorUnits?: number; currencyCode?: string } };
  const minorUnitsRequested = Number(body.amount?.minorUnits);
  const currencyCode = typeof body.amount?.currencyCode === "string"
    ? body.amount.currencyCode.toUpperCase() : "";
  if (!Number.isSafeInteger(minorUnitsRequested) || minorUnitsRequested <= 0 ||
      !/^[A-Z]{3}$/.test(currencyCode)) {
    throw new HttpsError("invalid-argument", "A positive payout amount and currency are required.");
  }
  const db = getDatabase();
  const profile = await db.ref("users/" + uid).get();
  const approvedMerchant = profile.child("foodStatus").val() === "APPROVED" ||
    profile.child("shopStatus").val() === "APPROVED";
  const approvedCourier = profile.child("deliveryServices").child("status").val() === "APPROVED";
  if (!approvedMerchant && !approvedCourier) {
    throw new HttpsError("permission-denied", "Only administrator-approved merchants and couriers may request payouts.");
  }

  // Keep a server-owned audit record of the rejected attempt, but do not reserve or change
  // balances until a transactional payout processor is configured.
  const payoutRef = db.ref("payouts").push();
  const now = Date.now();
  await payoutRef.set({
    id: payoutRef.key ?? "",
    uid,
    merchantId: approvedMerchant ? uid : "",
    courierId: approvedCourier ? uid : "",
    amount: { minorUnits: minorUnitsRequested, currencyCode },
    status: "REJECTED_DISABLED",
    rejectionReason: "PAYOUT_EXECUTION_NOT_CONFIGURED",
    providerReference: "",
    createdAtEpochMillis: now,
    updatedAtEpochMillis: now
  });
  throw new HttpsError("failed-precondition", "Payouts are disabled until server-side wallet reservation and provider payout processing are configured.");
});

