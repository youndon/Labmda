import { describe, expect, it } from "vitest";
import { assertQuotePickupMatches, requireMerchantPickup } from "./merchant-location.js";

describe("merchant pickup location validation", () => {
  it("accepts a valid persisted merchant location", () => {
    expect(requireMerchantPickup(14.5995, 120.9842)).toEqual({ latitude: 14.5995, longitude: 120.9842 });
  });

  it.each([
    [undefined, 120.9842],
    [14.5995, null],
    [91, 120.9842],
    [14.5995, 181],
    [Number.NaN, 120.9842],
  ])("rejects missing or invalid merchant coordinates (%s, %s)", (latitude, longitude) => {
    expect(() => requireMerchantPickup(latitude, longitude)).toThrow();
  });

  it("rejects a quote when the merchant's saved pickup point changes", () => {
    const current = requireMerchantPickup(14.5995, 120.9842);
    expect(() => assertQuotePickupMatches({ latitude: 14.6, longitude: 120.9842 }, current)).toThrow();
  });

  it("accepts a quote only when its pickup point still matches the merchant", () => {
    const current = requireMerchantPickup(14.5995, 120.9842);
    expect(() => assertQuotePickupMatches({ ...current }, current)).not.toThrow();
  });
});
