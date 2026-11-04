import { getDatabase } from "firebase-admin/database";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { buildAutomaticRideRefundCase } from "../finance/ride-refunds.js";

export const UNACCEPTED_PAID_RIDE_TIMEOUT_MS = 10 * 60 * 1000;
const idPattern = /^[A-Za-z0-9_-]{1,128}$/;

type RideRoot = Record<string, any>;

/**
 * Atomically closes an expired, paid ride request, reserves its refundable
 * balance and removes it from the passenger's active request projection.
 * Driver acceptance and recovery race through the same RTDB root transaction.
 */
export function buildUnacceptedPaidRideRecoveryRoot(
  root: RideRoot,
  tripId: string,
  now: number,
  timeoutMs = UNACCEPTED_PAID_RIDE_TIMEOUT_MS,
): RideRoot | null {
  if (!idPattern.test(tripId) || !Number.isSafeInteger(now) || now <= 0
    || !Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) return null;

  const trip = root.trips?.[tripId] as Record<string, any> | undefined;
  if (!trip || trip.id !== tripId || trip.status !== "REQUESTED"
    || (typeof trip.driverId === "string" && trip.driverId.trim().length > 0)) return null;

  const paymentId = typeof trip.paymentId === "string" ? trip.paymentId : "";
  const passengerId = typeof trip.passengerId === "string" ? trip.passengerId : "";
  if (!idPattern.test(paymentId) || !idPattern.test(passengerId)) return null;

  const payment = root.payments?.[paymentId] as Record<string, any> | undefined;
  if (!payment || payment.id !== paymentId || payment.transactionType !== "RIDE"
    || payment.tripId !== tripId || payment.payerId !== passengerId
    || payment.method !== "GCASH" || payment.provider !== "BDO_CHECKOUT"
    || (payment.status !== "SUCCEEDED" && payment.status !== "PARTIALLY_REFUNDED")
    || payment.currency !== "PHP" || !Number.isSafeInteger(payment.amountMinor)
    || payment.amountMinor <= 0 || payment.amountMinor !== trip.customerTotalMinor
    || !Number.isSafeInteger(payment.completedAt)
    || payment.completedAt > now - timeoutMs || payment.refundProcessingId) return null;

  const refunded = payment.refundedAmountMinor ?? 0;
  const reserved = payment.refundReservedAmountMinor ?? 0;
  if (!Number.isSafeInteger(refunded) || !Number.isSafeInteger(reserved)
    || refunded < 0 || reserved < 0 || refunded + reserved > payment.amountMinor) return null;

  const refundAmountMinor = payment.amountMinor - refunded - reserved;
  if (refundAmountMinor <= 0) return null;

  const refund = buildAutomaticRideRefundCase({
    tripId, paymentId, payerId: passengerId, amountMinor: refundAmountMinor, createdAt: now,
  });
  const refundCases = { ...(root.refundCases ?? {}) } as Record<string, Record<string, any>>;
  const existingCase = refundCases[refund.id];
  if (existingCase) {
    // The trip, reservation and case are written atomically; an orphaned or
    // conflicting deterministic case is unsafe to adopt.
    return null;
  }

  const nextPayment = {
    ...payment,
    refundReservedAmountMinor: reserved + refund.amountMinor,
    updatedAt: now,
  };
  const nextTrip = {
    ...trip,
    status: "CANCELLED",
    paymentStatus: "REFUND_PENDING",
    paymentState: "REFUND_PENDING",
    refundCaseId: refund.id,
    cancellationReason: "NO_DRIVER_ACCEPTED",
    cancelledAt: trip.cancelledAt ?? now,
    updatedAtTimestamp: now,
  };

  const activeRideRequests = { ...(root.activeRideRequests ?? {}) } as Record<string, Record<string, unknown>>;
  if (activeRideRequests[passengerId]) {
    const passengerRequests = { ...activeRideRequests[passengerId] };
    delete passengerRequests[tripId];
    if (Object.keys(passengerRequests).length) activeRideRequests[passengerId] = passengerRequests;
    else delete activeRideRequests[passengerId];
  }

  return {
    ...root,
    trips: { ...root.trips, [tripId]: nextTrip },
    payments: { ...root.payments, [paymentId]: nextPayment },
    refundCases: { ...refundCases, [refund.id]: refund },
    activeRideRequests,
  };
}

/** Every minute, recover paid ride requests that have waited ten minutes without driver acceptance. */
export const recoverUnacceptedPaidRides = onSchedule("every 1 minutes", async () => {
  const database = getDatabase();
  const now = Date.now();
  const cutoff = now - UNACCEPTED_PAID_RIDE_TIMEOUT_MS;
  const candidates = await database.ref("trips")
    .orderByChild("status")
    .equalTo("REQUESTED")
    .get();

  if (!candidates.exists()) return;

  for (const [tripId, candidate] of Object.entries(candidates.val() as Record<string, Record<string, any>>)) {
    if (!["PAID", "PARTIALLY_REFUNDED"].includes(String(candidate.paymentStatus))
      || (typeof candidate.driverId === "string" && candidate.driverId.trim())) continue;

    const paymentId = typeof candidate.paymentId === "string" ? candidate.paymentId : "";
    if (!idPattern.test(paymentId)) continue;
    const paymentSnapshot = await database.ref(`payments/${paymentId}`).get();
    const payment = paymentSnapshot.val() as Record<string, any> | null;
    if (!payment || !Number.isSafeInteger(payment.completedAt) || payment.completedAt > cutoff) continue;

    await database.ref().transaction((root: RideRoot | null) => {
      if (root === null) return null;
      return buildUnacceptedPaidRideRecoveryRoot(root, tripId, now);
    }, undefined, false);
  }
});
