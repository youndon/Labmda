import { createHash } from "node:crypto";

type Root = Record<string, any>;

export function buildCompletedRefundRoot(
  root: Root,
  refundId: string,
  actorUid: string,
  providerReference: string,
  ledgerOperationId: string,
  now: number,
): Root | null {
  const currentCase = root.refundCases?.[refundId];
  if (!currentCase || currentCase.status !== "PROCESSING") return null;
  const paymentId = currentCase.paymentId;
  const currentPayment = root.payments?.[paymentId];
  if (!currentPayment || currentPayment.refundProcessingId !== refundId) return null;
  const refunded = currentPayment.refundedAmountMinor ?? 0;
  const reserved = currentPayment.refundReservedAmountMinor ?? 0;
  if (!Number.isSafeInteger(refunded) || !Number.isSafeInteger(reserved)
    || reserved < currentCase.amountMinor
    || refunded + currentCase.amountMinor > currentPayment.amountMinor
    || (currentPayment.status !== "SUCCEEDED" && currentPayment.status !== "PARTIALLY_REFUNDED")) return null;

  const nextRefunded = refunded + currentCase.amountMinor;
  const fullyRefunded = nextRefunded === currentPayment.amountMinor;
  const nextPayment = {
    ...currentPayment,
    refundedAmountMinor: nextRefunded,
    refundReservedAmountMinor: reserved - currentCase.amountMinor,
    refundProcessingId: null,
    status: fullyRefunded ? "REFUNDED" : "PARTIALLY_REFUNDED",
    updatedAt: now,
  };
  const nextCase = {
    ...currentCase,
    status: "COMPLETED",
    providerReference,
    completedBy: actorUid,
    completedAt: now,
    updatedAt: now,
    ledgerOperationId,
    processingLeaseUntil: null,
  };
  const nextRoot: Root = {
    ...root,
    payments: { ...root.payments, [paymentId]: nextPayment },
    refundCases: { ...root.refundCases, [refundId]: nextCase },
  };

  // A partial goodwill refund does not cancel a live transaction. A full
  // refund does: close its order/trip and remove any active ride projection.
  if (fullyRefunded && currentPayment.transactionType === "ORDER" && currentPayment.orderId) {
    const order = root.orders?.[currentPayment.orderId];
    if (order?.paymentId === paymentId) {
      nextRoot.orders = {
        ...root.orders,
        [currentPayment.orderId]: {
          ...order,
          status: "CANCELLED",
          paymentStatus: "REFUNDED",
          refundCaseId,
          cancelledAt: order.cancelledAt ?? now,
          cancellationReason: "FULL_REFUND_COMPLETED",
          updatedAtTimestamp: now,
        },
      };
    }
  }

  if (fullyRefunded && currentPayment.transactionType === "RIDE" && currentPayment.tripId) {
    const trip = root.trips?.[currentPayment.tripId];
    if (trip?.paymentId === paymentId) {
      nextRoot.trips = {
        ...root.trips,
        [currentPayment.tripId]: {
          ...trip,
          status: "CANCELLED",
          paymentStatus: "REFUNDED",
          paymentState: "REFUNDED",
          refundCaseId,
          cancelledAt: trip.cancelledAt ?? now,
          cancellationReason: "FULL_REFUND_COMPLETED",
          updatedAtTimestamp: now,
        },
      };
      const activeRideRequests = { ...(root.activeRideRequests ?? {}) };
      const passengerId = typeof trip.passengerId === "string" ? trip.passengerId : "";
      if (passengerId && activeRideRequests[passengerId]) {
        const requests = { ...activeRideRequests[passengerId] };
        delete requests[currentPayment.tripId];
        if (Object.keys(requests).length) activeRideRequests[passengerId] = requests;
        else delete activeRideRequests[passengerId];
      }
      nextRoot.activeRideRequests = activeRideRequests;
    }
  }
  return nextRoot;
}

export function buildAbortRefundProcessingRoot(
  root: Root,
  refundId: string,
  actorUid: string,
  reason: string,
  now: number,
): Root | null {
  const currentCase = root.refundCases?.[refundId];
  if (!currentCase || currentCase.status !== "ABORTING") return null;
  const paymentId = currentCase.paymentId;
  const payment = root.payments?.[paymentId];
  if (!payment || payment.refundProcessingId !== refundId) return null;
  const nextPayment = { ...payment };
  delete nextPayment.refundProcessingId;
  const nextCase = { ...currentCase, status: "APPROVED", abortedAt: now, abortedBy: actorUid,
    abortReason: reason, updatedAt: now, processingLeaseUntil: null };
  for (const key of ["providerReference", "verificationNote", "verifiedAmountMinor", "verifiedBy",
    "verifiedAt", "processingAt", "processingLeaseUntil", "providerReferenceKey", "completedBy"]) delete nextCase[key];
  const referenceIndex = { ...(root.refundProviderReferenceIndex ?? {}) };
  if (currentCase.providerReference) {
    const referenceKey = currentCase.providerReferenceKey
      ?? createHash("sha256").update(String(currentCase.providerReference).trim().toUpperCase()).digest("hex");
    if (referenceIndex[referenceKey] === refundId) delete referenceIndex[referenceKey];
  }
  return {
    ...root,
    payments: { ...root.payments, [paymentId]: nextPayment },
    refundCases: { ...root.refundCases, [refundId]: nextCase },
    refundProviderReferenceIndex: referenceIndex,
  };
}
