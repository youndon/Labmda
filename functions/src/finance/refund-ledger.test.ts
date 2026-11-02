import { describe, expect, it } from "vitest";
import { buildRefundReversal } from "./refund-ledger.js";

const original = [
  { accountId: "payment_provider_clearing", bucket: "CLEARING" as const, deltaMinor: -10001, partyType: "PAYMENT_PROVIDER" as const, partyId: "provider" },
  { accountId: "wallet_merchant_m1", bucket: "PENDING" as const, deltaMinor: 6000, partyType: "MERCHANT" as const, partyId: "m1" },
  { accountId: "wallet_delivery_c1", bucket: "PENDING" as const, deltaMinor: 2500, partyType: "COURIER" as const, partyId: "c1" },
  { accountId: "platform_revenue", bucket: "AVAILABLE" as const, deltaMinor: 1501, partyType: "PLATFORM" as const, partyId: "platform" },
];

describe("buildRefundReversal", () => {
  it("reverses the full payment exactly", () => {
    const result = buildRefundReversal({
      refundId: "r1", paymentId: "p1", paymentAmountMinor: 10001,
      refundAmountMinor: 10001, createdAt: 1000, reason: "Full refund", originalPostings: original,
    });
    expect(result.postings.map((item) => item.deltaMinor)).toEqual([10001, -6000, -2500, -1501]);
    expect(result.postings.reduce((sum, item) => sum + item.deltaMinor, 0)).toBe(0);
  });

  it("allocates partial refunds proportionally without losing centavos", () => {
    const result = buildRefundReversal({
      refundId: "r2", paymentId: "p1", paymentAmountMinor: 10001,
      refundAmountMinor: 3333, createdAt: 1000, reason: "Partial refund", originalPostings: original,
    });
    expect(result.postings.map((item) => item.deltaMinor)).toEqual([3333, -2000, -833, -500]);
    expect(result.postings.reduce((sum, item) => sum + item.deltaMinor, 0)).toBe(0);
  });

  it("rejects an unbalanced or incomplete source journal", () => {
    expect(() => buildRefundReversal({
      refundId: "r3", paymentId: "p1", paymentAmountMinor: 10001,
      refundAmountMinor: 100, createdAt: 1000, reason: "Invalid", originalPostings: original.slice(0, 2),
    })).toThrow(/debit and credit totals/);
  });

  it("rejects refunds larger than the original payment", () => {
    expect(() => buildRefundReversal({
      refundId: "r4", paymentId: "p1", paymentAmountMinor: 100,
      refundAmountMinor: 101, createdAt: 1000, reason: "Too much", originalPostings: original,
    })).toThrow(/not exceeding the payment/);
  });
});
