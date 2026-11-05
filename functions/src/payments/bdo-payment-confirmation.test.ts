import { describe, expect, it } from "vitest";
import { confirmBdoGcashPaymentRecord } from "./bdo-payment-confirmation.js";
import { providerReferenceIndexKey } from "./provider-reference-key.js";

const payment = {
  id: "pay_1", transactionType: "ORDER", orderId: "order_1", tripId: "",
  deliveryId: "courier_1", payerId: "customer_1", method: "GCASH",
  provider: "BDO_CHECKOUT", checkoutUrl: "https://checkout.bdo.com.ph/request_1",
  status: "PENDING", amountMinor: 12_500, currency: "PHP",
  refundedAmountMinor: 0, createdAt: 100, updatedAt: 100, completedAt: null,
};
const order = {
  id: "order_1", paymentId: "pay_1", status: "PENDING_PAYMENT",
  orderType: "FOOD", paymentStatus: "PENDING", amountMinor: 12_500,
};
const input = {
  paymentId: "pay_1", transferReference: "BDO-REF-1234",
  amountMinor: 12_500, verifiedBy: "admin_1",
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
    setPaymentStatus: (status: string) => { root.payments.pay_1.status = status; },
  };
}

function seed(overrides: Record<string, any> = {}) {
  return {
    payments: { pay_1: { ...payment, ...(overrides.payment ?? {}) } },
    orders: { order_1: { ...order, ...(overrides.order ?? {}) } },
    ...(overrides.root ?? {}),
  };
}

describe("confirmBdoGcashPaymentRecord", () => {
  it("confirms the exact GCash payment and advances its pending order", async () => {
    const fake = fakeDatabase(seed());
    const result = await confirmBdoGcashPaymentRecord(fake.database, input);

    expect(result).toMatchObject({ paymentId: "pay_1", status: "SUCCEEDED", duplicate: false });
    expect(fake.value().payments.pay_1.providerReference).toBe(input.transferReference);
    expect(fake.value().bdoPaymentConfirmations.pay_1.status).toBe("VERIFIED");
    const referenceKey = providerReferenceIndexKey(input.transferReference);
    expect(fake.value().bdoPaymentReferenceIndex[referenceKey]).toBe("pay_1");
    expect(fake.value().bdoPaymentReferenceIndex[input.transferReference]).toBeUndefined();
    expect(fake.value().orders.order_1).toMatchObject({ status: "PLACED", paymentStatus: "PAID" });
  });

  it("treats the same reference and amount as an idempotent retry", async () => {
    const fake = fakeDatabase(seed());
    await confirmBdoGcashPaymentRecord(fake.database, input);
    const retry = await confirmBdoGcashPaymentRecord(fake.database, input);

    expect(retry).toMatchObject({ status: "SUCCEEDED", duplicate: true });
    expect(Object.keys(fake.value().bdoPaymentConfirmations)).toHaveLength(1);
  });

  it("reports the current payment status on a retry after a later refund", async () => {
    const fake = fakeDatabase(seed());
    await confirmBdoGcashPaymentRecord(fake.database, input);
    fake.setPaymentStatus("REFUNDED");

    const retry = await confirmBdoGcashPaymentRecord(fake.database, input);
    expect(retry).toMatchObject({ status: "REFUNDED", duplicate: true });
  });

  it("rejects a BDO reference already assigned to another payment", async () => {
    const fake = fakeDatabase(seed({
      root: { bdoPaymentReferenceIndex: { [input.transferReference]: "another_payment" } },
    }));

    await expect(confirmBdoGcashPaymentRecord(fake.database, input))
      .rejects.toThrow(/could not be verified/i);
    expect(fake.value().payments.pay_1.status).toBe("PENDING");
  });

  it("rejects an amount mismatch without changing payment state", async () => {
    const fake = fakeDatabase(seed());
    await expect(confirmBdoGcashPaymentRecord(fake.database, { ...input, amountMinor: 12_499 }))
      .rejects.toThrow(/could not be verified/i);
    expect(fake.value().payments.pay_1.status).toBe("PENDING");
  });

  it("rejects a payment without an attached BDO Checkout request", async () => {
    const fake = fakeDatabase(seed({ payment: { checkoutUrl: "" } }));
    await expect(confirmBdoGcashPaymentRecord(fake.database, input))
      .rejects.toThrow(/could not be verified/i);
  });

  it("rejects a mismatched order/payment association", async () => {
    const fake = fakeDatabase(seed({ order: { paymentId: "different_payment" } }));
    await expect(confirmBdoGcashPaymentRecord(fake.database, input))
      .rejects.toThrow(/could not be verified/i);
    expect(fake.value().payments.pay_1.status).toBe("PENDING");
  });

  it("rejects a failed or already-cancelled payment", async () => {
    for (const status of ["FAILED", "CANCELLED", "EXPIRED"]) {
      const fake = fakeDatabase(seed({ payment: { status } }));
      await expect(confirmBdoGcashPaymentRecord(fake.database, input))
        .rejects.toThrow(/could not be verified/i);
    }
  });

  it("rejects a transaction total that differs from the verified payment amount", async () => {
    const fake = fakeDatabase(seed({ order: { amountMinor: 12_499 } }));
    await expect(confirmBdoGcashPaymentRecord(fake.database, input))
      .rejects.toThrow(/could not be verified/i);
    expect(fake.value().payments.pay_1.status).toBe("PENDING");
  });

  it("rejects invalid confirmation input", async () => {
    const fake = fakeDatabase(seed());
    await expect(confirmBdoGcashPaymentRecord(fake.database, { ...input, amountMinor: 0 }))
      .rejects.toThrow(/input is invalid/i);
  });
});
