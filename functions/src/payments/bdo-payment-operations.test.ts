import { describe, expect, it } from "vitest";
import { isBdoCheckoutPaymentRequestUrl } from "./bdo-payment-operations.js";

describe("BDO Checkout payment request URL validation", () => {
  it("accepts HTTPS one-time payment links on the exact BDO Checkout host", () => {
    expect(isBdoCheckoutPaymentRequestUrl("https://checkout.bdo.com.ph/AbC123")).toBe(true);
  });

  it("rejects lookalike hosts and non-HTTPS links", () => {
    expect(isBdoCheckoutPaymentRequestUrl("http://checkout.bdo.com.ph/AbC123")).toBe(false);
    expect(isBdoCheckoutPaymentRequestUrl("https://checkout.bdo.com.ph.attacker.test/AbC123")).toBe(false);
    expect(isBdoCheckoutPaymentRequestUrl("https://bdo.com.ph/AbC123")).toBe(false);
    expect(isBdoCheckoutPaymentRequestUrl("https://checkout.bdo.com.ph")).toBe(false);
  });
});
