import { describe, expect, it } from "vitest";
import { buildOrderPaymentAllocations, buildRidePaymentAllocations, buildStandaloneDeliveryAllocations, buildInitialOrderPaymentAllocations, buildInitialRidePaymentAllocations, buildInitialStandaloneDeliveryPaymentAllocations } from "./payment-allocations.js";
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
      courierId: "courier_uid", breakdown: { ...orderBreakdown, customerTotalMinor: 10_000 }, providerFeePayer: "PLATFORM", createdAt: 100,
    });
    expect(rows.reduce((sum, row) => sum + row.amountMinor, 0)).toBe(10_000);
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

describe("standalone delivery allocations", () => {
  it("splits the delivery fee between courier and platform", () => {
    const rows = buildStandaloneDeliveryAllocations({
      paymentId: "payment_delivery", orderId: "delivery_1", courierId: "courier_uid",
      deliveryFeeMinor: 1_000, courierEarningsMinor: 850, platformCommissionMinor: 150,
      providerFeeMinor: 0, providerFeePayer: "PLATFORM", customerTotalMinor: 1_000, createdAt: 100,
    });
    expect(rows.map((row) => [row.beneficiaryType, row.amountMinor])).toEqual([
      ["DELIVERY", 850], ["PLATFORM", 150],
    ]);
  });

  it("includes a customer-paid checkout fee without reducing courier earnings", () => {
    const rows = buildStandaloneDeliveryAllocations({
      paymentId: "payment_delivery_gcash", orderId: "delivery_2", courierId: "courier_uid",
      deliveryFeeMinor: 1_000, courierEarningsMinor: 850, platformCommissionMinor: 150,
      providerFeeMinor: 25, providerFeePayer: "CUSTOMER", customerTotalMinor: 1_025, createdAt: 100,
    });
    expect(rows.reduce((sum, row) => sum + row.amountMinor, 0)).toBe(1_025);
    expect(rows[1].amountMinor).toBe(175);
  });

  it("rejects a delivery split that does not balance", () => {
    expect(() => buildStandaloneDeliveryAllocations({
      paymentId: "payment_delivery", orderId: "delivery_1", courierId: "courier_uid",
      deliveryFeeMinor: 1_000, courierEarningsMinor: 850, platformCommissionMinor: 100,
      providerFeeMinor: 0, providerFeePayer: "PLATFORM", customerTotalMinor: 1_000, createdAt: 100,
    })).toThrow(/do not reconcile/i);
  });
});


describe("pre-assignment GCash allocations", () => {
  it("allocates merchant and platform shares while leaving the courier share unallocated", () => {
    const rows = buildInitialOrderPaymentAllocations({
      paymentId: "payment_initial_order", orderId: "order_1",
      merchantOwnerId: "merchant_uid", breakdown: orderBreakdown,
      providerFeePayer: "CUSTOMER", createdAt: 100,
    });
    expect(rows.map((row) => [row.beneficiaryType, row.amountMinor])).toEqual([
      ["MERCHANT", 8_640], ["PLATFORM", 760],
    ]);
    expect(rows.reduce((sum, row) => sum + row.amountMinor, 0)).toBe(9_400);
    expect(orderBreakdown.customerTotalMinor - rows.reduce((sum, row) => sum + row.amountMinor, 0))
      .toBe(orderBreakdown.courierEarningsMinor);
    expect(rows.every((row) => row.deliveryId === "")).toBe(true);
  });

  it("allocates only the platform share before a ride driver is assigned", () => {
    const rows = buildInitialRidePaymentAllocations({
      paymentId: "payment_initial_ride", tripId: "trip_1",
      fareMinor: 5_000, driverEarningsMinor: 4_000, platformCommissionMinor: 1_000,
      providerFeeMinor: 150, providerFeePayer: "CUSTOMER", customerTotalMinor: 5_150, createdAt: 100,
    });
    expect(rows).toEqual([
      expect.objectContaining({ beneficiaryType: "PLATFORM", beneficiaryId: "platform", amountMinor: 1_150 }),
    ]);
    expect(5_150 - rows.reduce((sum, row) => sum + row.amountMinor, 0)).toBe(4_000);
  });

  it("keeps a standalone delivery courier share unassigned while recording the platform share", () => {
    const rows = buildInitialStandaloneDeliveryPaymentAllocations({
      paymentId: "payment_initial_delivery", orderId: "delivery_1",
      deliveryFeeMinor: 1_000, courierEarningsMinor: 850, platformCommissionMinor: 150,
      providerFeeMinor: 25, providerFeePayer: "CUSTOMER", customerTotalMinor: 1_025, createdAt: 100,
    });
    expect(rows.map((row) => [row.beneficiaryType, row.amountMinor])).toEqual([["PLATFORM", 175]]);
    expect(1_025 - rows[0].amountMinor).toBe(850);
  });

  it("rejects an initial split that does not account for the unassigned courier amount", () => {
    expect(() => buildInitialOrderPaymentAllocations({
      paymentId: "payment_bad", orderId: "order_1", merchantOwnerId: "merchant_uid",
      breakdown: { ...orderBreakdown, customerTotalMinor: 10_000 },
      providerFeePayer: "CUSTOMER", createdAt: 100,
    })).toThrow(/do not reconcile/i);
  });
});
