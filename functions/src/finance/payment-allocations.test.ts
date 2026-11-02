import { describe, expect, it } from "vitest";
import { buildOrderPaymentAllocations, buildRidePaymentAllocations } from "./payment-allocations.js";
import type { MoneyBreakdown } from "../pricing/engine.js";

const orderBreakdown: MoneyBreakdown = {
  currency: "PHP", subtotalMinor: 10_000, deliveryFeeMinor: 1_000,
  discountMinor: 1_000, merchantDiscountMinor: 400, platformDiscountMinor: 600,
  providerFeeMinor: 300, customerTotalMinor: 10_300,
  merchantCommissionMinor: 960, deliveryCommissionMinor: 100,
  platformCommissionMinor: 1_060, merchantEarningsMinor: 8_640,
  courierEarningsMinor: 900, platformNetBeforeOtherCostsMinor: 160,
};

describe("quote-backed payment allocations", () => {
  it("charges merchant-funded discounts to merchant earnings and platform-funded discounts to platform proceeds", () => {
    const rows = buildOrderPaymentAllocations({
      paymentId: "payment_order", orderId: "order_1", merchantOwnerId: "merchant_uid",
      courierId: "courier_uid", breakdown: orderBreakdown, providerFeePayer: "CUSTOMER", createdAt: 100,
    });
    expect(rows).toEqual([
      expect.objectContaining({ beneficiaryType: "MERCHANT", beneficiaryId: "merchant_uid", amountMinor: 8_640 }),
      expect.objectContaining({ beneficiaryType: "DELIVERY", beneficiaryId: "courier_uid", amountMinor: 900 }),
      expect.objectContaining({ beneficiaryType: "PLATFORM", amountMinor: 760 }),
    ]);
    expect(rows.reduce((sum, row) => sum + row.amountMinor, 0)).toBe(orderBreakdown.customerTotalMinor);
  });

  it("does not allocate a platform-paid provider fee as customer money", () => {
    const rows = buildOrderPaymentAllocations({
      paymentId: "payment_order_platform_fee", orderId: "order_1", merchantOwnerId: "merchant_uid",
      courierId: "courier_uid", breakdown: orderBreakdown, providerFeePayer: "PLATFORM", createdAt: 100,
    });
    expect(rows.reduce((sum, row) => sum + row.amountMinor, 0)).toBe(orderBreakdown.customerTotalMinor - 300);
  });

  it("reconciles ride driver, platform and customer-paid provider fee", () => {
    const rows = buildRidePaymentAllocations({
      paymentId: "payment_ride", tripId: "trip_1", driverId: "driver_uid",
      fareMinor: 5_000, driverEarningsMinor: 4_000, platformCommissionMinor: 1_000,
      providerFeeMinor: 150, providerFeePayer: "CUSTOMER", customerTotalMinor: 5_150, createdAt: 100,
    });
    expect(rows.map((row) => row.amountMinor)).toEqual([4_000, 1_150]);
  });

  it("rejects allocations that do not reconcile to the trusted quote", () => {
    expect(() => buildRidePaymentAllocations({
      paymentId: "payment_ride", tripId: "trip_1", driverId: "driver_uid",
      fareMinor: 5_000, driverEarningsMinor: 4_100, platformCommissionMinor: 1_000,
      providerFeeMinor: 150, providerFeePayer: "CUSTOMER", customerTotalMinor: 5_250, createdAt: 100,
    })).toThrow(/do not reconcile/i);
  });
});
