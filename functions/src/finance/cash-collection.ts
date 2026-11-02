import type { Database } from "firebase-admin/database";
import type { CashCollection, Payment } from "../models/payment.js";
import { assertPaymentStatusTransition } from "./payment-state.js";

function sameCollection(a: CashCollection, b: CashCollection): boolean {
  return a.id === b.id && a.paymentId === b.paymentId
    && a.orderId === b.orderId && a.tripId === b.tripId
    && a.deliveryId === b.deliveryId && a.collectorId === b.collectorId
    && a.payerId === b.payerId && a.amountMinor === b.amountMinor
    && a.currency === b.currency && a.status === "COLLECTED";
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
    const assigned = current.transactionType === "ORDER"
      ? current.deliveryId === collection.collectorId
      : raw.trips?.[current.tripId]?.driverId === collection.collectorId;
    if (!assigned) return;

    const prior = raw.cashCollections?.[expectedPayment.id] as CashCollection | undefined;
    if (prior && !sameCollection(prior, collection)) return;

    if (current.status === "SUCCEEDED" && prior && sameCollection(prior, collection)) {
      return raw;
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

    return {
      ...raw,
      payments: { ...(raw.payments ?? {}), [expectedPayment.id]: savedPayment },
      cashCollections: { ...(raw.cashCollections ?? {}), [expectedPayment.id]: savedCollection },
    };
  }, undefined, false);

  const root = result.snapshot.val() as Record<string, any> | null;
  const savedPayment = root?.payments?.[expectedPayment.id] as Payment | undefined;
  const savedCollection = root?.cashCollections?.[expectedPayment.id] as CashCollection | undefined;
  if (!savedPayment || savedPayment.status !== "SUCCEEDED" || !savedCollection
    || !sameCollection(savedCollection, collection)) {
    throw new Error("Cash collection could not be committed. Payment state or collector assignment changed; refresh and retry.");
  }
  return { duplicate: !result.committed };
}
