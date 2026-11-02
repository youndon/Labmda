import { describe, expect, it } from "vitest";
import { isQuoteClaimable } from "./quote-guards.js";

describe("one-time pricing quote guard", () => {
  const valid = { uid: "customer_1", kind: "FOOD", status: "QUOTED", expiresAt: 2_000 };

  it("accepts a quote owned by the caller, of the expected type, before expiry", () => {
    expect(isQuoteClaimable(valid, "customer_1", "FOOD", 1_999)).toBe(true);
  });

  it("rejects quotes belonging to another account or resource type", () => {
    expect(isQuoteClaimable(valid, "customer_2", "FOOD", 1_000)).toBe(false);
    expect(isQuoteClaimable(valid, "customer_1", "SHOP", 1_000)).toBe(false);
  });

  it("rejects expired, already-consumed, and malformed quotes", () => {
    expect(isQuoteClaimable(valid, "customer_1", "FOOD", 2_000)).toBe(false);
    expect(isQuoteClaimable({ ...valid, status: "ORDER_CREATED" }, "customer_1", "FOOD", 1_000)).toBe(false);
    expect(isQuoteClaimable({ ...valid, expiresAt: "2000" }, "customer_1", "FOOD", 1_000)).toBe(false);
    expect(isQuoteClaimable(null, "customer_1", "FOOD", 1_000)).toBe(false);
  });
});
