import { randomUUID } from "node:crypto";
import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import type { CashCollection, Payment, PaymentAllocation } from "../models/payment.js";
import { decimalPesosToMinor } from "../pricing/engine.js";
import { buildOrderPaymentAllocations, buildRidePaymentAllocations } from "./payment-allocations.js";

type QuoteRecord = Record<string, any>;
type RootRecord = Record<string, any>;

const db = () => getDatabase();
const idPattern = /^[A-Za-z0-9_-]{1,128}$/;

function requireUid(uid?: string): string {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before initializing payment.");
  return uid;
}
function requireId(value: unknown, field: string): string {
  if (typeof value !== "string" || !idPattern.test(value)) {
    throw new HttpsError("invalid-argument", `${field} is invalid.`);
  }
  return value;
}
function requireCashQuote(quote: QuoteRecord, uid: string, kind: "FOOD" | "SHOP" | "RIDE"): void {
  if (quote.uid !== uid) throw new HttpsError("permission-denied", "This quote belongs to another customer.");
  if (quote.kind !== kind || quote.paymentMethod !== "CASH" || quote.currency !== "PHP") {
    throw new HttpsError("failed-precondition", "The quote does not match a PHP cash payment.");
  }
  if (quote.status !== "QUOTED" || !Number.isSafeInteger(quote.expiresAt) || quote.expiresAt <= Date.now()) {
    throw new HttpsError("failed-precondition", "The quote has expired or has already been used.");
  }
}
function quoteTotal(quote: QuoteRecord): number {
  const amount = quote.breakdown?.customerTotalMinor;
  if (!Number.isSafeInteger(amount) || amount <= 0 || quote.breakdown?.currency !== "PHP") {
    throw new HttpsError("failed-precondition", "The quote does not contain a valid PHP total.");
  }
  return amount;
}
function closeEnough(actual: unknown, expected: unknown): boolean {
  return typeof actual === "number" && Number.isFinite(actual)
    && typeof expected === "number" && Number.isFinite(expected)
    && Math.abs(actual - expected) <= 0.00001;
}
function validateRouteMatches(record: Record<string, any>, quote: QuoteRecord, label: string): void {
  const pickup = quote.request?.pickup;
  const dropoff = quote.request?.dropoff;
  if (!pickup || !dropoff
    || !closeEnough(record.pickupLat, pickup.latitude)
    || !closeEnough(record.pickupLng, pickup.longitude)
    || !closeEnough(record.dropoffLat, dropoff.latitude)
    || !closeEnough(record.dropoffLng, dropoff.longitude)) {
    throw new HttpsError("failed-precondition", `${label} locations do not match the server quote.`);
  }
}
export function validateOrderMatches(order: Record<string, any>, quote: QuoteRecord, uid: string): void {
  if (order.clientId !== uid || order.sourceId !== quote.sourceId
    || !["PLACED", "ACCEPTED", "PREPARING", "OUT_FOR_DELIVERY"].includes(order.status)
    || order.paymentMethod !== "CASH") {
    throw new HttpsError("failed-precondition", "Order ownership, status, merchant or payment method does not match the quote.");
  }
  const expectedType = quote.kind === "FOOD" ? "FOOD" : "RETAIL";
  if (order.orderType !== expectedType) throw new HttpsError("failed-precondition", "Order type does not match the quote.");
  validateRouteMatches(order, quote, "Order");
  const quoteItems = Array.isArray(quote.items) ? quote.items : [];
  const orderItems = Array.isArray(order.items) ? order.items : [];
  const normalize = (items: Array<Record<string, any>>, idField: string) =>
    items.map((item) => [String(item[idField] ?? ""), Number(item.quantity)])
      .sort((a, b) => String(a[0]).localeCompare(String(b[0])));
  const quoted = normalize(quoteItems, "itemId");
  const placed = normalize(orderItems, "id");
  if (quoted.length === 0 || JSON.stringify(quoted) !== JSON.stringify(placed)) {
    throw new HttpsError("failed-precondition", "Order items or quantities do not match the server quote.");
  }
  const breakdown = quote.breakdown ?? {};
  if (decimalPesosToMinor(order.subtotal, "order subtotal") !== breakdown.subtotalMinor
    || decimalPesosToMinor(order.deliveryFee, "order delivery fee") !== breakdown.deliveryFeeMinor
    || decimalPesosToMinor(order.totalPrice, "order total") !== breakdown.customerTotalMinor) {
    throw new HttpsError("failed-precondition", "Order totals do not match the server-generated quote.");
  }
}
export function validateRideMatches(trip: Record<string, any>, quote: QuoteRecord, uid: string): void {
  if (trip.passengerId !== uid || !["ACCEPTED", "IN_PROGRESS", "COMPLETED"].includes(trip.status)
    || typeof trip.driverId !== "string" || !trip.driverId) {
    throw new HttpsError("failed-precondition", "The ride must belong to you and have an accepted driver before cash payment is initialized.");
  }
  validateRouteMatches(trip, quote, "Ride");
  if (decimalPesosToMinor(trip.fare, "ride fare") !== quote.breakdown?.fareMinor) {
    throw new HttpsError("failed-precondition", "Ride fare does not match the server-generated quote.");
  }
}
function allocationMap(rows: PaymentAllocation[]): Record<string, PaymentAllocation> {
  return Object.fromEntries(rows.map((row) => [row.id, row]));
}
export function buildCashPaymentBundle(args: {
  paymentId: string; uid: string; transactionType: "ORDER" | "RIDE";
  orderId: string; tripId: string; deliveryId: string; collectorId: string;
  amountMinor: number; allocations: PaymentAllocation[]; now: number;
}): { payment: Payment; allocations: Record<string, PaymentAllocation>; cashCollection: CashCollection } {
  const payment: Payment = {
    id: args.paymentId,
    transactionType: args.transactionType,
    orderId: args.orderId,
    tripId: args.tripId,
    deliveryId: args.deliveryId,
    payerId: args.uid,
    method: "CASH",
    status: "PENDING",
    amountMinor: args.amountMinor,
    currency: "PHP",
    refundedAmountMinor: 0,
    createdAt: args.now,
    updatedAt: args.now,
    completedAt: null,
  };
  const cashCollection: CashCollection = {
    id: `cash_${args.paymentId}`,
    paymentId: args.paymentId,
    orderId: args.orderId,
    tripId: args.tripId,
    deliveryId: args.deliveryId,
    collectorId: args.collectorId,
    payerId: args.uid,
    amountMinor: args.amountMinor,
    currency: "PHP",
    status: "PENDING",
    collectedAt: null,
    createdAt: args.now,
    notes: "",
  };
  return { payment, allocations: allocationMap(args.allocations), cashCollection };
}

/**
 * Creates a cash payment only from a server-owned quote and a matching,
 * already-assigned order. Payment, allocations, collection evidence and quote
 * consumption are committed in one RTDB root transaction.
 */
export const initializeOrderCashPayment = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const quoteId = requireId(call.data?.quoteId, "Quote ID");
  const orderId = requireId(call.data?.orderId, "Order ID");
  const quoteSnapshot = await db().ref(`pricing/quotes/${quoteId}`).get();
  if (!quoteSnapshot.exists()) throw new HttpsError("not-found", "The checkout quote was not found.");
  const quote = quoteSnapshot.val() as QuoteRecord;
  if (quote.kind !== "FOOD" && quote.kind !== "SHOP") throw new HttpsError("failed-precondition", "This quote is not an order quote.");
  requireCashQuote(quote, uid, quote.kind);
  const amountMinor = quoteTotal(quote);
  const orderSnapshot = await db().ref(`orders/${orderId}`).get();
  if (!orderSnapshot.exists()) throw new HttpsError("not-found", "Order was not found.");
  const order = orderSnapshot.val() as Record<string, any>;
  validateOrderMatches(order, quote, uid);
  const courierId = typeof order.deliveryId === "string" ? order.deliveryId : "";
  if (!courierId) throw new HttpsError("failed-precondition", "A courier must be assigned before cash payment can be initialized.");
  const sourcePath = quote.kind === "FOOD" ? `restaurants/${quote.sourceId}` : `shops/${quote.sourceId}`;
  const sourceSnapshot = await db().ref(sourcePath).get();
  if (!sourceSnapshot.exists()) throw new HttpsError("not-found", "The merchant record was not found.");
  const merchantOwnerId = quote.kind === "FOOD"
    ? String(sourceSnapshot.child("ownerUid").val() ?? quote.sourceId)
    : String(sourceSnapshot.child("ownerUid").val() ?? "");
  if (!merchantOwnerId) throw new HttpsError("failed-precondition", "The merchant owner UID is missing.");
  const breakdown = quote.breakdown;
  const now = Date.now();
  const paymentId = randomUUID();
  const allocations = buildOrderPaymentAllocations({
    paymentId, orderId, merchantOwnerId, courierId, breakdown,
    providerFeePayer: "PLATFORM", createdAt: now,
  });
  const bundle = buildCashPaymentBundle({
    paymentId, uid, transactionType: "ORDER", orderId, tripId: "", deliveryId: courierId,
    collectorId: courierId, amountMinor, allocations, now,
  });
  const result = await db().ref().transaction((raw: RootRecord | null) => {
    if (!raw) return;
    const currentQuote = raw.pricing?.quotes?.[quoteId] as QuoteRecord | undefined;
    const currentOrder = raw.orders?.[orderId] as Record<string, any> | undefined;
    if (!currentQuote || !currentOrder) return;
    if (currentQuote.status === "PAYMENT_CREATED" && currentQuote.orderId === orderId
      && typeof currentQuote.paymentId === "string" && raw.payments?.[currentQuote.paymentId]) return;
    if (currentQuote.status !== "QUOTED" || currentQuote.uid !== uid || currentQuote.expiresAt <= Date.now()
      || currentQuote.kind !== quote.kind || currentQuote.sourceId !== quote.sourceId
      || currentQuote.breakdown?.customerTotalMinor !== amountMinor) return;
    if (raw.paymentByOrder?.[orderId] || currentOrder.paymentId) return;
    validateOrderMatches(currentOrder, currentQuote, uid);
    if (currentOrder.deliveryId !== courierId) return;
    const source = raw[quote.kind === "FOOD" ? "restaurants" : "shops"]?.[quote.sourceId];
    if (!source) return;
    const currentOwnerId = quote.kind === "FOOD" ? String(source.ownerUid ?? quote.sourceId) : String(source.ownerUid ?? "");
    if (currentOwnerId !== merchantOwnerId) return;
    return {
      ...raw,
      orders: { ...raw.orders, [orderId]: { ...currentOrder, paymentId } },
      payments: { ...(raw.payments ?? {}), [paymentId]: bundle.payment },
      paymentAllocations: { ...(raw.paymentAllocations ?? {}), [paymentId]: bundle.allocations },
      cashCollections: { ...(raw.cashCollections ?? {}), [paymentId]: bundle.cashCollection },
      paymentByOrder: { ...(raw.paymentByOrder ?? {}), [orderId]: paymentId },
      pricing: { ...raw.pricing, quotes: { ...raw.pricing?.quotes, [quoteId]: { ...currentQuote, status: "PAYMENT_CREATED", paymentId, orderId, paymentCreatedAt: now } } },
    };
  }, undefined, false);
  const savedQuote = result.snapshot.child(`pricing/quotes/${quoteId}`).val() as QuoteRecord | null;
  if (!result.committed) {
    if (savedQuote?.status === "PAYMENT_CREATED" && savedQuote.orderId === orderId && savedQuote.paymentId) {
      return { paymentId: savedQuote.paymentId, status: "PENDING", amountMinor, currency: "PHP", duplicate: true };
    }
    throw new HttpsError("failed-precondition", "Payment initialization failed: the quote was used, the order changed, or a payment already exists.");
  }
  return { paymentId, status: "PENDING", amountMinor, currency: "PHP", duplicate: false };
});

/** Creates a cash payment for a ride only after a driver accepts the trip. */
export const initializeRideCashPayment = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const quoteId = requireId(call.data?.quoteId, "Quote ID");
  const tripId = requireId(call.data?.tripId, "Trip ID");
  const quoteSnapshot = await db().ref(`pricing/quotes/${quoteId}`).get();
  if (!quoteSnapshot.exists()) throw new HttpsError("not-found", "The ride quote was not found.");
  const quote = quoteSnapshot.val() as QuoteRecord;
  requireCashQuote(quote, uid, "RIDE");
  const amountMinor = quoteTotal(quote);
  const tripSnapshot = await db().ref(`trips/${tripId}`).get();
  if (!tripSnapshot.exists()) throw new HttpsError("not-found", "Trip was not found.");
  const trip = tripSnapshot.val() as Record<string, any>;
  validateRideMatches(trip, quote, uid);
  const driverId = trip.driverId as string;
  const breakdown = quote.breakdown;
  const now = Date.now();
  const paymentId = randomUUID();
  const allocations = buildRidePaymentAllocations({
    paymentId, tripId, driverId, fareMinor: breakdown.fareMinor,
    driverEarningsMinor: breakdown.driverEarningsMinor,
    platformCommissionMinor: breakdown.platformCommissionMinor,
    providerFeeMinor: breakdown.providerFeeMinor ?? 0,
    providerFeePayer: "PLATFORM",
    customerTotalMinor: amountMinor,
    createdAt: now,
  });
  const bundle = paymentBundle({
    paymentId, uid, transactionType: "RIDE", orderId: "", tripId, deliveryId: "",
    collectorId: driverId, amountMinor, allocations, now,
  });
  const result = await db().ref().transaction((raw: RootRecord | null) => {
    if (!raw) return;
    const currentQuote = raw.pricing?.quotes?.[quoteId] as QuoteRecord | undefined;
    const currentTrip = raw.trips?.[tripId] as Record<string, any> | undefined;
    if (!currentQuote || !currentTrip) return;
    if (currentQuote.status === "PAYMENT_CREATED" && currentQuote.tripId === tripId
      && typeof currentQuote.paymentId === "string" && raw.payments?.[currentQuote.paymentId]) return;
    if (currentQuote.status !== "QUOTED" || currentQuote.uid !== uid || currentQuote.expiresAt <= Date.now()
      || currentQuote.kind !== "RIDE" || currentQuote.breakdown?.customerTotalMinor !== amountMinor) return;
    if (raw.paymentByTrip?.[tripId] || currentTrip.paymentId) return;
    validateRideMatches(currentTrip, currentQuote, uid);
    if (currentTrip.driverId !== driverId) return;
    return {
      ...raw,
      trips: { ...raw.trips, [tripId]: { ...currentTrip, paymentId } },
      payments: { ...(raw.payments ?? {}), [paymentId]: bundle.payment },
      paymentAllocations: { ...(raw.paymentAllocations ?? {}), [paymentId]: bundle.allocations },
      cashCollections: { ...(raw.cashCollections ?? {}), [paymentId]: bundle.cashCollection },
      paymentByTrip: { ...(raw.paymentByTrip ?? {}), [tripId]: paymentId },
      pricing: { ...raw.pricing, quotes: { ...raw.pricing?.quotes, [quoteId]: { ...currentQuote, status: "PAYMENT_CREATED", paymentId, tripId, paymentCreatedAt: now } } },
    };
  }, undefined, false);
  const savedQuote = result.snapshot.child(`pricing/quotes/${quoteId}`).val() as QuoteRecord | null;
  if (!result.committed) {
    if (savedQuote?.status === "PAYMENT_CREATED" && savedQuote.tripId === tripId && savedQuote.paymentId) {
      return { paymentId: savedQuote.paymentId, status: "PENDING", amountMinor, currency: "PHP", duplicate: true };
    }
    throw new HttpsError("failed-precondition", "Payment initialization failed: the quote was used, the trip changed, or a payment already exists.");
  }
  return { paymentId, status: "PENDING", amountMinor, currency: "PHP", duplicate: false };
});
