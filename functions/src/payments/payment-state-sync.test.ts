import { describe, expect, it } from "vitest";
import { synchronizePaymentState } from "./payment-state-sync.js";

function fakeDatabase(seed: Record<string, any>) {
  let value = seed;
  return {
    ref: () => ({
      transaction: async (update: (current: Record<string, any> | null) => Record<string, any> | undefined) => {
        const next = update(structuredClone(value));
        const committed = next !== undefined;
        if (committed) value = next!;
        return { committed, snapshot: { val: () => structuredClone(value) } };
      },
    }),
    value: () => structuredClone(value),
  } as any;
}

describe("payment state synchronization", () => {
  it("projects pending GCash status and checkout link to an order", async () => {
    const db = fakeDatabase({
      payments: { p1: { id: "p1", transactionType: "ORDER", orderId: "o1", method: "GCASH", provider: "BDO_CHECKOUT", status: "PROCESSING", checkoutUrl: "https://checkout.bdo.com.ph/request/1", updatedAt: 10 } },
      orders: { o1: { id: "o1", paymentId: "p1", status: "PENDING_PAYMENT", paymentStatus: "PENDING" } },
    });
    expect(await synchronizePaymentState(db, "p1")).toBe(true);
    expect(db.value().orders.o1.paymentStatus).toBe("PROCESSING");
    expect(db.value().orders.o1.checkoutUrl).toBe("https://checkout.bdo.com.ph/request/1");
    expect(db.value().orders.o1.status).toBe("PENDING_PAYMENT");
  });

  it("releases an order into fulfilment only after a successful payment record", async () => {
    const db = fakeDatabase({
      payments: { p1: { id: "p1", transactionType: "ORDER", orderId: "o1", method: "GCASH", provider: "BDO_CHECKOUT", status: "SUCCEEDED", updatedAt: 20 } },
      orders: { o1: { id: "o1", paymentId: "p1", orderType: "FOOD", status: "PENDING_PAYMENT", paymentStatus: "PENDING" } },
    });
    await synchronizePaymentState(db, "p1");
    expect(db.value().orders.o1.paymentStatus).toBe("PAID");
    expect(db.value().orders.o1.status).toBe("PLACED");
  });

  it("keeps cash due until the trusted collector marks the payment succeeded", async () => {
    const db = fakeDatabase({
      payments: { p1: { id: "p1", transactionType: "RIDE", tripId: "t1", method: "CASH", provider: "CASH", status: "PENDING", updatedAt: 30 } },
      trips: { t1: { id: "t1", passengerId: "passenger_1", paymentId: "p1", status: "REQUESTED", paymentStatus: "CASH_DUE", paymentState: "CASH_DUE" } },
    });
    await synchronizePaymentState(db, "p1");
    expect(db.value().trips.t1.paymentStatus).toBe("CASH_DUE");
    expect(db.value().trips.t1.paymentState).toBe("CASH_DUE");
    expect(db.value().activeRideRequests.passenger_1.t1.status).toBe("REQUESTED");
  });

  it("cancels only a transaction still waiting for payment", async () => {
    const db = fakeDatabase({
      payments: { p1: { id: "p1", transactionType: "RIDE", tripId: "t1", method: "GCASH", provider: "BDO_CHECKOUT", status: "CANCELLED", updatedAt: 40 } },
      trips: { t1: { id: "t1", passengerId: "passenger_1", paymentId: "p1", status: "PENDING_PAYMENT", paymentStatus: "PENDING" } },
      activeRideRequests: { passenger_1: { t1: { status: "REQUESTED", updatedAt: 35 } } },
    });
    await synchronizePaymentState(db, "p1");
    expect(db.value().trips.t1.paymentStatus).toBe("CANCELLED");
    expect(db.value().trips.t1.status).toBe("CANCELLED");
    expect(db.value().activeRideRequests?.passenger_1).toBeUndefined();
  });

  it("does not touch a different transaction or an unrelated payment", async () => {
    const db = fakeDatabase({
      payments: { p1: { id: "p1", transactionType: "ORDER", orderId: "o1", method: "GCASH", status: "SUCCEEDED" } },
      orders: { o1: { id: "o1", paymentId: "different", status: "PENDING_PAYMENT" } },
    });
    expect(await synchronizePaymentState(db, "p1")).toBe(false);
    expect(db.value().orders.o1.status).toBe("PENDING_PAYMENT");
  });

  it("does not reopen an order that has already moved beyond pending payment", async () => {
    const db = fakeDatabase({
      payments: { p1: { id: "p1", transactionType: "ORDER", orderId: "o1", method: "GCASH", status: "SUCCEEDED", updatedAt: 50 } },
      orders: { o1: { id: "o1", paymentId: "p1", status: "PREPARING", paymentStatus: "PENDING" } },
    });
    await synchronizePaymentState(db, "p1");
    expect(db.value().orders.o1.status).toBe("PREPARING");
    expect(db.value().orders.o1.paymentStatus).toBe("PAID");
  });
});
