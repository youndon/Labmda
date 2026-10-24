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
  const fee = Number((balance as Record<string, unknown>).fee);
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
  const walletRef = getDatabase().ref("wallets/" + uid);
  await walletRef.transaction((current) => {
    const wallet = current && typeof current === "object" ? current as Record<string, unknown> : {};
    const applied = wallet.appliedLedgerIds && typeof wallet.appliedLedgerIds === "object"
      ? wallet.appliedLedgerIds as Record<string, unknown> : {};
    if (applied[postingId] === true) return;
    const available = moneyValue(wallet.available, currencyCode);
    const cashOwed = moneyValue(wallet.cashOwed, currencyCode);
    const lifetime = moneyValue(wallet.lifetimeEarnings, currencyCode);
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
  } else if (provider === "PAYPAL" && typeof payment.gatewayFeeMinorUnits === "number") {
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
  const courierId = String(payment.courierId ?? (context.rootPath === "trips" ? root.driverId ?? "" : root.deliveryId ?? ""));
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
    const intentId = String(payment.providerPaymentIntentId ?? "");
    if (!intentId) return;
    const key = stripeSecretKey.value().trim();
    const intent = await stripeApi<Record<string, unknown>>("payment_intents/" + encodeURIComponent(intentId), key);
    if (intent.status === "requires_capture") {
      await stripeApi<Record<string, unknown>>(
        "payment_intents/" + encodeURIComponent(intentId) + "/cancel", key, "POST",
        new URLSearchParams(), "labmda-cancel-" + orderId
      );
    }
  } else if (provider === "PAYPAL") {
    const authorizationId = String(payment.providerAuthorizationId ?? "");
    if (!authorizationId) return;
    const access = await getPayPalAccessToken();
    const response = await fetch(access.baseUrl + "/v2/payments/authorizations/" + encodeURIComponent(authorizationId) + "/void", {
      method: "POST",
      headers: { Authorization: "Bearer " + access.token, "PayPal-Request-Id": "labmda-void-" + orderId }
    });
    if (!response.ok) throw new Error("PayPal authorization void failed.");
  }
}

async function maybeCaptureAfterAuthorization(orderId: string): Promise<void> {
  const context = await loadPaymentContext(orderId);
  if (!context) return;
  const root = await getDatabase().ref(context.rootPath + "/" + orderId).get();
  if (!root.exists()) return;
  const status = String(root.child("status").val() ?? "");
  const shouldCapture = context.rootPath === "trips" ? status === "COMPLETED" : status === "ACCEPTED";
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
    auth_algo: headers["paypal-auth-algo"],
    cert_url: headers["paypal-cert-url"],
    transmission_id: headers["paypal-transmission-id"],
    transmission_sig: headers["paypal-transmission-sig"],
    transmission_time: headers["paypal-transmission-time"],
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

  const authoritative = await recomputeAuthoritativeAmount(orderId, context);
  const amount = payment.amount as Record<string, unknown> | undefined;
  const amountMinorUnits = Number(amount?.minorUnits);
  const currencyCode = String(amount?.currencyCode ?? "USD").toLowerCase();
  if (!Number.isSafeInteger(amountMinorUnits) || amountMinorUnits <= 0 ||
      amountMinorUnits !== authoritative.minorUnits ||
      currencyCode.toUpperCase() !== authoritative.currencyCode.toUpperCase() ||
      !/^[a-z]{3}$/.test(currencyCode)) {
    throw new HttpsError("failed-precondition", "The recomputed order or ride amount does not match its payment record.");
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
    const customerId = await getOrCreateStripeCustomer(uid, key);
    const form = new URLSearchParams();
    form.set("customer", customerId);
    form.set("mode", "payment");
    form.set("client_reference_id", orderId);
    form.set("metadata[orderId]", orderId);
    form.set("metadata[contextType]", context.rootPath === "trips" ? "RIDE" : "ORDER");
    form.set("payment_intent_data[metadata][orderId]", orderId);
    form.set("payment_intent_data[capture_method]", "manual");
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
      intent: "AUTHORIZE",
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
  if (normalizePaymentMethod(payment.provider ?? payment.method) !== provider) {
    throw new HttpsError("permission-denied", "This provider does not match the payment method.");
  }
  const recordedSessionId = String(payment.providerReference ?? payment.providerPaymentId ?? "");
  if (!recordedSessionId || (resultToken !== "cancel" && (sessionId !== recordedSessionId || resultToken !== recordedSessionId))) {
    throw new HttpsError("permission-denied", "The returned session does not match this payment.");
  }
  if (String(payment.status ?? "") === "SUCCEEDED") {
    return { accepted: true, message: "Payment is already confirmed by the server." };
  }
  if (String(payment.status ?? "") === "REFUNDED" || String(payment.status ?? "") === "PARTIALLY_REFUNDED") {
    throw new HttpsError("failed-precondition", "This payment has already been refunded.");
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
