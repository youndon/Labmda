import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Database } from "firebase-admin/database";
import type { CashCollection, Payment, PaymentAllocation } from "../models/payment.js";
const { postFinancialOperationMock } = vi.hoisted(() => ({ postFinancialOperationMock: vi.fn() }));
vi.mock("./ledger.js", async (importOriginal) => ({
  ...await importOriginal<typeof import("./ledger.js")>(),
  postFinancialOperation: postFinancialOperationMock,
}));
import { postPaymentReceiptFromDatabase } from "./payment-repository.js";

function createDatabase(records: Record<string, unknown>) {
  const database = {
    ref(path: string) {
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
  return { database: database as unknown as Database };
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
  beforeEach(() => postFinancialOperationMock.mockReset());
  it("loads persisted records and posts one balanced receipt", async () => {
    postFinancialOperationMock.mockResolvedValueOnce({ operationId: "payment_payment_1_received", duplicate: false })
      .mockResolvedValueOnce({ operationId: "payment_payment_1_received", duplicate: true });
    const { database } = createDatabase({
      "payments/payment_1": payment,
      "paymentAllocations/payment_1": { allocation_1: allocation },
    });
    const result = await postPaymentReceiptFromDatabase(database, payment.id);
    expect(result).toEqual({ operationId: "payment_payment_1_received", duplicate: false });
    const operation = postFinancialOperationMock.mock.calls[0]?.[0];
    expect(operation.postings.reduce((sum: number, posting: { deltaMinor: number }) => sum + posting.deltaMinor, 0)).toBe(0);
    expect(postFinancialOperationMock).toHaveBeenCalledTimes(1);

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
