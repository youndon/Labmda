import { describe, expect, it } from "vitest";
import type { CashCollection, Payment } from "../models/payment.js";
import { commitCashCollection } from "./cash-collection.js";
import { createRtdbEmulatorContext } from "../testing/rtdb-emulator.js";

const emulatorOnly = !!process.env.FIREBASE_DATABASE_EMULATOR_HOST;

describe.skipIf(!emulatorOnly)("RTDB emulator: cash collection null-cache retry", () => {
  it("retries a null root read and atomically commits collection and payment", async () => {
    const payment: Payment = {
      id: "payment_emulator", transactionType: "ORDER", orderId: "order_emulator", tripId: "",
      deliveryId: "courier_1", payerId: "customer_1", method: "CASH", status: "PENDING",
      amountMinor: 12_500, currency: "PHP", refundedAmountMinor: 0,
      createdAt: 100, updatedAt: 100, completedAt: null,
    };
    const collection: CashCollection = {
      id: "cash_payment_emulator", paymentId: payment.id, orderId: payment.orderId, tripId: "",
      deliveryId: "courier_1", collectorId: "courier_1", payerId: "customer_1",
      amountMinor: 12_500, currency: "PHP", status: "COLLECTED",
      collectedAt: 200, createdAt: 200, notes: "",
    };
    const { database, observed, cleanup } = await createRtdbEmulatorContext({
      payments: { [payment.id]: payment },
      orders: {
        [payment.orderId]: {
          id: payment.orderId, paymentId: payment.id, deliveryId: "courier_1",
          status: "DELIVERED", paymentStatus: "CASH_DUE",
        },
      },
    });
    try {
      const result = await commitCashCollection(database, payment, collection);
      expect(observed[0]).toBeNull();
      expect(observed.some((value: any) => value?.payments?.[payment.id]?.status === "PENDING")).toBe(true);
      expect(result.duplicate).toBe(false);
      const saved = await database.ref().get();
      expect(saved.child(`payments/${payment.id}/status`).val()).toBe("SUCCEEDED");
      expect(saved.child(`cashCollections/${payment.id}/status`).val()).toBe("COLLECTED");
      expect(saved.child(`orders/${payment.orderId}/paymentStatus`).val()).toBe("PAID");
    } finally {
      await cleanup();
    }
  });
});
