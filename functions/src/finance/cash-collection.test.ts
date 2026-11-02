import { describe, expect, it } from "vitest";
import type { CashCollection, Payment } from "../models/payment.js";
import { commitCashCollection } from "./cash-collection.js";

function fakeDatabase(seed: Record<string, any>) {
  let root = structuredClone(seed);
  return {
    database: {
      ref: () => ({
        transaction: async (update: (value: Record<string, any> | null) => Record<string, any> | undefined) => {
          const next = update(structuredClone(root));
          const committed = next !== undefined && JSON.stringify(next) !== JSON.stringify(root);
          if (committed) root = next!;
          return { committed, snapshot: { val: () => structuredClone(root) } };
        },
      }),
    } as any,
    value: () => structuredClone(root),
  };
}

const payment: Payment = {
  id: "payment_1", transactionType: "ORDER", orderId: "order_1", tripId: "",
  deliveryId: "courier_1", payerId: "customer_1", method: "CASH", status: "PENDING",
  amountMinor: 12_500, currency: "PHP", refundedAmountMinor: 0,
  createdAt: 100, updatedAt: 100, completedAt: null,
};
const collection: CashCollection = {
  id: "cash_payment_1", paymentId: "payment_1", orderId: "order_1", tripId: "",
  deliveryId: "courier_1", collectorId: "courier_1", payerId: "customer_1",
  amountMinor: 12_500, currency: "PHP", status: "COLLECTED",
  collectedAt: 200, createdAt: 200, notes: "",
};

describe("commitCashCollection", () => {
  it("writes collection evidence and payment success atomically", async () => {
    const fake = fakeDatabase({ payments: { payment_1: payment } });
    const result = await commitCashCollection(fake.database, payment, collection);
    expect(result.duplicate).toBe(false);
    expect(fake.value().payments.payment_1.status).toBe("SUCCEEDED");
    expect(fake.value().cashCollections.payment_1.status).toBe("COLLECTED");
  });

  it("treats an identical retry as a no-op", async () => {
    const fake = fakeDatabase({ payments: { payment_1: payment } });
    await commitCashCollection(fake.database, payment, collection);
    const retry = await commitCashCollection(fake.database, payment, collection);
    expect(retry.duplicate).toBe(true);
    expect(Object.keys(fake.value().cashCollections)).toHaveLength(1);
  });

  it("does not create collection evidence for a failed payment", async () => {
    const failed = { ...payment, status: "FAILED" as const };
    const fake = fakeDatabase({ payments: { payment_1: failed } });
    await expect(commitCashCollection(fake.database, payment, collection)).rejects.toThrow(/could not be committed/i);
    expect(fake.value().cashCollections).toBeUndefined();
    expect(fake.value().payments.payment_1.status).toBe("FAILED");
  });

  it("rejects collection after the courier assignment changes", async () => {
    const reassigned = { ...payment, deliveryId: "courier_2" };
    const fake = fakeDatabase({ payments: { payment_1: reassigned } });
    await expect(commitCashCollection(fake.database, payment, collection)).rejects.toThrow(/could not be committed/i);
    expect(fake.value().cashCollections).toBeUndefined();
  });

  it("rejects a collection amount that differs from the payment", async () => {
    const fake = fakeDatabase({ payments: { payment_1: payment } });
    await expect(commitCashCollection(fake.database, payment, { ...collection, amountMinor: 1 })).rejects.toThrow(/does not match/i);
  });
});
