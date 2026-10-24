import { onCall, HttpsError } from "firebase-functions/v2/https";
import { initializeApp } from "firebase-admin/app";
import { getDatabase } from "firebase-admin/database";

initializeApp();

type OrderType = "FOOD" | "RETAIL";
type ItemRequest = { id: string; quantity: number };

function minorUnits(value: unknown, label: string): number {
  const amount = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(amount) || amount < 0) throw new HttpsError("failed-precondition", `Invalid ${label} in catalog.`);
  return Math.round(amount * 100);
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
  const method = rawMethod.includes("PAYPAL") ? "PAYPAL"
    : rawMethod.includes("STRIPE") || rawMethod.includes("CARD") ? "STRIPE"
    : rawMethod.includes("CASH") ? "CASH" : "";

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
    const ownerUid = typeof source.ownerUid === "string" ? source.ownerUid : "";
    const profile = ownerUid ? await db.ref(`users/${ownerUid}`).get() : null;
    approved = source.status === "APPROVED" && (profile?.child("shopStatus").val() === "APPROVED" || profile?.child("role").val() === "ADMIN");
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

  const fee = orderType === "FOOD"
    ? minorUnits(source.deliveryFee ?? 0, "delivery fee")
    : minorUnits((await db.ref("settings/deliveryFees/RETAIL").get()).val() ?? 3, "retail delivery fee");
  const total = subtotal + fee;
  const orderRef = db.ref("orders").push();
  const orderId = orderRef.key;
  if (!orderId) throw new HttpsError("internal", "Could not allocate an order ID.");
  const merchantId = orderType === "FOOD" ? sourceId : String(source.ownerUid ?? sourceId);
  const now = Date.now();
  const order = {
    id: orderId, clientId: uid, orderType, sourceId,
    sourceName: typeof source.name === "string" ? source.name : String(source.fullName ?? ""),
    items: normalizedItems, subtotal: subtotal / 100, deliveryFee: fee / 100, totalPrice: total / 100,
    status: "PLACED", pickupLocation: String(source.address ?? ""),
    pickupLat: Number(source.latitude) || 0, pickupLng: Number(source.longitude) || 0,
    dropoffLocation, dropoffLat: lat, dropoffLng: lng, createdAtTimestamp: now
  };
  const payment = {
    orderId, clientId: uid, merchantId, courierId: "", method, status: "PENDING",
    amount: { minorUnits: total, currencyCode: "USD" },
    breakdown: {
      merchantShare: { minorUnits: subtotal, currencyCode: "USD" },
      courierShare: { minorUnits: 0, currencyCode: "USD" },
      platformShare: { minorUnits: fee, currencyCode: "USD" },
      gatewayFee: { minorUnits: 0, currencyCode: "USD" }
    },
    providerPaymentId: "", refundAmount: { minorUnits: 0, currencyCode: "USD" }, updatedAtEpochMillis: now
  };
  await db.ref().update({ [`orders/${orderId}`]: order, [`payments/${orderId}`]: payment });
  return { orderId, subtotalMinor: subtotal, deliveryFeeMinor: fee, totalMinor: total, currencyCode: "USD" };
});
