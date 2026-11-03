import { randomUUID } from "node:crypto";
import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { buildOrderPaymentAllocations, buildRidePaymentAllocations, buildStandaloneDeliveryAllocations } from "../finance/payment-allocations.js";
import { postPaymentReceiptFromDatabase } from "../finance/payment-repository.js";
import { createFinancialTransfer, getFinancialOperation, postFinancialOperation } from "../finance/ledger.js";

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
async function ensureOrderAllocations(orderId: string, courierId: string): Promise<void> {
  const orderSnapshot = await db().ref("orders/" + orderId).get();
  if (!orderSnapshot.exists()) return;
  const order = orderSnapshot.val() as Record<string, any>;
  const paymentId = readString(order.paymentId);
  if (!paymentId) return;
  const paymentSnapshot = await db().ref("payments/" + paymentId).get();
  if (!paymentSnapshot.exists()) fail("The order payment record is missing.");
  const payment = paymentSnapshot.val() as Record<string, any>;
  const existing = await db().ref("paymentAllocations/" + paymentId).get();
  if (!existing.exists()) {
    const breakdown = order.paymentBreakdown as Record<string, number>;
    const allocations = order.orderType === "DELIVERY"
      ? buildStandaloneDeliveryAllocations({
          paymentId, orderId, courierId,
          deliveryFeeMinor: breakdown.deliveryFeeMinor,
          courierEarningsMinor: breakdown.courierEarningsMinor,
          platformCommissionMinor: breakdown.platformCommissionMinor,
          providerFeeMinor: breakdown.providerFeeMinor,
          providerFeePayer: order.providerFeePayer === "CUSTOMER" ? "CUSTOMER" : "PLATFORM",
          customerTotalMinor: breakdown.customerTotalMinor, createdAt: Date.now(),
        })
      : buildOrderPaymentAllocations({ paymentId, orderId, merchantOwnerId: readString(order.merchantOwnerId), courierId, breakdown: breakdown as any, providerFeePayer: order.providerFeePayer === "CUSTOMER" ? "CUSTOMER" : "PLATFORM", createdAt: Date.now() });
    const rows: Record<string, unknown> = {};
    for (const allocation of allocations) rows[allocation.id] = allocation;
    const updates: Record<string, unknown> = {};
    updates["paymentAllocations/" + paymentId] = rows;
    updates["payments/" + paymentId + "/deliveryId"] = courierId;
    updates["payments/" + paymentId + "/updatedAt"] = Date.now();
    await db().ref().update(updates);
  } else if (payment.deliveryId !== courierId) {
    throw new HttpsError("failed-precondition", "Payment allocations already exist for a different courier.");
  }
  const latest = await db().ref("payments/" + paymentId).get();
  if (latest.child("status").val() === "SUCCEEDED") await postPaymentReceiptFromDatabase(db(), paymentId);
}

async function releaseSucceededOnlinePaymentAllocations(paymentId: string): Promise<void> {
  const paymentSnapshot = await db().ref("payments/" + paymentId).get();
  if (!paymentSnapshot.exists()) return;
  const payment = paymentSnapshot.val() as Record<string, any>;
  // Cash earners already hold their physical cash; cash settlement uses the
  // separate CASH_OWED/CASH_RETAINED workflow and must not credit wallets twice.
  if (payment.method !== "GCASH" || payment.status !== "SUCCEEDED") return;

  const allocationsSnapshot = await db().ref("paymentAllocations/" + paymentId).get();
  if (!allocationsSnapshot.exists()) throw new HttpsError("failed-precondition", "Payment allocations are missing.");
  const allocations = allocationsSnapshot.val() as Record<string, Record<string, any>>;
  for (const [allocationId, allocation] of Object.entries(allocations)) {
    if (allocation.status === "READY") continue;
    if (allocation.status !== "PENDING" || allocation.paymentId !== paymentId
      || !Number.isSafeInteger(allocation.amountMinor) || allocation.amountMinor <= 0
      || allocation.currency !== "PHP") {
      throw new HttpsError("failed-precondition", "A payment allocation cannot be released.");
    }
    const partyType = allocation.beneficiaryType === "DELIVERY" ? "COURIER"
      : allocation.beneficiaryType;
    const accountId = "wallet_" + String(allocation.beneficiaryType).toLowerCase() + "_" + allocation.beneficiaryId;
    const operation = createFinancialTransfer({
      operationId: "payment_" + paymentId + "_allocation_" + allocationId + "_released",
      eventType: "ADJUSTMENT", referenceType: "PAYMENT", referenceId: paymentId,
      idempotencyKey: "payment_" + paymentId + "_allocation_" + allocationId + "_released",
      createdAt: Date.now(), description: "Release completed-service payment allocation",
      amountMinor: allocation.amountMinor,
      source: { accountId, bucket: "PENDING", deltaMinor: 0, partyType, partyId: allocation.beneficiaryId },
      destination: { accountId, bucket: "AVAILABLE", deltaMinor: 0, partyType, partyId: allocation.beneficiaryId },
    });
    await postFinancialOperation(operation);
    await db().ref("paymentAllocations/" + paymentId + "/" + allocationId).transaction((current: Record<string, any> | null) => {
      if (!current || current.status === "READY") return current;
      if (current.status !== "PENDING") return;
      return { ...current, status: "READY", updatedAt: Date.now() };
    }, undefined, false);
  }
}

async function ensureRideAllocations(tripId: string, driverId: string): Promise<void> {
  const tripSnapshot = await db().ref("trips/" + tripId).get();
  if (!tripSnapshot.exists()) return;
  const trip = tripSnapshot.val() as Record<string, any>;
  const paymentId = readString(trip.paymentId);
  if (!paymentId) return;
  const existing = await db().ref("paymentAllocations/" + paymentId).get();
  if (!existing.exists()) {
    const breakdown = trip.paymentBreakdown as Record<string, number>;
    const allocations = buildRidePaymentAllocations({ paymentId, tripId, driverId, fareMinor: breakdown.fareMinor, driverEarningsMinor: breakdown.driverEarningsMinor, platformCommissionMinor: breakdown.platformCommissionMinor, providerFeeMinor: breakdown.providerFeeMinor, providerFeePayer: trip.providerFeePayer === "CUSTOMER" ? "CUSTOMER" : "PLATFORM", customerTotalMinor: breakdown.customerTotalMinor, createdAt: Date.now() });
    const rows: Record<string, unknown> = {};
    for (const allocation of allocations) rows[allocation.id] = allocation;
    const updates: Record<string, unknown> = {};
    updates["paymentAllocations/" + paymentId] = rows;
    await db().ref().update(updates);
  }
  const payment = await db().ref("payments/" + paymentId).get();
  if (payment.child("status").val() === "SUCCEEDED") await postPaymentReceiptFromDatabase(db(), paymentId);
}
export function isAllowedOrderTransition(from: string, to: string): boolean {
  return (from === "PLACED" && (to === "ACCEPTED" || to === "CANCELLED"))
    || (from === "ACCEPTED" && (to === "PREPARING" || to === "CANCELLED"))
    || (from === "PREPARING" && to === "OUT_FOR_DELIVERY");
}
export function isAllowedTripTransition(from: string, to: string): boolean {
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
    if (current === null) return null;
    if (current.status !== order.status) return;
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
  if (!profile.exists() || profile.child("deliveryServices/status").val() !== "APPROVED") {
    throw new HttpsError("permission-denied", "An approved delivery account is required.");
  }
  if (profile.child("deliveryServices/isOnline").val() !== true) {
    throw new HttpsError("failed-precondition", "Go online before claiming a delivery.");
  }
  const name = readString(profile.child("fullName").val()) || "Delivery";
  const phone = readString(profile.child("phoneNumber").val()) || readString(profile.child("phone").val());
  const orderRef = db().ref(`orders/${orderId}`);
  const result = await orderRef.transaction((current: Record<string, any> | null) => {
    if (current === null) return null;
    if (current.status !== "OUT_FOR_DELIVERY") return;
    if (readString(current.deliveryId).trim()) return;
    return { ...current, deliveryId: uid, deliveryName: name, deliveryPhone: phone, assignedAt: Date.now() };
  }, undefined, false);
  if (!result.committed) {
    const latest = result.snapshot.val() as Record<string, any> | null;
    if (latest?.deliveryId !== uid) fail("This delivery has already been claimed or is not ready for pickup.");
    await ensureOrderAllocations(orderId, uid);
    return { orderId, deliveryId: uid, duplicate: true };
  }
  await ensureOrderAllocations(orderId, uid);
  return { orderId, deliveryId: uid, duplicate: false };
});

/** A courier may release only their own not-yet-completed assignment. */
export const releaseOrderDelivery = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const orderId = requireId(call.data?.orderId, "Order ID");
  const result = await db().ref().transaction((root: Record<string, any> | null) => {
    if (root === null) return null;
    const order = root.orders?.[orderId] as Record<string, any> | undefined;
    if (!order || order.status !== "OUT_FOR_DELIVERY" || order.deliveryId !== uid || order.deliveryReassignment?.status === "PROCESSING") return;
    const paymentId = readString(order.paymentId);
    const payment = paymentId ? root.payments?.[paymentId] as Record<string, any> | undefined : undefined;
    // Once a non-cash payment has succeeded, its courier allocation is part of
    // the payment audit trail and cannot be reassigned through this operation.
    if (payment && payment.status !== "PENDING" && payment.status !== "PROCESSING") return;
    const nextRoot: Record<string, any> = {
      ...root,
      orders: { ...root.orders, [orderId]: { ...order, deliveryId: "", deliveryName: "", deliveryPhone: "", assignedAt: null } },
    };
    if (payment) {
      nextRoot.payments = { ...root.payments, [paymentId]: { ...payment, deliveryId: "", updatedAt: Date.now() } };
      if (root.paymentAllocations?.[paymentId]) {
        const allocations = root.paymentAllocations[paymentId] as Record<string, any>;
        if (Object.values(allocations).some((allocation: any) => allocation.status !== "PENDING")) return;
        const nextAllocations = { ...root.paymentAllocations };
        delete nextAllocations[paymentId];
        nextRoot.paymentAllocations = nextAllocations;
      }
    }
    return nextRoot;
  }, undefined, false);
  if (!result.committed) fail("Only the assigned courier can release an unpaid active delivery.");
  return { orderId, released: true };
});


/** Build the balanced pending-wallet transfer for an administrator courier replacement. */
export function buildDeliveryReassignmentTransfer(args: {
  operationId: string; orderId: string; previousCourierId: string;
  replacementCourierId: string; amountMinor: number; createdAt: number;
}) {
  if (!args.operationId || !args.orderId || !args.previousCourierId || !args.replacementCourierId
    || args.previousCourierId === args.replacementCourierId
    || !Number.isSafeInteger(args.amountMinor) || args.amountMinor <= 0
    || !Number.isSafeInteger(args.createdAt) || args.createdAt <= 0) {
    throw new Error("Delivery reassignment transfer details are invalid.");
  }
  return createFinancialTransfer({
    operationId: args.operationId, eventType: "ADJUSTMENT", referenceType: "ORDER",
    referenceId: args.orderId, idempotencyKey: args.operationId, createdAt: args.createdAt,
    description: "Administrator reassigned pending courier earnings for a paid delivery",
    amountMinor: args.amountMinor,
    source: { accountId: "wallet_delivery_" + args.previousCourierId, bucket: "PENDING",
      deltaMinor: 0, partyType: "COURIER", partyId: args.previousCourierId },
    destination: { accountId: "wallet_delivery_" + args.replacementCourierId, bucket: "PENDING",
      deltaMinor: 0, partyType: "COURIER", partyId: args.replacementCourierId },
  });
}

/** Admin recovery path for an abandoned, already-paid GCash delivery. */
export const adminReassignOrderDelivery = onCall({ enforceAppCheck: true }, async (call) => {
  const adminUid = requireUid(call.auth?.uid);
  if (!requireAdmin(call.auth?.token as Record<string, unknown> | undefined)) {
    throw new HttpsError("permission-denied", "Administrator access is required.");
  }
  const orderId = requireId(call.data?.orderId, "Order ID");
  const replacementCourierId = requireId(call.data?.replacementCourierId, "Replacement courier ID");
  const profile = await db().ref("users/" + replacementCourierId).get();
  if (!profile.exists() || profile.child("deliveryServices/status").val() !== "APPROVED") {
    throw new HttpsError("failed-precondition", "The replacement courier must have an approved delivery account.");
  }
  if (profile.child("deliveryServices/isOnline").val() !== true) {
    throw new HttpsError("failed-precondition", "The replacement courier must be online before reassignment.");
  }
  const replacementName = readString(profile.child("fullName").val()) || "Delivery";
  const replacementPhone = readString(profile.child("phoneNumber").val()) || readString(profile.child("phone").val());
  const reservationId = randomUUID();
  const now = Date.now();

  const reserved = await db().ref().transaction((root: Record<string, any> | null) => {
    if (!root) return;
    const order = root.orders?.[orderId] as Record<string, any> | undefined;
    if (!order || order.status !== "OUT_FOR_DELIVERY") return;
    const previousCourierId = readString(order.deliveryId);
    if (!previousCourierId || previousCourierId === replacementCourierId
      || order.deliveryReassignment?.status === "PROCESSING") return;
    const paymentId = readString(order.paymentId);
    const payment = paymentId ? root.payments?.[paymentId] as Record<string, any> | undefined : undefined;
    if (!payment || payment.id !== paymentId || payment.orderId !== orderId
      || payment.transactionType !== "ORDER" || payment.method !== "GCASH"
      || payment.status !== "SUCCEEDED" || payment.currency !== "PHP"
      || payment.deliveryId !== previousCourierId) return;
    const allocations = root.paymentAllocations?.[paymentId] as Record<string, any> | undefined;
    if (!allocations) return;
    const courierRows = Object.entries(allocations).filter(([, row]) => (row as any)?.beneficiaryType === "DELIVERY");
    if (courierRows.length !== 1) return;
    const pair = courierRows[0]!;
    const allocationId = pair[0];
    const allocation = pair[1] as Record<string, any>;
    if (allocation.id !== allocationId || allocation.paymentId !== paymentId
      || allocation.transactionType !== "ORDER" || allocation.orderId !== orderId
      || allocation.deliveryId !== previousCourierId || allocation.beneficiaryId !== previousCourierId
      || allocation.status !== "PENDING" || allocation.currency !== "PHP"
      || !Number.isSafeInteger(allocation.amountMinor) || allocation.amountMinor <= 0) return;
    const reservation = {
      id: reservationId, status: "PROCESSING", orderId, paymentId, allocationId,
      previousCourierId, replacementCourierId, amountMinor: allocation.amountMinor,
      initiatedBy: adminUid, createdAt: now,
    };
    return { ...root, orders: { ...root.orders, [orderId]: { ...order, deliveryReassignment: reservation } } };
  }, undefined, false);

  let reservation: Record<string, any>;
  if (!reserved.committed) {
    const current = reserved.snapshot.child("orders/" + orderId);
    if (current.child("deliveryId").val() === replacementCourierId
      && current.child("deliveryReassignment/status").val() === "COMPLETED") {
      return { orderId, deliveryId: replacementCourierId, duplicate: true };
    }
    const pending = current.child("deliveryReassignment").val() as Record<string, any> | null;
    if (current.child("status").val() === "OUT_FOR_DELIVERY"
      && pending?.status === "PROCESSING"
      && pending.replacementCourierId === replacementCourierId) {
      // Resume a prior invocation that reserved the order but timed out while
      // posting or finalizing the immutable ledger transfer.
      reservation = pending;
    } else {
      fail("This paid delivery cannot be reassigned. Verify its status, payment, courier and pending allocation.");
    }
  } else {
    reservation = reserved.snapshot.child("orders/" + orderId + "/deliveryReassignment").val() as Record<string, any>;
  }
  const activeReservationId = String(reservation.id);
  const operationId = "delivery_reassign_" + activeReservationId;
  const transfer = buildDeliveryReassignmentTransfer({
    operationId, orderId, previousCourierId: reservation.previousCourierId,
    replacementCourierId, amountMinor: reservation.amountMinor, createdAt: now,
  });
  try {
    await postFinancialOperation(transfer);
  } catch (error) {
    const committedOperation = await getFinancialOperation(operationId);
    if (!committedOperation) {
      await db().ref().transaction((root: Record<string, any> | null) => {
        const order = root?.orders?.[orderId] as Record<string, any> | undefined;
        if (!order || order.deliveryReassignment?.id !== activeReservationId) return;
        const nextOrder = { ...order };
        delete nextOrder.deliveryReassignment;
        return { ...root, orders: { ...root.orders, [orderId]: nextOrder } };
      }, undefined, false);
      throw error;
    }
  }

  const completedAt = Date.now();
  const completed = await db().ref().transaction((root: Record<string, any> | null) => {
    if (!root) return;
    const order = root.orders?.[orderId] as Record<string, any> | undefined;
    if (!order || order.status !== "OUT_FOR_DELIVERY"
      || order.deliveryId !== reservation.previousCourierId
      || order.deliveryReassignment?.id !== activeReservationId
      || order.deliveryReassignment?.status !== "PROCESSING") return;
    const payment = root.payments?.[reservation.paymentId] as Record<string, any> | undefined;
    const allocation = root.paymentAllocations?.[reservation.paymentId]?.[reservation.allocationId] as Record<string, any> | undefined;
    if (!payment || payment.status !== "SUCCEEDED" || payment.deliveryId !== reservation.previousCourierId
      || !allocation || allocation.status !== "PENDING"
      || allocation.beneficiaryId !== reservation.previousCourierId
      || allocation.amountMinor !== reservation.amountMinor) return;
    const audit = {
      id: activeReservationId, actorUid: adminUid, orderId, paymentId: reservation.paymentId,
      previousCourierId: reservation.previousCourierId, replacementCourierId,
      amountMinor: reservation.amountMinor, currency: "PHP", ledgerOperationId: operationId,
      createdAt: now, completedAt,
    };
    return {
      ...root,
      orders: { ...root.orders, [orderId]: {
        ...order, deliveryId: replacementCourierId, deliveryName: replacementName,
        deliveryPhone: replacementPhone, assignedAt: completedAt,
        deliveryReassignment: { ...order.deliveryReassignment, status: "COMPLETED", ledgerOperationId: operationId, completedAt },
      } },
      payments: { ...root.payments, [reservation.paymentId]: { ...payment, deliveryId: replacementCourierId, updatedAt: completedAt } },
      paymentAllocations: { ...root.paymentAllocations, [reservation.paymentId]: {
        ...root.paymentAllocations[reservation.paymentId],
        [reservation.allocationId]: { ...allocation, beneficiaryId: replacementCourierId, deliveryId: replacementCourierId, updatedAt: completedAt },
      } },
      deliveryReassignments: { ...(root.deliveryReassignments ?? {}), [activeReservationId]: audit },
      adminAuditLogs: { ...(root.adminAuditLogs ?? {}), [activeReservationId]: {
        id: activeReservationId, actorUid: adminUid, action: "ORDER_DELIVERY_REASSIGNED",
        targetType: "ORDER", targetId: orderId,
        before: { deliveryId: reservation.previousCourierId },
        after: { deliveryId: replacementCourierId, amountMinor: reservation.amountMinor, ledgerOperationId: operationId },
        note: "Reassigned a succeeded GCash delivery and transferred its pending courier allocation.",
        createdAt: completedAt,
      } },
    };
  }, undefined, false);

  if (!completed.committed) {
    throw new HttpsError("internal", "The ledger transfer was posted but the delivery reassignment could not be finalized. The order is locked for administrator recovery.");
  }
  return { orderId, deliveryId: replacementCourierId, amountMinor: reservation.amountMinor, ledgerOperationId: operationId, duplicate: false };
});

/** Only the assigned courier can mark an order delivered. */
export const completeOrderDelivery = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const orderId = requireId(call.data?.orderId, "Order ID");
  const ref = db().ref(`orders/${orderId}`);
  const result = await ref.transaction((current: Record<string, any> | null) => {
    if (current === null) return null;
    if (current.status !== "OUT_FOR_DELIVERY" || current.deliveryId !== uid || current.deliveryReassignment?.status === "PROCESSING") return;
    return { ...current, status: "DELIVERED", deliveredAt: Date.now(), updatedAtTimestamp: Date.now() };
  }, undefined, false);
  if (!result.committed) fail("Only the assigned courier can complete an active delivery.");
  const completedOrder = result.snapshot.val() as Record<string, any>;
  if (typeof completedOrder.paymentId === "string" && completedOrder.paymentId) {
    await releaseSucceededOnlinePaymentAllocations(completedOrder.paymentId);
  }
  return { orderId, status: "DELIVERED" };
});

/** Driver acceptance is a first-claim-wins transaction, not a client-side read-then-write. */
export const claimRide = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const tripId = requireId(call.data?.tripId, "Trip ID");
  const profile = await db().ref(`users/${uid}`).get();
  if (!profile.exists() || profile.child("driverService/status").val() !== "APPROVED") {
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
    if (current === null) return null;
    if (current.status !== "REQUESTED" || readString(current.driverId).trim()) return;
    return { ...current, driverId: uid, driverName: fullName, driverPhone: phone, vehicleDetails, status: "ACCEPTED", acceptedAt: Date.now() };
  }, undefined, false);
  if (!result.committed) {
    const latest = result.snapshot.val() as Record<string, any> | null;
    if (latest?.driverId !== uid || latest?.status !== "ACCEPTED") fail("This ride has already been accepted or is no longer available.");
    await ensureRideAllocations(tripId, uid);
    return { tripId, driverId: uid, duplicate: true };
  }
  await ensureRideAllocations(tripId, uid);
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
    if (current === null) return null;
    if (current.driverId !== uid || !isAllowedTripTransition(readString(current.status), nextStatus)) return;
    return { ...current, status: nextStatus, updatedAtTimestamp: Date.now(), ...(nextStatus === "COMPLETED" ? { completedAt: Date.now() } : {}) };
  }, undefined, false);
  if (!result.committed) fail("Only the assigned driver can make this valid trip transition.");
  if (nextStatus === "COMPLETED") {
    const completedTrip = result.snapshot.val() as Record<string, any>;
    if (typeof completedTrip.paymentId === "string" && completedTrip.paymentId) {
      await releaseSucceededOnlinePaymentAllocations(completedTrip.paymentId);
    }
  }
  return { tripId, status: nextStatus };
});

/** Passenger may cancel only their own trip before the ride starts. */
export const cancelPassengerTrip = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const tripId = requireId(call.data?.tripId, "Trip ID");
  const result = await db().ref().transaction((root: Record<string, any> | null) => {
    if (root === null) return null;
    const trip = root.trips?.[tripId] as Record<string, any> | undefined;
    if (!trip || trip.passengerId !== uid || !["REQUESTED", "ACCEPTED"].includes(readString(trip.status))) return;
    const paymentId = readString(trip.paymentId);
    const payment = paymentId ? root.payments?.[paymentId] as Record<string, any> | undefined : undefined;
    if (payment && payment.status !== "PENDING" && payment.status !== "PROCESSING") return;
    const now = Date.now();
    const nextRoot: Record<string, any> = {
      ...root,
      trips: { ...root.trips, [tripId]: { ...trip, status: "CANCELLED", cancelledAt: now, updatedAtTimestamp: now } },
    };
    if (payment) nextRoot.payments = { ...root.payments, [paymentId]: { ...payment, status: "CANCELLED", updatedAt: now, completedAt: now } };
    return nextRoot;
  }, undefined, false);
  if (!result.committed) fail("Only the passenger can cancel an unpaid ride before it starts.");
  return { tripId, status: "CANCELLED" };
});


/** Customer may cancel only their own order before merchant preparation or paid fulfilment. */
export const cancelCustomerOrder = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireUid(call.auth?.uid);
  const orderId = requireId(call.data?.orderId, "Order ID");
  const result = await db().ref().transaction((root: Record<string, any> | null) => {
    if (root === null) return null;
    const order = root.orders?.[orderId] as Record<string, any> | undefined;
    if (!order || order.clientId !== uid) return;
    const orderStatus = readString(order.status);
    const cancellable = ["PENDING_PAYMENT", "PLACED"].includes(orderStatus)
      || (order.orderType === "DELIVERY" && orderStatus === "OUT_FOR_DELIVERY" && !readString(order.deliveryId));
    if (!cancellable) return;
    const paymentId = readString(order.paymentId);
    const payment = paymentId ? root.payments?.[paymentId] as Record<string, any> | undefined : undefined;
    if (payment && payment.status !== "PENDING" && payment.status !== "PROCESSING") return;
    const now = Date.now();
    const nextOrder = { ...order, status: "CANCELLED", paymentStatus: payment ? "CANCELLED" : order.paymentStatus, updatedAtTimestamp: now, cancelledAt: now };
    const nextRoot: Record<string, any> = { ...root, orders: { ...root.orders, [orderId]: nextOrder } };
    if (payment) nextRoot.payments = { ...root.payments, [paymentId]: { ...payment, status: "CANCELLED", updatedAt: now, completedAt: now } };
    return nextRoot;
  }, undefined, false);
  if (!result.committed) fail("Only the customer can cancel an unpaid order before preparation.");
  return { orderId, status: "CANCELLED" };
});
