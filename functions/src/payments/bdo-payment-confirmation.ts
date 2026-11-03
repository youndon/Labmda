import type { Database } from "firebase-admin/database";

export interface BdoGcashConfirmationInput {
  paymentId: string;
  transferReference: string;
  amountMinor: number;
  verifiedBy: string;
}

export interface BdoGcashConfirmationResult {
  paymentId: string;
  status: string;
  transferReference: string;
  duplicate: boolean;
}

/**
 * Atomically confirms one manually verified BDO GCash payment.
 * This is an interim admin verification bridge, not a provider webhook.
 */
export async function confirmBdoGcashPaymentRecord(
  database: Database,
  input: BdoGcashConfirmationInput,
): Promise<BdoGcashConfirmationResult> {
  const { paymentId, transferReference, amountMinor, verifiedBy } = input;
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(paymentId)
    || !/^[A-Za-z0-9_-]{4,120}$/.test(transferReference)
    || !Number.isSafeInteger(amountMinor) || amountMinor <= 0
    || !verifiedBy) {
    throw new Error("BDO payment confirmation input is invalid.");
  }

  const result = await database.ref().transaction((root: Record<string, any> | null) => {
    if (root === null) return null;
    const payment = root.payments?.[paymentId];
    if (!payment || payment.id !== paymentId || payment.method !== "GCASH"
      || payment.provider !== "BDO_CHECKOUT" || !payment.checkoutUrl
      || payment.currency !== "PHP" || payment.amountMinor !== amountMinor) return;

    const confirmations = root.bdoPaymentConfirmations ?? {};
    const referenceIndex = root.bdoPaymentReferenceIndex ?? {};
    const prior = confirmations[paymentId];
    if (prior) {
      const alreadyConfirmed = prior.transferReference === transferReference
        && prior.amountMinor === amountMinor && prior.currency === "PHP"
        && prior.status === "VERIFIED" && payment.providerReference === transferReference
        && ["SUCCEEDED", "PARTIALLY_REFUNDED", "REFUNDED"].includes(payment.status);
      if (!alreadyConfirmed || referenceIndex[transferReference] !== paymentId) return;
      return;
    }

    const indexedPaymentId = referenceIndex[transferReference];
    if (indexedPaymentId && indexedPaymentId !== paymentId) return;
    if (payment.status !== "PENDING" && payment.status !== "PROCESSING") return;

    const now = Date.now();
    const nextPayment = {
      ...payment,
      providerReference: transferReference,
      status: "SUCCEEDED",
      completedAt: now,
      updatedAt: now,
      verifiedBy,
      verifiedAt: now,
      verificationMode: "ADMIN_DASHBOARD",
    };
    const updates: Record<string, any> = {
      payments: { ...root.payments, [paymentId]: nextPayment },
      bdoPaymentConfirmations: {
        ...confirmations,
        [paymentId]: {
          paymentId, transferReference, amountMinor, currency: "PHP",
          status: "VERIFIED", verifiedBy, verifiedAt: now,
        },
      },
      bdoPaymentReferenceIndex: { ...referenceIndex, [transferReference]: paymentId },
    };

    if (payment.transactionType === "ORDER" && payment.orderId) {
      const order = root.orders?.[payment.orderId];
      if (!order || order.paymentId !== paymentId || order.status !== "PENDING_PAYMENT"
        || order.amountMinor !== payment.amountMinor) return;
      updates.orders = {
        ...root.orders,
        [payment.orderId]: {
          ...order,
          status: order.orderType === "DELIVERY" ? "OUT_FOR_DELIVERY" : "PLACED",
          paymentStatus: "PAID",
          updatedAtTimestamp: now,
        },
      };
    } else if (payment.transactionType === "RIDE" && payment.tripId) {
      const trip = root.trips?.[payment.tripId];
      if (!trip || trip.paymentId !== paymentId || trip.status !== "PENDING_PAYMENT"
        || trip.customerTotalMinor !== payment.amountMinor) return;
      updates.trips = {
        ...root.trips,
        [payment.tripId]: {
          ...trip, status: "REQUESTED", paymentStatus: "PAID",
          paymentState: "PAID", updatedAtTimestamp: now,
        },
      };
    } else {
      return;
    }
    return { ...root, ...updates };
  }, undefined, false);

  const root = result.snapshot.val() as Record<string, any> | null;
  const payment = root?.payments?.[paymentId];
  const confirmation = root?.bdoPaymentConfirmations?.[paymentId];
  if (!payment || payment.id !== paymentId || payment.method !== "GCASH"
    || payment.provider !== "BDO_CHECKOUT" || payment.currency !== "PHP"
    || payment.amountMinor !== amountMinor || !confirmation
    || confirmation.transferReference !== transferReference
    || confirmation.amountMinor !== amountMinor || confirmation.currency !== "PHP"
    || confirmation.status !== "VERIFIED") {
    throw new Error("Payment, order/trip state, amount, or BDO reference could not be verified.");
  }
  return {
    paymentId,
    status: payment.status,
    transferReference,
    duplicate: !result.committed,
  };
}
