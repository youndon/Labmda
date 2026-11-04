import { describe, expect, it } from "vitest";
import type { Payment } from "../models/payment.js";
import type { MoneyBreakdown } from "../pricing/engine.js";
import { buildInitialOrderPaymentAllocations, buildInitialRidePaymentAllocations } from "./payment-allocations.js";
import { createPaymentReceiptOperation } from "./payment-ledger.js";
import { buildRefundReversal } from "./refund-ledger.js";
import { buildCancelledOrderRoot, buildClaimAllocationTransfer } from "../operations/transaction-operations.js";
import { shouldPostSucceededPaymentToLedger } from "./payment-ledger-trigger.js";

const at = 1_798_000_000_100;
const breakdown: MoneyBreakdown = {
  currency: "PHP", subtotalMinor: 10_000, deliveryFeeMinor: 1_000,
  discountMinor: 1_000, merchantDiscountMinor: 400, platformDiscountMinor: 600,
  providerFeeMinor: 300, customerTotalMinor: 10_300,
  merchantCommissionMinor: 960, deliveryCommissionMinor: 100,
  platformCommissionMinor: 1_060, merchantEarningsMinor: 8_640,
  courierEarningsMinor: 900, platformNetBeforeOtherCostsMinor: 160,
};

function orderPayment(overrides: Partial<Payment> = {}): Payment {
  return {
    id: "payment_order_regression", transactionType: "ORDER", orderId: "order_regression",
    tripId: "", deliveryId: "", payerId: "customer_1", method: "GCASH",
    provider: "BDO_CHECKOUT", status: "SUCCEEDED", amountMinor: 10_300,
    currency: "PHP", refundedAmountMinor: 0, createdAt: at - 100,
    updatedAt: at, completedAt: at, ...overrides,
  };
}

function ridePayment(overrides: Partial<Payment> = {}): Payment {
  return {
    id: "payment_ride_regression", transactionType: "RIDE", orderId: "",
    tripId: "trip_regression", deliveryId: "", payerId: "passenger_1",
    method: "GCASH", provider: "BDO_CHECKOUT", status: "SUCCEEDED",
    amountMinor: 5_150, currency: "PHP", refundedAmountMinor: 0,
    createdAt: at - 100, updatedAt: at, completedAt: at, ...overrides,
  };
}

describe("payment lifecycle regression suite", () => {
  it("keeps an unclaimed paid order's courier share in CLEARING until claim", () => {
    const payment = orderPayment();
    const allocations = buildInitialOrderPaymentAllocations({
      paymentId: payment.id, orderId: payment.orderId, merchantOwnerId: "merchant_1",
      breakdown, providerFeePayer: "CUSTOMER", createdAt: at,
    });
    const receipt = createPaymentReceiptOperation(payment, allocations);
    expect(allocations.reduce((sum, row) => sum + row.amountMinor, 0)).toBe(9_400);
    expect(receipt.postings).toContainEqual(expect.objectContaining({
      accountId: "unallocated_payment_payment_order_regression", bucket: "CLEARING", deltaMinor: 900,
    }));
    expect(receipt.postings.reduce((sum, row) => sum + row.deltaMinor, 0)).toBe(0);
  });

  it("keeps an unclaimed paid ride's driver share in CLEARING until claim", () => {
    const payment = ridePayment();
    const allocations = buildInitialRidePaymentAllocations({
      paymentId: payment.id, tripId: payment.tripId, fareMinor: 5_000,
      driverEarningsMinor: 4_000, platformCommissionMinor: 1_000,
      providerFeeMinor: 150, providerFeePayer: "CUSTOMER",
      customerTotalMinor: 5_150, createdAt: at,
    });
    const receipt = createPaymentReceiptOperation(payment, allocations);
    expect(allocations.map(row => [row.beneficiaryType, row.amountMinor])).toEqual([["PLATFORM", 1_150]]);
    expect(receipt.postings).toContainEqual(expect.objectContaining({
      accountId: "unallocated_payment_payment_ride_regression", bucket: "CLEARING", deltaMinor: 4_000,
    }));
    expect(receipt.postings.reduce((sum, row) => sum + row.deltaMinor, 0)).toBe(0);
  });

  it.each([
    ["order courier", "payment_order", "alloc_payment_order_delivery", "courier_1", "DELIVERY", 900, "wallet_delivery_courier_1"],
    ["ride driver", "payment_ride", "alloc_payment_ride_driver", "driver_1", "DRIVER", 4_000, "wallet_driver_driver_1"],
  ] as const)("transfers an unclaimed share to the correct pending wallet (%s)", (_label, paymentId, allocationId, beneficiaryId, beneficiaryType, amountMinor, walletId) => {
    const transfer = buildClaimAllocationTransfer({
      paymentId, allocationId, beneficiaryId, beneficiaryType, amountMinor, createdAt: at,
    });
    expect(transfer.postings).toEqual([
      expect.objectContaining({ accountId: `unallocated_payment_${paymentId}`, bucket: "CLEARING", deltaMinor: -amountMinor }),
      expect.objectContaining({ accountId: walletId, bucket: "PENDING", deltaMinor: amountMinor }),
    ]);
    expect(transfer.postings.reduce((sum, row) => sum + row.deltaMinor, 0)).toBe(0);
    expect(buildClaimAllocationTransfer({
      paymentId, allocationId, beneficiaryId, beneficiaryType, amountMinor, createdAt: at,
    })).toEqual(transfer);
  });

  it("reserves a paid order refund without reversing the original successful receipt", () => {
    const order = { id: "order_1", clientId: "customer_1", paymentId: "payment_1", status: "PLACED", orderType: "FOOD" };
    const payment = {
      id: "payment_1", orderId: "order_1", transactionType: "ORDER", payerId: "customer_1",
      currency: "PHP", status: "SUCCEEDED", method: "GCASH", provider: "BDO_CHECKOUT",
      amountMinor: 10_000, refundedAmountMinor: 0, refundReservedAmountMinor: 0,
    };
    const receipt = { operationId: "payment_payment_1_received", immutable: true };
    const root = {
      orders: { order_1: order }, payments: { payment_1: payment }, refundCases: {},
      financialOperations: { payment_payment_1_received: receipt },
    };
    const cancelled = buildCancelledOrderRoot(root, "order_1", order, "customer_1", "CUSTOMER", at)!;
    expect(cancelled.orders.order_1).toMatchObject({ status: "CANCELLED", paymentStatus: "REFUND_PENDING" });
    expect(cancelled.payments.payment_1).toMatchObject({ status: "SUCCEEDED", refundReservedAmountMinor: 10_000 });
    expect(cancelled.financialOperations.payment_payment_1_received).toBe(receipt);
    expect(Object.values(cancelled.refundCases)).toHaveLength(1);
  });

  it("cancels pending cash orders without opening an online refund case or leaving CASH_DUE", () => {
    const order = {
      id: "cash_order", clientId: "customer_1", paymentId: "cash_payment",
      status: "PLACED", orderType: "FOOD", paymentStatus: "CASH_DUE", paymentMethod: "CASH",
    };
    const payment = {
      id: "cash_payment", orderId: "cash_order", transactionType: "ORDER", payerId: "customer_1",
      currency: "PHP", status: "PENDING", method: "CASH", provider: "CASH", amountMinor: 1_000,
    };
    const cancelled = buildCancelledOrderRoot({
      orders: { cash_order: order }, payments: { cash_payment: payment }, refundCases: {},
    }, "cash_order", order, "merchant_1", "MERCHANT", at)!;
    expect(cancelled.orders.cash_order).toMatchObject({ status: "CANCELLED", paymentStatus: "CANCELLED" });
    expect(cancelled.payments.cash_payment).toMatchObject({ status: "CANCELLED", method: "CASH", provider: "CASH" });
    expect(cancelled.refundCases).toEqual({});
  });

  it("makes duplicate success triggers ineligible after the first SUCCEEDED transition", () => {
    expect(shouldPostSucceededPaymentToLedger("PENDING", "SUCCEEDED", true)).toBe(true);
    expect(shouldPostSucceededPaymentToLedger("SUCCEEDED", "SUCCEEDED", true)).toBe(false);
    expect(shouldPostSucceededPaymentToLedger("SUCCEEDED", "SUCCEEDED", false)).toBe(false);
  });

  it("reverses partial refunds proportionally, centavo-exactly and with balanced postings", () => {
    const originalPostings = [
      { accountId: "payment_provider_clearing", bucket: "CLEARING" as const, deltaMinor: -10_001, partyType: "PAYMENT_PROVIDER" as const, partyId: "provider" },
      { accountId: "wallet_merchant_m1", bucket: "PENDING" as const, deltaMinor: 6_000, partyType: "MERCHANT" as const, partyId: "m1" },
      { accountId: "wallet_delivery_c1", bucket: "PENDING" as const, deltaMinor: 2_500, partyType: "COURIER" as const, partyId: "c1" },
      { accountId: "platform_revenue", bucket: "AVAILABLE" as const, deltaMinor: 1_501, partyType: "PLATFORM" as const, partyId: "platform" },
    ];
    const reversal = buildRefundReversal({
      refundId: "refund_regression", paymentId: "payment_regression",
      paymentAmountMinor: 10_001, refundAmountMinor: 3_333,
      createdAt: at, reason: "Regression partial refund", originalPostings,
    });
    expect(reversal.postings.map(row => row.deltaMinor)).toEqual([3_333, -2_000, -833, -500]);
    expect(reversal.postings.reduce((sum, row) => sum + row.deltaMinor, 0)).toBe(0);
  });
});
