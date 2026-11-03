import { describe, expect, it } from "vitest";
import {
  DEFAULT_PAYMENT_CONFIGURATION,
  validatePaymentConfiguration,
} from "./payment-config.js";

describe("Xendit payment configuration", () => {
  it("defaults to GCash and cash across all four Labmda services", () => {
    expect(DEFAULT_PAYMENT_CONFIGURATION).toMatchObject({
      provider: "XENDIT",
      currency: "PHP",
      enabledMethods: ["GCASH", "CASH"],
      services: { FOOD: true, SHOP: true, DELIVERY: true, RIDE: true },
      checkoutMode: "API",
      payoutMode: "MANUAL",
    });
  });

  it("accepts a valid stored configuration", () => {
    expect(validatePaymentConfiguration(DEFAULT_PAYMENT_CONFIGURATION)).toEqual(DEFAULT_PAYMENT_CONFIGURATION);
  });

  it("rejects an empty or unsupported payment-method list", () => {
    expect(() => validatePaymentConfiguration({
      ...DEFAULT_PAYMENT_CONFIGURATION, enabledMethods: [],
    })).toThrow(/At least one unique supported payment method/);
    expect(() => validatePaymentConfiguration({
      ...DEFAULT_PAYMENT_CONFIGURATION, enabledMethods: ["BDO"],
    })).toThrow(/At least one unique supported payment method/);
  });

  it("rejects duplicate methods and missing service flags", () => {
    expect(() => validatePaymentConfiguration({
      ...DEFAULT_PAYMENT_CONFIGURATION, enabledMethods: ["GCASH", "GCASH"],
    })).toThrow(/unique supported payment method/);
    expect(() => validatePaymentConfiguration({
      ...DEFAULT_PAYMENT_CONFIGURATION, services: { FOOD: true },
    })).toThrow(/each have an explicit enabled state/);
  });

  it("keeps provider payouts manual until the account has explicit approval", () => {
    expect(() => validatePaymentConfiguration({
      ...DEFAULT_PAYMENT_CONFIGURATION, checkoutMode: "MANUAL_LINK",
    })).toThrow(/provider and currency must be XENDIT/);
    expect(() => validatePaymentConfiguration({
      ...DEFAULT_PAYMENT_CONFIGURATION, payoutMode: "XENDIT_API",
    })).toThrow(/Automated third-party payouts remain disabled/);
  });

  it("does not silently switch provider or currency", () => {
    expect(() => validatePaymentConfiguration({
      ...DEFAULT_PAYMENT_CONFIGURATION, provider: "BDO_CHECKOUT", checkoutMode: "MANUAL_LINK",
    })).toMatchObject({ provider: "XENDIT", checkoutMode: "API" });
    expect(() => validatePaymentConfiguration({
      ...DEFAULT_PAYMENT_CONFIGURATION, currency: "USD",
    })).toThrow(/provider and currency must be XENDIT and PHP/);
  });
});
