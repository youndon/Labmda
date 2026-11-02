import { describe, expect, it } from "vitest";
import type { Database } from "firebase-admin/database";
import type { CashCollection, Payment, PaymentAllocation } from "../models/payment.js";
import { postPaymentReceiptFromDatabase } from "./payment-repository.js";

function createDatabase(records: Record<string, unknown>) {
  let finance: Record<string, unknown> | null = null;
  const database = {
    ref(path: string) {
      if (path === "finance") {
        return {
          transaction: async (update: (state: unknown) => unknown) => {
            const next = update(finance);
            const committed = next !== finance;
            if (committed) finance = next as Record<string, unknown>;
            return { committed, snapshot: { val: () => finance } };
          },
        };
      }
      return {
        get: async () => {
          const value = records[path];
          return {
            exists: () => value !== undefined && value !== null,
            val: () => value ?? null,
          };
        },
      };
    },
  };
  return { database: database as unknown as Database, getFinance: () => finance };
}

const payment: Payment = {
  id: "payment_1", transactionType: "ORDER", orderId: "order_1", tripId: "",
  deliveryId: "", payerId: "customer_1", method: "GCASH", status: "SUCCEEDED",
  amountMinor: 1_000, currency: "PHP", refundedAmountMinor: 0,
  createdAt: 1_798_000_000_000, updatedAt: 1_798_000_000_100,
  completedAt: 1_798_000_000_100,
};
const allocation: PaymentAllocation = {
  id: "allocation_1", paymentId: payment.id, transactionType: "ORDER",
  orderId: "order_1", tripId: "", deliveryId: "", beneficiaryId: "merchant_1",
  beneficiaryType: "MERCHANT", amountMinor: 1_000, currency: "PHP",
  status: "PENDING", createdAt: payment.createdAt, updatedAt: payment.updatedAt,
};

describe("postPaymentReceiptFromDatabase", () => {
  it("loads persisted records and posts one balanced receipt", async () => {
    const { database, getFinance } = createDatabase({
      "payments/payment_1": payment,
      "paymentAllocations/payment_1": { allocation_1: allocation },
    });
    const result = await postPaymentReceiptFromDatabase(database, payment.id);
    expect(result).toEqual({ operationId: "payment_payment_1_received", duplicate: false });
    expect(getFinance()).toBeTruthy();
    const entries = (getFinance() as { entries: Record<string, { deltaMinor: number }> }).entries;
    expect(Object.values(entries).reduce((sum, entry) => sum + entry.deltaMinor, 0)).toBe(0);

    const duplicate = await postPaymentReceiptFromDatabase(database, payment.id);
    expect(duplicate).toEqual({ operationId: "payment_payment_1_received", duplicate: true });
  });

  it("refuses to post a cash payment without persisted collection evidence", async () => {
    const cashPayment: Payment = { ...payment, method: "CASH" };
    const { database } = createDatabase({
      "payments/payment_1": cashPayment,
      "paymentAllocations/payment_1": { allocation_1: allocation },
    });
    await expect(postPaymentReceiptFromDatabase(database, payment.id)).rejects.toThrow(/cash collection record/i);
  });

  it("rejects payment records whose ID does not match the database path", async () => {
    const { database } = createDatabase({
      "payments/payment_1": { ...payment, id: "payment_other" },
      "paymentAllocations/payment_1": { allocation_1: allocation },
    });
    await expect(postPaymentReceiptFromDatabase(database, payment.id)).rejects.toThrow(/identity/i);
  });

  it("rejects allocation records whose ID does not match the database key", async () => {
    const { database } = createDatabase({
      "payments/payment_1": payment,
      "paymentAllocations/payment_1": { allocation_1: { ...allocation, id: "allocation_other" } },
    });
    await expect(postPaymentReceiptFromDatabase(database, payment.id)).rejects.toThrow(/database key/i);
  });

  it("requires cash evidence to be stored against the matching payment", async () => {
    const cashPayment: Payment = { ...payment, method: "CASH" };
    const cashCollection: CashCollection = {
      id: "cash_1", paymentId: "payment_other", orderId: "order_1", tripId: "",
      deliveryId: "", collectorId: "courier_1", payerId: "customer_1",
      amountMinor: 1_000, currency: "PHP", status: "COLLECTED",
      collectedAt: payment.updatedAt, createdAt: payment.createdAt, notes: "",
    };
    const { database } = createDatabase({
      "payments/payment_1": cashPayment,
      "paymentAllocations/payment_1": { allocation_1: allocation },
      "cashCollections/payment_1": cashCollection,
    });
    await expect(postPaymentReceiptFromDatabase(database, payment.id)).rejects.toThrow(/identity/i);
  });
});
