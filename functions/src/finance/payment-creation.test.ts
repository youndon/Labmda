import { describe, expect, it } from "vitest";
import type { Payment } from "../models/payment.js";
import { buildCashAllocations } from "./payment-creation.js";

const payment = (overrides: Partial<Payment> = {}): Payment => ({
  id: "pay_quote_1", transactionType: "ORDER", orderId: "order_1", tripId: "",
  deliveryId: "", payerId: "customer_1", method: "CASH", status: "PENDING",
  amountMinor: 10_000, currency: "PHP", refundedAmountMinor: 0,
  createdAt: 1_800_000_000_000, updatedAt: 1_800_000_000_000, completedAt: null,
  ...overrides,
});

describe("server cash payment allocations", () => {
  it("splits food/shop customer total into merchant, courier and platform", () => {
    const result = buildCashAllocations({
      payment: payment(), quoteKind: "FOOD",
      breakdown: { merchantEarningsMinor: 7_000, courierEarningsMinor: 2_000 },
      merchantId: "merchant_uid", now: 1_800_000_000_001,
    });
    expect(result.map(({ beneficiaryType, beneficiaryId, amountMinor }) => [beneficiaryType, beneficiaryId, amountMinor])).toEqual([
      ["MERCHANT", "merchant_uid", 7_000],
      ["DELIVERY", "UNASSIGNED", 2_000],
      ["PLATFORM", "platform", 1_000],
    ]);
    expect(result.reduce((sum, item) => sum + item.amountMinor, 0)).toBe(payment().amountMinor);
  });

  it("allocates standalone delivery to courier and platform", () => {
    const result = buildCashAllocations({
      payment: payment({ transactionType: "RIDE", orderId: "", tripId: "trip_1" }),
      quoteKind: "DELIVERY", breakdown: { courierEarningsMinor: 9_000 },
      collectorId: "courier_uid", now: 1_800_000_000_001,
    });
    expect(result.map(({ beneficiaryType, beneficiaryId, amountMinor }) => [beneficiaryType, beneficiaryId, amountMinor])).toEqual([
      ["DELIVERY", "courier_uid", 9_000], ["PLATFORM", "platform", 1_000],
    ]);
  });

  it("allocates rides to driver and platform", () => {
    const result = buildCashAllocations({
      payment: payment({ transactionType: "RIDE", orderId: "", tripId: "trip_1" }),
      quoteKind: "RIDE", breakdown: { driverEarningsMinor: 8_000 },
      collectorId: "driver_uid", now: 1_800_000_000_001,
    });
    expect(result.map(({ beneficiaryType, beneficiaryId, amountMinor }) => [beneficiaryType, beneficiaryId, amountMinor])).toEqual([
      ["DRIVER", "driver_uid", 8_000], ["PLATFORM", "platform", 2_000],
    ]);
  });

  it("rejects platform-funded discounts that exceed the commission remaining in a cash payment", () => {
    expect(() => buildCashAllocations({
      payment: payment(), quoteKind: "SHOP",
      breakdown: { merchantEarningsMinor: 9_500, courierEarningsMinor: 2_000 },
      merchantId: "merchant_uid", now: 1_800_000_000_001,
    })).toThrow(/discounts exceed/);
  });

  it("requires a real merchant owner UID before creating merchant allocations", () => {
    expect(() => buildCashAllocations({
      payment: payment(), quoteKind: "FOOD",
      breakdown: { merchantEarningsMinor: 7_000, courierEarningsMinor: 2_000 },
      now: 1_800_000_000_001,
    })).toThrow(/Merchant owner UID/);
  });
});
