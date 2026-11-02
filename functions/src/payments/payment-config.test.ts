import { describe, expect, it } from "vitest";
import {
  DEFAULT_PAYMENT_CONFIGURATION,
  validatePaymentConfiguration,
} from "./payment-config.js";

describe("BDO payment configuration", () => {
  it("defaults to GCash and cash across all four Labmda services", () => {
    expect(DEFAULT_PAYMENT_CONFIGURATION).toMatchObject({
      provider: "BDO_CHECKOUT",
      currency: "PHP",
      enabledMethods: ["GCASH", "CASH"],
      services: { FOOD: true, SHOP: true, DELIVERY: true, RIDE: true },
      checkoutMode: "MANUAL_LINK",
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

  it("does not allow unapproved BDO API checkout or automated payouts", () => {
    expect(() => validatePaymentConfiguration({
      ...DEFAULT_PAYMENT_CONFIGURATION, checkoutMode: "API",
    })).toThrow(/unavailable until BDO approves/);
    expect(() => validatePaymentConfiguration({
      ...DEFAULT_PAYMENT_CONFIGURATION, payoutMode: "BDO_CORPORATE_API",
    })).toThrow(/unavailable until BDO approves/);
  });

  it("does not silently switch provider or currency", () => {
    expect(() => validatePaymentConfiguration({
      ...DEFAULT_PAYMENT_CONFIGURATION, provider: "XENDIT",
    })).toThrow(/provider and currency must be BDO_CHECKOUT and PHP/);
    expect(() => validatePaymentConfiguration({
      ...DEFAULT_PAYMENT_CONFIGURATION, currency: "USD",
    })).toThrow(/provider and currency must be BDO_CHECKOUT and PHP/);
  });
});
