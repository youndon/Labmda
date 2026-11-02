import type { Database } from "firebase-admin/database";
import type { CashCollection, Payment } from "../models/payment.js";
import { assertPaymentStatusTransition } from "./payment-state.js";

function sameCollectionIdentity(a: CashCollection, b: CashCollection): boolean {
  return a.id === b.id && a.paymentId === b.paymentId
    && a.orderId === b.orderId && a.tripId === b.tripId
    && a.deliveryId === b.deliveryId && a.collectorId === b.collectorId
    && a.payerId === b.payerId && a.amountMinor === b.amountMinor
    && a.currency === b.currency;
}

function samePaymentIdentity(current: Payment, expected: Payment): boolean {
  return current.id === expected.id
    && current.transactionType === expected.transactionType
    && current.orderId === expected.orderId && current.tripId === expected.tripId
    && current.deliveryId === expected.deliveryId && current.payerId === expected.payerId
    && current.method === "CASH" && current.amountMinor === expected.amountMinor
    && current.currency === "PHP";
}

/**
 * Atomically records cash evidence and moves its payment to SUCCEEDED.
 *
 * Both records are written in one RTDB root transaction because they live at
 * separate paths. This deliberately favors correctness over write throughput;
 * the financial journal already uses a separate transactional root.
 */
export async function commitCashCollection(
  database: Database,
  expectedPayment: Payment,
  collection: CashCollection,
): Promise<{ duplicate: boolean }> {
  if (expectedPayment.method !== "CASH" || collection.paymentId !== expectedPayment.id
    || collection.amountMinor !== expectedPayment.amountMinor || collection.currency !== "PHP"
    || collection.status !== "COLLECTED") {
    throw new Error("Cash collection does not match the authoritative payment.");
  }

  const result = await database.ref().transaction((raw: Record<string, any> | null) => {
    if (!raw) return;
    const current = raw.payments?.[expectedPayment.id] as Payment | undefined;
    if (!current || !samePaymentIdentity(current, expectedPayment)) return;

    // Re-check the assignment inside the transaction so a reassignment racing
    // the collector request cannot authorize the previous driver/courier.
    const order = current.transactionType === "ORDER"
      ? raw.orders?.[current.orderId] as Record<string, any> | undefined
      : undefined;
    const trip = current.transactionType === "RIDE"
      ? raw.trips?.[current.tripId] as Record<string, any> | undefined
      : undefined;
    const assigned = current.transactionType === "ORDER"
      ? current.deliveryId === collection.collectorId
        && order?.deliveryId === collection.collectorId
      : trip?.driverId === collection.collectorId;
    if (!assigned) return;

    // Cash is collected at the physical handoff/completion point, not while
    // an order is still being prepared or a ride is still in progress.
    // Check this inside the same root transaction as the payment write so a
    // stale client cannot confirm cash against an earlier service state.
    const completed = current.transactionType === "ORDER"
      ? order?.paymentId === expectedPayment.id && order.status === "DELIVERED"
      : trip?.paymentId === expectedPayment.id && trip.status === "COMPLETED";
    if (!completed) return;

    const prior = raw.cashCollections?.[expectedPayment.id] as CashCollection | undefined;
    if (prior && (!sameCollectionIdentity(prior, collection)
      || (prior.status !== "PENDING" && prior.status !== "COLLECTED"))) return;

    if (current.status === "SUCCEEDED" && prior?.status === "COLLECTED"
      && sameCollectionIdentity(prior, collection)) {
      // Abort the transaction: the already-committed collection is an idempotent retry.
      return;
    }
    if (current.status !== "PENDING") return;
    assertPaymentStatusTransition(current.status, "SUCCEEDED");

    const now = collection.collectedAt ?? Date.now();
    const savedCollection: CashCollection = {
      ...collection,
      createdAt: prior?.createdAt ?? collection.createdAt,
      collectedAt: now,
    };
    const savedPayment: Payment = {
      ...current,
      status: "SUCCEEDED",
      updatedAt: now,
      completedAt: now,
    };

    const nextRoot: Record<string, any> = {
      ...raw,
      payments: { ...(raw.payments ?? {}), [expectedPayment.id]: savedPayment },
      cashCollections: { ...(raw.cashCollections ?? {}), [expectedPayment.id]: savedCollection },
    };
    if (current.transactionType === "ORDER") {
      const order = raw.orders?.[current.orderId] as Record<string, any> | undefined;
      if (!order || order.paymentId !== expectedPayment.id) return;
      nextRoot.orders = {
        ...raw.orders,
        [current.orderId]: { ...order, paymentStatus: "PAID", updatedAtTimestamp: now },
      };
    } else {
      const trip = raw.trips?.[current.tripId] as Record<string, any> | undefined;
      if (!trip || trip.paymentId !== expectedPayment.id) return;
      nextRoot.trips = {
        ...raw.trips,
        [current.tripId]: { ...trip, paymentStatus: "PAID", paymentState: "PAID", updatedAtTimestamp: now },
      };
    }
    return nextRoot;
  }, undefined, false);

  const root = result.snapshot.val() as Record<string, any> | null;
  const savedPayment = root?.payments?.[expectedPayment.id] as Payment | undefined;
  const savedCollection = root?.cashCollections?.[expectedPayment.id] as CashCollection | undefined;
  if (!savedPayment || savedPayment.status !== "SUCCEEDED" || !savedCollection
    || savedCollection.status !== "COLLECTED" || !sameCollectionIdentity(savedCollection, collection)) {
    throw new Error("Cash collection could not be committed. Payment state or collector assignment changed; refresh and retry.");
  }
  return { duplicate: !result.committed };
}
