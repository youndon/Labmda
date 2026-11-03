import { describe, expect, it } from "vitest";
import {
  payoutTransferReferenceIndexKey,
  PayoutTransferReferenceError,
  resolvePayoutTransferReference,
} from "./payout-transfer-reference.js";

describe("resolvePayoutTransferReference", () => {
  it("creates a case-insensitive stable key for reference uniqueness", () => {
    expect(payoutTransferReferenceIndexKey(" BDO-12345 ")).toBe(payoutTransferReferenceIndexKey("bdo-12345"));
    expect(payoutTransferReferenceIndexKey("BDO-12345")).not.toBe(payoutTransferReferenceIndexKey("BDO-12346"));
  });
  it("requires a reference before an approved payout enters processing", () => {
    expect(() => resolvePayoutTransferReference("APPROVED", undefined, " "))
      .toThrowError(PayoutTransferReferenceError);
  });

  it("accepts and trims the completed transfer reference", () => {
    expect(resolvePayoutTransferReference("APPROVED", undefined, "  BDO-12345  "))
      .toBe("BDO-12345");
  });

  it("normalizes transfer references to uppercase", () => {
    expect(resolvePayoutTransferReference("APPROVED", undefined, "gcash-ab12")).toBe("GCASH-AB12");
    expect(resolvePayoutTransferReference("PAYING", "gcash-ab12", "GCASH-AB12")).toBe("GCASH-AB12");
  });

  it("keeps the reference immutable when a PAYING payout is retried", () => {
    expect(resolvePayoutTransferReference("PAYING", "GCASH-987", ""))
      .toBe("GCASH-987");
    expect(resolvePayoutTransferReference("PAYING", "GCASH-987", "GCASH-987"))
      .toBe("GCASH-987");
  });

  it("rejects attempts to replace a reference after processing starts", () => {
    expect(() => resolvePayoutTransferReference("PAYING", "BDO-12345", "BDO-OTHER"))
      .toThrowError(/cannot be changed/);
  });

  it("fails closed when an interrupted PAYING record has no reference", () => {
    expect(() => resolvePayoutTransferReference("PAYING", undefined, "BDO-12345"))
      .toThrowError(/without a saved transfer reference/);
  });

  it("rejects malformed and undersized references", () => {
    expect(() => resolvePayoutTransferReference("APPROVED", undefined, "abc")).toThrowError(/valid transfer reference/);
    expect(() => resolvePayoutTransferReference("APPROVED", undefined, "BDO 123")).toThrowError(/valid transfer reference/);
  });

  it("rejects oversized references", () => {
    expect(() => resolvePayoutTransferReference("APPROVED", undefined, "x".repeat(121)))
      .toThrowError(/too long/);
  });
});
