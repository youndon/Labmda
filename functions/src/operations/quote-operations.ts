import { randomUUID } from "node:crypto";
import { getDatabase, type Reference } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";

const db = () => getDatabase();
const idPattern = /^[A-Za-z0-9_-]{1,128}$/;

function uidOrThrow(uid?: string): string {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before placing an order or booking a ride.");
  return uid;
}
function requiredText(value: unknown, label: string): string {
  if (typeof value !== "string" || !value.trim()) throw new HttpsError("invalid-argument", `${label} is required.`);
  return value.trim();
}
function pesos(minor: unknown, label: string): number {
  if (!Number.isSafeInteger(minor) || (minor as number) < 0) throw new HttpsError("failed-precondition", `The quoted ${label} is invalid.`);
  return (minor as number) / 100;
}
async function claimQuote(quoteId: string, uid: string, kind: string) {
  if (!idPattern.test(quoteId)) throw new HttpsError("invalid-argument", "Quote ID is invalid.");
  const ref = db().ref(`pricing/quotes/${quoteId}`);
  const result = await ref.transaction((quote: Record<string, any> | null) => {
    if (!quote || quote.uid !== uid || quote.kind !== kind || quote.status !== "QUOTED"
      || !Number.isSafeInteger(quote.expiresAt) || quote.expiresAt <= Date.now()) return;
    return { ...quote, status: "CREATING", creationStartedAt: Date.now() };
  }, undefined, false);
  if (!result.committed) throw new HttpsError("failed-precondition", "This quote is expired, already used, or belongs to another account.");
  return { ref, quote: result.snapshot.val() as Record<string, any> };
}
async function restoreQuote(ref: Reference, quote: Record<string, any>) {
  await ref.transaction((current: Record<string, any> | null) => {
    if (!current || current.status !== "CREATING" || current.creationStartedAt !== quote.creationStartedAt) return;
    return { ...current, status: "QUOTED", creationStartedAt: null };
  }, undefined, false);
}

/** Creates an order exclusively from the customer's unexpired server quote. */
export const createOrderFromQuote = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = uidOrThrow(call.auth?.uid);
  const quoteId = requiredText(call.data?.quoteId, "Quote ID");
  const kind = call.data?.kind === "SHOP" ? "SHOP" : "FOOD";
  const { ref, quote } = await claimQuote(quoteId, uid, kind);
  try {
    const sourceId = requiredText(quote.sourceId, "Merchant");
    const root = kind === "FOOD" ? "restaurants" : "shops";
    const source = await db().ref(`${root}/${sourceId}`).get();
    if (!source.exists()) throw new HttpsError("not-found", "The selected merchant no longer exists.");
    const ownerUid = String(source.child("ownerUid").val() ?? source.child("managerId").val() ?? sourceId);
    const request = quote.request ?? {};
    const items = Array.isArray(quote.items) ? quote.items : [];
    if (!items.length) throw new HttpsError("failed-precondition", "The quote contains no order items.");
    const breakdown = quote.breakdown ?? {};
    const profile = await db().ref(`users/${uid}`).get();
    const orderId = randomUUID();
    const paymentId = randomUUID();
    const now = Date.now();
    const method = quote.paymentMethod === "GCASH" ? "GCASH" : "CASH";
    const order = {
      id: orderId, clientId: uid, orderType: kind === "FOOD" ? "FOOD" : "RETAIL",
      sourceId, sourceName: String(source.child("name").val() ?? source.child("businessName").val() ?? ""),
      merchantOwnerId: ownerUid,
      items: items.map((item: Record<string, any>) => ({
        id: String(item.itemId), name: String(item.name ?? ""), basePrice: pesos(item.unitPriceMinor, "item price"),
        quantity: item.quantity, specialInstructions: "",
      })),
      subtotal: pesos(breakdown.subtotalMinor, "subtotal"),
      deliveryFee: pesos(breakdown.deliveryFeeMinor, "delivery fee"),
      discount: pesos(breakdown.discountMinor ?? 0, "discount"),
      totalPrice: pesos(breakdown.customerTotalMinor, "customer total"),
      status: method === "GCASH" ? "PENDING_PAYMENT" : "PLACED",
      paymentId,
      paymentBreakdown: breakdown,
      providerFeePayer: method === "GCASH" ? String((await db().ref("pricing/config/providerFees/GCASH/payer").get()).val() ?? "PLATFORM") : "PLATFORM",
      paymentStatus: method === "GCASH" ? "PENDING" : "CASH_DUE",
      paymentMethod: method, quoteId,
      pickupLocation: String(request.pickupAddress ?? source.child("address").val() ?? ""),
      pickupLat: Number(request.pickup?.latitude ?? source.child("latitude").val() ?? 0),
      pickupLng: Number(request.pickup?.longitude ?? source.child("longitude").val() ?? 0),
      dropoffLocation: String(request.dropoffAddress ?? ""),
      dropoffLat: Number(request.dropoff?.latitude ?? 0),
      dropoffLng: Number(request.dropoff?.longitude ?? 0),
      createdAtTimestamp: now, updatedAtTimestamp: now,
      deliveryId: "", deliveryName: "", deliveryPhone: "",
      customerName: String(profile.child("fullName").val() ?? profile.child("name").val() ?? ""),
      customerPhone: String(profile.child("phoneNumber").val() ?? profile.child("phone").val() ?? ""),
      pricingVersion: quote.pricingVersion, currency: "PHP",
      amountMinor: breakdown.customerTotalMinor,
    };
    const updates: Record<string, unknown> = {};
    updates[`orders/${orderId}`] = order;
    updates[`payments/${paymentId}`] = {
      id: paymentId, transactionType: "ORDER", orderId, tripId: "", deliveryId: "",
      payerId: uid, method, status: "PENDING", amountMinor: breakdown.customerTotalMinor,
      currency: "PHP", refundedAmountMinor: 0, createdAt: now, updatedAt: now, completedAt: null,
    };
    updates[`pricing/quotes/${quoteId}/paymentId`] = paymentId;
    updates[`pricing/quotes/${quoteId}/status`] = "ORDER_CREATED";
    updates[`pricing/quotes/${quoteId}/orderId`] = orderId;
    updates[`pricing/quotes/${quoteId}/createdResourceAt`] = now;
    await db().ref().update(updates);
    return { orderId, status: order.status, paymentStatus: order.paymentStatus, totalMinor: breakdown.customerTotalMinor };
  } catch (error) {
    await restoreQuote(ref, quote);
    throw error;
  }
});

/** Creates a REQUESTED ride from a trusted route/fare quote; clients cannot assign a driver or set fare. */
export const createRideFromQuote = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = uidOrThrow(call.auth?.uid);
  const quoteId = requiredText(call.data?.quoteId, "Quote ID");
  const { ref, quote } = await claimQuote(quoteId, uid, "RIDE");
  try {
    const request = quote.request ?? {};
    const breakdown = quote.breakdown ?? {};
    const profile = await db().ref(`users/${uid}`).get();
    const tripId = randomUUID();
    const paymentId = randomUUID();
    const now = Date.now();
    const trip = {
      id: tripId, passengerId: uid,
      passengerName: String(profile.child("fullName").val() ?? profile.child("name").val() ?? "Passenger"),
      passengerPhone: String(profile.child("phoneNumber").val() ?? profile.child("phone").val() ?? ""),
      pickupLocation: String(request.pickupAddress ?? ""),
      dropoffLocation: String(request.dropoffAddress ?? ""),
      pickupLat: Number(request.pickup?.latitude ?? 0), pickupLng: Number(request.pickup?.longitude ?? 0),
      dropoffLat: Number(request.dropoff?.latitude ?? 0), dropoffLng: Number(request.dropoff?.longitude ?? 0),
      fare: pesos(breakdown.fareMinor, "ride fare"),
      paymentId,
      paymentBreakdown: breakdown,
      providerFeePayer: quote.paymentMethod === "GCASH" ? String((await db().ref("pricing/config/providerFees/GCASH/payer").get()).val() ?? "PLATFORM") : "PLATFORM",
      fareMinor: breakdown.fareMinor,
      customerTotalMinor: breakdown.customerTotalMinor,
      providerFeeMinor: breakdown.providerFeeMinor ?? 0,
      platformCommissionMinor: breakdown.platformCommissionMinor,
      driverEarningsMinor: breakdown.driverEarningsMinor,
      paymentMethod: quote.paymentMethod === "GCASH" ? "GCASH" : "CASH",
      paymentStatus: quote.paymentMethod === "GCASH" ? "PENDING" : "CASH_DUE",
      paymentState: quote.paymentMethod === "GCASH" ? "PENDING_PAYMENT" : "CASH_DUE",
      quoteId, pricingVersion: quote.pricingVersion, currency: "PHP",
      rideType: String(quote.rideType ?? "STANDARD"),
      driverId: "", driverName: "", driverPhone: "", vehicleDetails: "",
      status: quote.paymentMethod === "GCASH" ? "PENDING_PAYMENT" : "REQUESTED",
      createdAtTimestamp: now, updatedAtTimestamp: now,
    };
    const updates: Record<string, unknown> = {};
    updates[`trips/${tripId}`] = trip;
    updates[`payments/${paymentId}`] = {
      id: paymentId, transactionType: "RIDE", orderId: "", tripId, deliveryId: "",
      payerId: uid, method: quote.paymentMethod === "GCASH" ? "GCASH" : "CASH",
      status: "PENDING", amountMinor: breakdown.customerTotalMinor,
      currency: "PHP", refundedAmountMinor: 0, createdAt: now, updatedAt: now, completedAt: null,
    };
    updates[`pricing/quotes/${quoteId}/paymentId`] = paymentId;
    updates[`pricing/quotes/${quoteId}/status`] = "RIDE_CREATED";
    updates[`pricing/quotes/${quoteId}/tripId`] = tripId;
    updates[`pricing/quotes/${quoteId}/createdResourceAt`] = now;
    await db().ref().update(updates);
    return { tripId, status: trip.status, fareMinor: breakdown.fareMinor, customerTotalMinor: breakdown.customerTotalMinor };
  } catch (error) {
    await restoreQuote(ref, quote);
    throw error;
  }
});
