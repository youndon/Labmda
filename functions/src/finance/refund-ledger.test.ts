import { describe, expect, it } from "vitest";
import { buildRefundReversal } from "./refund-ledger.js";

const original = [
  { accountId: "payment_provider_clearing", bucket: "CLEARING" as const, deltaMinor: -10001, partyType: "PAYMENT_PROVIDER" as const, partyId: "provider" },
  { accountId: "wallet_merchant_m1", bucket: "PENDING" as const, deltaMinor: 6000, partyType: "MERCHANT" as const, partyId: "m1" },
  { accountId: "wallet_delivery_c1", bucket: "PENDING" as const, deltaMinor: 2500, partyType: "COURIER" as const, partyId: "c1" },
  { accountId: "platform_revenue", bucket: "AVAILABLE" as const, deltaMinor: 1501, partyType: "PLATFORM" as const, partyId: "platform" },
];
const balances = {
  wallet_merchant_m1: { pendingMinor: 6000, availableMinor: 0 },
  wallet_delivery_c1: { pendingMinor: 2500, availableMinor: 0 },
  platform_revenue: { pendingMinor: 0, availableMinor: 1501 },
};
const build = (refundId: string, refundAmountMinor: number, currentBalances = balances) =>
  buildRefundReversal({ refundId, paymentId: "p1", paymentAmountMinor: 10001,
    refundAmountMinor, createdAt: 1000, reason: "Refund", originalPostings: original, currentBalances });

describe("buildRefundReversal", () => {
  it("reverses the full payment exactly from current pending buckets", () => {
    const result = build("r1", 10001);
    expect(result.postings.map((item) => item.deltaMinor)).toEqual([10001, -6000, -2500, -1501]);
    expect(result.postings.reduce((sum, item) => sum + item.deltaMinor, 0)).toBe(0);
  });

  it("debits AVAILABLE after the allocation has been released", () => {
    const released = {
      wallet_merchant_m1: { pendingMinor: 0, availableMinor: 6000 },
      wallet_delivery_c1: { pendingMinor: 0, availableMinor: 2500 },
      platform_revenue: { pendingMinor: 0, availableMinor: 1501 },
    };
    const result = build("r2", 10001, released);
    expect(result.postings.filter((p) => p.accountId.startsWith("wallet_")).every((p) => p.bucket === "AVAILABLE")).toBe(true);
    expect(result.postings.filter((p) => p.accountId.startsWith("wallet_")).map((p) => p.deltaMinor)).toEqual([-6000, -2500]);
  });

  it("records a clawback when a provider has already withdrawn refunded funds", () => {
    const spent = {
      wallet_merchant_m1: { pendingMinor: 0, availableMinor: 1000 },
      wallet_delivery_c1: { pendingMinor: 0, availableMinor: 0 },
      platform_revenue: { pendingMinor: 0, availableMinor: 1501 },
    };
    const result = build("r3", 6000, spent);
    const merchant = result.postings.filter((p) => p.accountId === "wallet_merchant_m1");
    expect(merchant).toEqual([
      { ...original[1], bucket: "AVAILABLE", deltaMinor: -1000 },
      { ...original[1], bucket: "AVAILABLE", deltaMinor: -2600 },
    ]);
    expect(result.postings.reduce((sum, item) => sum + item.deltaMinor, 0)).toBe(0);
  });

  it("allocates partial refunds proportionally without losing centavos", () => {
    const result = build("r4", 3333);
    expect(result.postings.reduce((sum, item) => sum + item.deltaMinor, 0)).toBe(0);
    expect(result.postings.find((item) => item.accountId === "payment_provider_clearing")?.deltaMinor).toBe(3333);
  });

  it("rejects an unbalanced or incomplete source journal", () => {
    expect(() => buildRefundReversal({ refundId: "r5", paymentId: "p1", paymentAmountMinor: 10001,
      refundAmountMinor: 100, createdAt: 1000, reason: "Invalid", originalPostings: original.slice(0, 2),
      currentBalances: balances })).toThrow(/debit and credit totals/);
  });

  it("rejects refunds larger than the original payment", () => {
    expect(() => build("r6", 10002)).toThrow(/not exceeding the payment/);
  });
});
