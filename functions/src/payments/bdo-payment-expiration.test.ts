import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { expireBdoGcashPaymentRecord } from "./bdo-payment-expiration.js";

const payment = {
  id: "pay_1", transactionType: "ORDER", orderId: "order_1", tripId: "",
  payerId: "customer_1", method: "GCASH", provider: "BDO_CHECKOUT",
  checkoutUrl: "https://checkout.bdo.com.ph/request_1", status: "PENDING",
  amountMinor: 12500, currency: "PHP", createdAt: 100, updatedAt: 100,
};
const input = {
  paymentId: "pay_1", closureReference: "BDO-CLOSE-1234",
  reason: "Confirmed expired in BDO merchant portal", expiredBy: "admin_1",
};

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

describe("expireBdoGcashPaymentRecord", () => {
  it("expires a portal-verified unpaid BDO order and closes its customer transaction", async () => {
    const fake = fakeDatabase({
      payments: { pay_1: { ...payment } },
      orders: { order_1: { id: "order_1", paymentId: "pay_1", status: "PENDING_PAYMENT", amountMinor: 12500 } },
    });
    const result = await expireBdoGcashPaymentRecord(fake.database, input);
    expect(result).toMatchObject({ paymentId: "pay_1", status: "EXPIRED", duplicate: false });
    expect(fake.value().payments.pay_1.status).toBe("EXPIRED");
    expect(fake.value().orders.order_1).toMatchObject({ status: "CANCELLED", paymentStatus: "EXPIRED" });
    expect(fake.value().bdoPaymentClosures.pay_1.status).toBe("VERIFIED_EXPIRED");
  });

  it("expires a pending ride and removes its active dispatch request", async () => {
    const fake = fakeDatabase({
      payments: { pay_1: { ...payment, transactionType: "RIDE", orderId: "", tripId: "trip_1" } },
      trips: { trip_1: { id: "trip_1", paymentId: "pay_1", passengerId: "passenger_1", status: "PENDING_PAYMENT", customerTotalMinor: 12500 } },
      activeRideRequests: { passenger_1: { trip_1: { status: "REQUESTED" } } },
    });
    await expireBdoGcashPaymentRecord(fake.database, input);
    expect(fake.value().trips.trip_1).toMatchObject({ status: "CANCELLED", paymentStatus: "EXPIRED", paymentState: "EXPIRED" });
    expect(fake.value().activeRideRequests.passenger_1).toBeUndefined();
  });

  it("is idempotent for the same closure reference", async () => {
    const fake = fakeDatabase({
      payments: { pay_1: { ...payment } },
      orders: { order_1: { id: "order_1", paymentId: "pay_1", status: "PENDING_PAYMENT", amountMinor: 12500 } },
    });
    await expireBdoGcashPaymentRecord(fake.database, input);
    expect(await expireBdoGcashPaymentRecord(fake.database, input))
      .toMatchObject({ status: "EXPIRED", duplicate: true });
  });

  it("refuses to expire a payment that is already successful", async () => {
    const fake = fakeDatabase({
      payments: { pay_1: { ...payment, status: "SUCCEEDED" } },
      orders: { order_1: { id: "order_1", paymentId: "pay_1", status: "PENDING_PAYMENT", amountMinor: 12500 } },
    });
    await expect(expireBdoGcashPaymentRecord(fake.database, input)).rejects.toThrow(/could not be safely expired/i);
    expect(fake.value().payments.pay_1.status).toBe("SUCCEEDED");
  });

  it("refuses mismatched order references and reused closure references", async () => {
    const mismatched = fakeDatabase({
      payments: { pay_1: { ...payment } },
      orders: { order_1: { id: "order_1", paymentId: "other_payment", status: "PENDING_PAYMENT", amountMinor: 12500 } },
    });
    await expect(expireBdoGcashPaymentRecord(mismatched.database, input)).rejects.toThrow(/could not be safely expired/i);
    const key = createHash("sha256").update(input.closureReference.toUpperCase()).digest("hex");
    const duplicateReference = fakeDatabase({
      payments: { pay_1: { ...payment } },
      orders: { order_1: { id: "order_1", paymentId: "pay_1", status: "PENDING_PAYMENT", amountMinor: 12500 } },
      bdoPaymentClosureReferenceIndex: { [key]: "another_payment" },
    });
    await expect(expireBdoGcashPaymentRecord(duplicateReference.database, input)).rejects.toThrow(/could not be safely expired/i);
  });

  it("rejects an order whose stored total differs from the BDO payment", async () => {
    const fake = fakeDatabase({
      payments: { pay_1: { ...payment } },
      orders: { order_1: { id: "order_1", paymentId: "pay_1", status: "PENDING_PAYMENT", amountMinor: 12499 } },
    });
    await expect(expireBdoGcashPaymentRecord(fake.database, input)).rejects.toThrow(/could not be safely expired/i);
    expect(fake.value().payments.pay_1.status).toBe("PENDING");
  });

  it("rejects malformed expiry evidence", async () => {
    const fake = fakeDatabase({ payments: { pay_1: { ...payment } }, orders: { order_1: { paymentId: "pay_1", status: "PENDING_PAYMENT", amountMinor: 12500 } } });
    await expect(expireBdoGcashPaymentRecord(fake.database, { ...input, reason: "bad" })).rejects.toThrow(/details are invalid/i);
  });
});
