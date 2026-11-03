import { describe, expect, it } from "vitest";
import { calculateDeliveryFee, calculateOrderBreakdown, calculateRideFare, decimalPesosToMinor } from "./engine.js";

const config = {
  version: 1, currency: "PHP" as const,
  ride: { baseFareMinor: 5000, perKmMinor: 1200, perMinuteMinor: 200, minimumFareMinor: 8000, platformCommissionBps: 1500, vehicleMultipliersBps: {} },
  delivery: { baseFeeMinor: 3000, perKmMinor: 1000, minimumFeeMinor: 3000, platformCommissionBps: 1000 },
  food: { commissionBps: 1200 }, shop: { commissionBps: 1000 },
  providerFees: { GCASH: { rateBps: 250, fixedFeeMinor: 1500, payer: "PLATFORM" as const } },
};

describe("PHP pricing engine", () => {
  it("converts peso decimals to integer centavos", () => {
    expect(decimalPesosToMinor("123.45", "price")).toBe(12345);
    expect(decimalPesosToMinor(12.5, "price")).toBe(1250);
  });
  it("calculates delivery proportionally to the travelled distance", () => {
    expect(calculateDeliveryFee(config.delivery, { distanceMeters: 1100, durationSeconds: 600 })).toEqual({
      feeMinor: 4100, platformCommissionMinor: 410, courierEarningsMinor: 3690,
    });
  });
  it("calculates ride fare and driver earnings", () => {
    expect(calculateRideFare({ ...config.ride, vehicleMultipliersBps: {} }, { distanceMeters: 2100, durationSeconds: 601 })).toEqual({
      fareMinor: 9720, platformCommissionMinor: 1458, driverEarningsMinor: 8262,
    });
  });
  it("applies the selected vehicle multiplier before calculating the platform split", () => {
    expect(calculateRideFare({ ...config.ride, vehicleMultipliersBps: {} }, { distanceMeters: 2100, durationSeconds: 601 }, 15000)).toEqual({
      fareMinor: 14580, platformCommissionMinor: 2187, driverEarningsMinor: 12393,
    });
  });
  it("separates merchant-funded discount, platform commission and courier earnings", () => {
    const quote = calculateOrderBreakdown({
      config, service: "FOOD", subtotalMinor: 20000,
      route: { distanceMeters: 1000, durationSeconds: 300 }, paymentMethod: "GCASH",
      discount: { active: true, kind: "FIXED", valueMinor: 2000, funder: "MERCHANT" },
    });
    expect(quote.discountMinor).toBe(2000);
    expect(quote.merchantEarningsMinor).toBe(15840);
    expect(quote.courierEarningsMinor).toBe(3600);
    expect(quote.customerTotalMinor).toBe(22000);
    expect(quote.platformNetBeforeOtherCostsMinor).toBe(510);
  });
});
