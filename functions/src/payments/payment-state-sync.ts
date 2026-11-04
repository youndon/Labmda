import type { Database } from "firebase-admin/database";

type PaymentRecord = {
  id?: string;
  transactionType?: string;
  orderId?: string;
  tripId?: string;
  method?: string;
  provider?: string;
  status?: string;
  checkoutUrl?: string;
  updatedAt?: number;
};

/**
 * Keeps customer-visible order/trip payment fields aligned with the canonical
 * server-owned payments/{paymentId} record. The root transaction makes the
 * payment/entity association check and projection atomic.
 */
export async function synchronizePaymentState(database: Database, paymentId: string): Promise<boolean> {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(paymentId)) throw new Error("Payment ID is invalid.");

  const result = await database.ref().transaction((root: Record<string, any> | null) => {
    if (root === null) return null;
    const payment = root.payments?.[paymentId] as PaymentRecord | undefined;
    if (!payment || payment.id !== paymentId) return;

    const rawStatus = typeof payment.status === "string" ? payment.status.toUpperCase() : "";
    const method = typeof payment.method === "string" ? payment.method.toUpperCase() : "";
    const paymentStatus = rawStatus === "SUCCEEDED"
      ? "PAID"
      : method === "CASH" && rawStatus === "PENDING"
        ? "CASH_DUE"
        : rawStatus;

    const now = Number.isSafeInteger(payment.updatedAt) ? payment.updatedAt! : Date.now();
    const common = {
      paymentMethod: method,
      paymentProvider: payment.provider ?? "",
      paymentStatus,
      checkoutUrl: typeof payment.checkoutUrl === "string" ? payment.checkoutUrl : "",
      paymentUpdatedAt: now,
    };

    if (payment.transactionType === "ORDER" && typeof payment.orderId === "string" && payment.orderId) {
      const order = root.orders?.[payment.orderId] as Record<string, any> | undefined;
      if (!order || order.paymentId !== paymentId) return;
      const nextStatus = rawStatus === "SUCCEEDED" && order.status === "PENDING_PAYMENT"
        ? (order.orderType === "DELIVERY" ? "OUT_FOR_DELIVERY" : "PLACED")
        : ["CANCELLED", "FAILED", "EXPIRED"].includes(rawStatus) && order.status === "PENDING_PAYMENT"
          ? "CANCELLED"
          : method === "CASH" && rawStatus === "CANCELLED" && order.paymentStatus === "CASH_DUE"
            && ["PLACED", "ACCEPTED"].includes(String(order.status))
            ? "CANCELLED"
            : order.status;
      const refundCase = order.refundCaseId ? root.refundCases?.[order.refundCaseId] as Record<string, any> | undefined : undefined;
      const refundCaseStatus = typeof refundCase?.status === "string" ? refundCase.status : "";
      const orderPaymentStatus = order.status === "CANCELLED" && refundCaseStatus
        ? ["REQUESTED", "APPROVED", "PROCESSING"].includes(refundCaseStatus) ? "REFUND_PENDING"
          : refundCaseStatus === "REJECTED" ? "REFUND_REJECTED" : paymentStatus
        : paymentStatus;
      const orderCommon = { ...common, paymentStatus: orderPaymentStatus };
      const nextOrder = { ...order, ...orderCommon, status: nextStatus, updatedAtTimestamp: now };
      if (Object.keys(orderCommon).every((key) => order[key] === (orderCommon as Record<string, unknown>)[key])
        && order.status === nextStatus && order.updatedAtTimestamp === now) return;
      return { ...root, orders: { ...root.orders, [payment.orderId]: nextOrder } };
    }

    if (payment.transactionType === "RIDE" && typeof payment.tripId === "string" && payment.tripId) {
      const trip = root.trips?.[payment.tripId] as Record<string, any> | undefined;
      if (!trip || trip.paymentId !== paymentId) return;
      const nextStatus = rawStatus === "SUCCEEDED" && trip.status === "PENDING_PAYMENT"
        ? "REQUESTED"
        : ["CANCELLED", "FAILED", "EXPIRED"].includes(rawStatus) && trip.status === "PENDING_PAYMENT"
          ? "CANCELLED"
          : trip.status;
      const nextTrip = {
        ...trip,
        ...common,
        paymentState: paymentStatus,
        status: nextStatus,
        updatedAtTimestamp: now,
      };
      if (Object.keys(common).every((key) => trip[key] === (common as Record<string, unknown>)[key])
        && trip.paymentState === paymentStatus && trip.status === nextStatus
        && trip.updatedAtTimestamp === now) return;

      const activeRideRequests = { ...(root.activeRideRequests ?? {}) } as Record<string, Record<string, unknown>>;
      const passengerId = typeof trip.passengerId === "string" ? trip.passengerId : "";
      if (passengerId && ["REQUESTED", "ACCEPTED", "IN_PROGRESS"].includes(nextStatus)) {
        activeRideRequests[passengerId] = {
          ...(activeRideRequests[passengerId] ?? {}),
          [payment.tripId]: { status: nextStatus, updatedAt: now },
        };
      } else if (passengerId && activeRideRequests[passengerId]) {
        const passengerRequests = { ...activeRideRequests[passengerId] };
        delete passengerRequests[payment.tripId];
        if (Object.keys(passengerRequests).length) activeRideRequests[passengerId] = passengerRequests;
        else delete activeRideRequests[passengerId];
      }
      return {
        ...root,
        trips: { ...root.trips, [payment.tripId]: nextTrip },
        activeRideRequests,
      };
    }

    return;
  }, undefined, false);

  return result.committed;
}

/** Triggered by trusted payment-record writes; never accepts client-supplied status. */
