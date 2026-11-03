import { createHash } from "node:crypto";
import type { Database } from "firebase-admin/database";

export interface BdoPaymentExpiryInput {
  paymentId: string;
  closureReference: string;
  reason: string;
  expiredBy: string;
}

export interface BdoPaymentExpiryResult {
  paymentId: string;
  status: "EXPIRED";
  closureReference: string;
  duplicate: boolean;
}

const idPattern = /^[A-Za-z0-9_-]{1,128}$/;
const referencePattern = /^[A-Za-z0-9_-]{4,120}$/;

/**
 * Expires a BDO Checkout payment only after an administrator has checked the
 * BDO merchant portal and confirmed that the attached request is no longer
 * payable. This is not an automated timeout or a provider webhook.
 */
export async function expireBdoGcashPaymentRecord(
  database: Database,
  input: BdoPaymentExpiryInput,
): Promise<BdoPaymentExpiryResult> {
  const paymentId = input.paymentId.trim();
  const closureReference = input.closureReference.trim();
  const reason = input.reason.trim();
  if (!idPattern.test(paymentId) || !referencePattern.test(closureReference)
    || reason.length < 5 || reason.length > 500 || !input.expiredBy.trim()) {
    throw new Error("BDO payment expiry details are invalid.");
  }
  const referenceKey = createHash("sha256").update(closureReference.toUpperCase()).digest("hex");
  const now = Date.now();

  const result = await database.ref().transaction((root: Record<string, any> | null) => {
    if (root === null) return null;
    const payment = root.payments?.[paymentId] as Record<string, any> | undefined;
    const closures = root.bdoPaymentClosures ?? {};
    const referenceIndex = root.bdoPaymentClosureReferenceIndex ?? {};
    const existing = closures[paymentId];
    if (existing) {
      if (existing.closureReference === closureReference
        && existing.status === "VERIFIED_EXPIRED"
        && payment?.status === "EXPIRED") return root;
      return;
    }
    if (referenceIndex[referenceKey] && referenceIndex[referenceKey] !== paymentId) return;
    if (!payment || payment.id !== paymentId || payment.method !== "GCASH"
      || payment.provider !== "BDO_CHECKOUT" || typeof payment.checkoutUrl !== "string"
      || !payment.checkoutUrl.startsWith("https://checkout.bdo.com.ph/")
      || (payment.status !== "PENDING" && payment.status !== "PROCESSING")
      || payment.currency !== "PHP") return;

    const nowAt = now;
    const closure = {
      paymentId, closureReference, reason, status: "VERIFIED_EXPIRED",
      verifiedBy: input.expiredBy, verifiedAt: nowAt,
    };
    const nextPayment = {
      ...payment, status: "EXPIRED", updatedAt: nowAt, completedAt: nowAt,
      expiryReference: closureReference, expiryReason: reason,
      expiredBy: input.expiredBy, expiredAt: nowAt,
    };
    const nextRoot: Record<string, any> = {
      ...root,
      payments: { ...root.payments, [paymentId]: nextPayment },
      bdoPaymentClosures: { ...closures, [paymentId]: closure },
      bdoPaymentClosureReferenceIndex: { ...referenceIndex, [referenceKey]: paymentId },
    };

    if (payment.transactionType === "ORDER" && payment.orderId) {
      const order = root.orders?.[payment.orderId];
      if (!order || order.paymentId !== paymentId || order.status !== "PENDING_PAYMENT"
        || order.amountMinor !== payment.amountMinor) return;
      nextRoot.orders = {
        ...root.orders,
        [payment.orderId]: {
          ...order, status: "CANCELLED", paymentStatus: "EXPIRED",
          cancellationReason: "BDO_CHECKOUT_EXPIRED", updatedAtTimestamp: nowAt,
          cancelledAt: nowAt,
        },
      };
    } else if (payment.transactionType === "RIDE" && payment.tripId) {
      const trip = root.trips?.[payment.tripId];
      if (!trip || trip.paymentId !== paymentId || trip.status !== "PENDING_PAYMENT"
        || trip.customerTotalMinor !== payment.amountMinor) return;
      nextRoot.trips = {
        ...root.trips,
        [payment.tripId]: {
          ...trip, status: "CANCELLED", paymentStatus: "EXPIRED",
          paymentState: "EXPIRED", cancellationReason: "BDO_CHECKOUT_EXPIRED",
          updatedAtTimestamp: nowAt, cancelledAt: nowAt,
        },
      };
      const active = { ...(root.activeRideRequests ?? {}) };
      const passengerId = typeof trip.passengerId === "string" ? trip.passengerId : "";
      if (passengerId && active[passengerId]) {
        const requests = { ...active[passengerId] };
        delete requests[payment.tripId];
        if (Object.keys(requests).length) active[passengerId] = requests;
        else delete active[passengerId];
      }
      nextRoot.activeRideRequests = active;
    } else {
      return;
    }
    return nextRoot;
  }, undefined, false);

  const root = result.snapshot.val() as Record<string, any> | null;
  const payment = root?.payments?.[paymentId];
  const closure = root?.bdoPaymentClosures?.[paymentId];
  if (!payment || payment.status !== "EXPIRED" || !closure
    || closure.closureReference !== closureReference
    || closure.status !== "VERIFIED_EXPIRED") {
    throw new Error("BDO Checkout payment could not be safely expired. Verify the payment, transaction and BDO closure reference.");
  }
  return { paymentId, status: "EXPIRED", closureReference, duplicate: !result.committed };
}
