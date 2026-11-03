import { describe, expect, it } from "vitest";
import { hasActiveBdoCheckoutRequest } from "./payment-lifecycle-guards.js";

describe("hasActiveBdoCheckoutRequest", () => {
  const base = {
    method: "GCASH", provider: "BDO_CHECKOUT", status: "PENDING",
    checkoutUrl: "https://checkout.bdo.com.ph/request_1",
  };
  it("blocks cancellation while a BDO GCash request may still be payable", () => {
    expect(hasActiveBdoCheckoutRequest(base)).toBe(true);
    expect(hasActiveBdoCheckoutRequest({ ...base, status: "PROCESSING" })).toBe(true);
  });
  it("does not block cash, terminal or non-BDO payments", () => {
    expect(hasActiveBdoCheckoutRequest({ ...base, method: "CASH" })).toBe(false);
    expect(hasActiveBdoCheckoutRequest({ ...base, status: "EXPIRED" })).toBe(false);
    expect(hasActiveBdoCheckoutRequest({ ...base, provider: "OTHER" })).toBe(false);
  });
  it("rejects lookalike and non-HTTPS checkout links", () => {
    expect(hasActiveBdoCheckoutRequest({ ...base, checkoutUrl: "https://checkout.bdo.com.ph.attacker.test/x" })).toBe(false);
    expect(hasActiveBdoCheckoutRequest({ ...base, checkoutUrl: "http://checkout.bdo.com.ph/x" })).toBe(false);
  });
});
