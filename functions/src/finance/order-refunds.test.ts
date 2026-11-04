import { describe, expect, it } from "vitest";
import { buildAutomaticOrderRefundCase } from "./order-refunds.js";

describe("automatic paid-order refund cases", () => {
  const input = {
    orderId: "order_1", paymentId: "payment_1", payerId: "customer_1",
    amountMinor: 12500, reason: "Customer cancelled paid order",
    requestedBy: "customer_1", requestedByRole: "CUSTOMER" as const, createdAt: 1_798_000_000_100,
  };

  it("creates a PHP refund case with a stable cancellation identity", () => {
    const first = buildAutomaticOrderRefundCase(input);
    const retry = buildAutomaticOrderRefundCase(input);
    expect(first.id).toMatch(/^order_cancel_[a-f0-9]{32}$/);
    expect(first).toEqual(retry);
    expect(first.status).toBe("REQUESTED");
    expect(first.origin).toBe("ORDER_CANCELLATION");
    expect(first.amountMinor).toBe(12500);
  });

  it("records whether the customer, merchant or administrator initiated cancellation", () => {
    expect(buildAutomaticOrderRefundCase({ ...input, requestedByRole: "MERCHANT" }).requestedByRole).toBe("MERCHANT");
  });

  it("rejects invalid amounts and identifiers", () => {
    expect(() => buildAutomaticOrderRefundCase({ ...input, amountMinor: 0 })).toThrow(/invalid/i);
    expect(() => buildAutomaticOrderRefundCase({ ...input, orderId: "bad/id" })).toThrow(/invalid/i);
  });
});
