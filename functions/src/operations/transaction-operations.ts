import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";

const db = () => getDatabase();
const idPattern = /^[A-Za-z0-9_-]{1,128}$/;

function requireUid(uid?: string): string {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to continue.");
  return uid;
}
function requireId(value: unknown, label: string): string {
  if (typeof value !== "string" || !idPattern.test(value)) {
    throw new HttpsError("invalid-argument", `${label} is invalid.`);
  }
  return value;
}
function requireAdmin(claims: Record<string, unknown> | undefined): boolean {
  return claims?.admin === true;
}
function fail(message: string): never {
  throw new HttpsError("failed-precondition", message);
}
function readString(value: unknown): string {
  return typeof value === "string" ? value : "";
}
function isAllowedOrderTransition(from: string, to: string): boolean {
  return (from === "PLACED" && (to === "ACCEPTED" || to === "CANCELLED"))
    || (from === "ACCEPTED" && (to === "PREPARING" || to === "CANCELLED"))
    || (from === "PREPARING" && to === "OUT_FOR_DELIVERY");
}
function isAllowedTripTransition(from: string, to: string): boolean {
  return (from === "ACCEPTED" && to === "IN_PROGRESS")
    || (from === "IN_PROGRESS" && to === "COMPLETED");
}

/** Merchant-only order state changes. Delivery completion is a separate courier operation. */
export const transitionOrderStatus = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const orderId = requireId(call.data?.orderId, "Order ID");
  const nextStatus = call.data?.nextStatus;
  if (typeof nextStatus !== "string" || !["ACCEPTED", "PREPARING", "OUT_FOR_DELIVERY", "CANCELLED"].includes(nextStatus)) {
    throw new HttpsError("invalid-argument", "The requested order transition is invalid.");
  }

  const orderRef = db().ref(`orders/${orderId}`);
  const before = await orderRef.get();
  if (!before.exists()) throw new HttpsError("not-found", "Order was not found.");
  const order = before.val() as Record<string, any>;
  const sourceId = readString(order.sourceId);
  const sourceRoot = order.orderType === "FOOD" ? "restaurants" : "shops";
  const source = await db().ref(`${sourceRoot}/${sourceId}`).get();
  const ownsSource = sourceId === uid || source.child("ownerUid").val() === uid
    || source.child("managerId").val() === uid;
  if (!ownsSource && !requireAdmin(call.auth?.token as Record<string, unknown> | undefined)) {
    throw new HttpsError("permission-denied", "Only this merchant or an administrator can change the order.");
  }
  if (!isAllowedOrderTransition(readString(order.status), nextStatus)
    && !(requireAdmin(call.auth?.token as Record<string, unknown> | undefined)
      && ["PLACED", "ACCEPTED", "PREPARING"].includes(readString(order.status)))) {
    fail(`Order cannot transition from ${readString(order.status)} to ${nextStatus}.`);
  }

  const result = await orderRef.transaction((current: Record<string, any> | null) => {
    if (!current || current.status !== order.status) return;
    if (!isAllowedOrderTransition(readString(current.status), nextStatus)
      && !(requireAdmin(call.auth?.token as Record<string, unknown> | undefined)
        && ["PLACED", "ACCEPTED", "PREPARING"].includes(readString(current.status)))) return;
    return { ...current, status: nextStatus, updatedAtTimestamp: Date.now() };
  }, undefined, false);
  if (!result.committed) fail("The order changed before this update. Refresh and try again.");
  return { orderId, status: nextStatus };
});

/** First eligible delivery account to claim an unassigned order wins atomically. */
export const claimOrderDelivery = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const orderId = requireId(call.data?.orderId, "Order ID");
  const profileRef = db().ref(`users/${uid}`);
  const profile = await profileRef.get();
  if (!profile.exists() || !profile.child("deliveryServices").exists()) {
    throw new HttpsError("permission-denied", "An approved delivery account is required.");
  }
  const name = readString(profile.child("fullName").val()) || "Delivery";
  const phone = readString(profile.child("phoneNumber").val()) || readString(profile.child("phone").val());
  const orderRef = db().ref(`orders/${orderId}`);
  const result = await orderRef.transaction((current: Record<string, any> | null) => {
    if (!current || current.status !== "OUT_FOR_DELIVERY") return;
    if (readString(current.deliveryId).trim()) return;
    return { ...current, deliveryId: uid, deliveryName: name, deliveryPhone: phone, assignedAt: Date.now() };
  }, undefined, false);
  if (!result.committed) {
    const latest = result.snapshot.val() as Record<string, any> | null;
    if (latest?.deliveryId === uid) return { orderId, deliveryId: uid, duplicate: true };
    fail("This delivery has already been claimed or is not ready for pickup.");
  }
  return { orderId, deliveryId: uid, duplicate: false };
});

/** A courier may release only their own not-yet-completed assignment. */
export const releaseOrderDelivery = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const orderId = requireId(call.data?.orderId, "Order ID");
  const ref = db().ref(`orders/${orderId}`);
  const result = await ref.transaction((current: Record<string, any> | null) => {
    if (!current || current.status !== "OUT_FOR_DELIVERY" || current.deliveryId !== uid) return;
    return { ...current, deliveryId: "", deliveryName: "", deliveryPhone: "", assignedAt: null };
  }, undefined, false);
  if (!result.committed) fail("Only the assigned courier can release an active delivery.");
  return { orderId, released: true };
});

/** Only the assigned courier can mark an order delivered. */
export const completeOrderDelivery = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const orderId = requireId(call.data?.orderId, "Order ID");
  const ref = db().ref(`orders/${orderId}`);
  const result = await ref.transaction((current: Record<string, any> | null) => {
    if (!current || current.status !== "OUT_FOR_DELIVERY" || current.deliveryId !== uid) return;
    return { ...current, status: "DELIVERED", deliveredAt: Date.now(), updatedAtTimestamp: Date.now() };
  }, undefined, false);
  if (!result.committed) fail("Only the assigned courier can complete an active delivery.");
  return { orderId, status: "DELIVERED" };
});

/** Driver acceptance is a first-claim-wins transaction, not a client-side read-then-write. */
export const claimRide = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const tripId = requireId(call.data?.tripId, "Trip ID");
  const profile = await db().ref(`users/${uid}`).get();
  if (!profile.exists() || !profile.child("driverService").exists()) {
    throw new HttpsError("permission-denied", "An approved driver account is required.");
  }
  if (profile.child("driverService/isOnline").val() !== true) {
    throw new HttpsError("failed-precondition", "Go online before accepting a ride.");
  }
  const fullName = readString(profile.child("fullName").val()) || "Verified Driver";
  const phone = readString(profile.child("phoneNumber").val()) || readString(profile.child("phone").val());
  const vehicle = profile.child("driverService/vehicleInfo");
  const make = readString(vehicle.child("make").val());
  const model = readString(vehicle.child("model").val());
  const color = readString(vehicle.child("color").val());
  const plate = readString(vehicle.child("licensePlate").val());
  const vehicleDetails = [color, make, model].filter(Boolean).join(" ") + (plate ? ` (${plate})` : "");
  const ref = db().ref(`trips/${tripId}`);
  const result = await ref.transaction((current: Record<string, any> | null) => {
    if (!current || current.status !== "REQUESTED" || readString(current.driverId).trim()) return;
    return { ...current, driverId: uid, driverName: fullName, driverPhone: phone, vehicleDetails, status: "ACCEPTED", acceptedAt: Date.now() };
  }, undefined, false);
  if (!result.committed) {
    const latest = result.snapshot.val() as Record<string, any> | null;
    if (latest?.driverId === uid && latest?.status === "ACCEPTED") return { tripId, driverId: uid, duplicate: true };
    fail("This ride has already been accepted or is no longer available.");
  }
  return { tripId, driverId: uid, duplicate: false };
});

/** Driver-only progress transitions. Passenger cancellation is a separate operation. */
export const transitionTripStatus = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const tripId = requireId(call.data?.tripId, "Trip ID");
  const nextStatus = call.data?.nextStatus;
  if (nextStatus !== "IN_PROGRESS" && nextStatus !== "COMPLETED") {
    throw new HttpsError("invalid-argument", "Only IN_PROGRESS or COMPLETED can be requested here.");
  }
  const ref = db().ref(`trips/${tripId}`);
  const result = await ref.transaction((current: Record<string, any> | null) => {
    if (!current || current.driverId !== uid || !isAllowedTripTransition(readString(current.status), nextStatus)) return;
    return { ...current, status: nextStatus, updatedAtTimestamp: Date.now(), ...(nextStatus === "COMPLETED" ? { completedAt: Date.now() } : {}) };
  }, undefined, false);
  if (!result.committed) fail("Only the assigned driver can make this valid trip transition.");
  return { tripId, status: nextStatus };
});

/** Passenger may cancel only their own trip before the ride starts. */
export const cancelPassengerTrip = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const tripId = requireId(call.data?.tripId, "Trip ID");
  const ref = db().ref(`trips/${tripId}`);
  const result = await ref.transaction((current: Record<string, any> | null) => {
    if (!current || current.passengerId !== uid || !["REQUESTED", "ACCEPTED"].includes(readString(current.status))) return;
    return { ...current, status: "CANCELLED", cancelledAt: Date.now(), updatedAtTimestamp: Date.now() };
  }, undefined, false);
  if (!result.committed) fail("Only the passenger can cancel before the ride starts.");
  return { tripId, status: "CANCELLED" };
});
