import { describe, expect, it } from "vitest";
import type { CashCollection, Payment, PaymentAllocation } from "../models/payment.js";
import { createPaymentReceiptOperation } from "./payment-ledger.js";

const now = 1_798_000_000_000;

function orderPayment(overrides: Partial<Payment> = {}): Payment {
  return {
    id: "payment_order", transactionType: "ORDER", orderId: "order_1", tripId: "",
    deliveryId: "courier_1", payerId: "customer_1", method: "GCASH",
    provider: "BDO_CHECKOUT", status: "SUCCEEDED", amountMinor: 1_000,
    currency: "PHP", refundedAmountMinor: 0, createdAt: now,
    updatedAt: now + 1, completedAt: now + 1, ...overrides,
  };
}

function ridePayment(overrides: Partial<Payment> = {}): Payment {
  return {
    id: "payment_ride", transactionType: "RIDE", orderId: "", tripId: "trip_1",
    deliveryId: "", payerId: "passenger_1", method: "GCASH",
    provider: "BDO_CHECKOUT", status: "SUCCEEDED", amountMinor: 1_000,
    currency: "PHP", refundedAmountMinor: 0, createdAt: now,
    updatedAt: now + 1, completedAt: now + 1, ...overrides,
  };
}

function allocation(payment: Payment, beneficiaryType: PaymentAllocation["beneficiaryType"], beneficiaryId: string): PaymentAllocation {
  return {
    id: "allocation_1", paymentId: payment.id, transactionType: payment.transactionType,
    orderId: payment.orderId, tripId: payment.tripId, deliveryId: payment.deliveryId,
    beneficiaryId, beneficiaryType, amountMinor: 1_000, currency: "PHP",
    status: "PENDING", createdAt: now, updatedAt: now + 1,
  };
}

function cashCollection(payment: Payment, collectorId: string): CashCollection {
  return {
    id: "cash_collection_1", paymentId: payment.id, orderId: payment.orderId,
    tripId: payment.tripId, deliveryId: payment.deliveryId, collectorId,
    payerId: payment.payerId, amountMinor: payment.amountMinor, currency: "PHP",
    status: "COLLECTED", collectedAt: now + 1, createdAt: now, notes: "",
  };
}

describe("payment ledger beneficiary relationships", () => {
  it("rejects a driver allocation on an order payment", () => {
    const payment = orderPayment();
    expect(() => createPaymentReceiptOperation(payment, [allocation(payment, "DRIVER", "driver_1")]))
      .toThrow(/driver allocations are valid only for ride payments/i);
  });

  it("rejects a merchant allocation on a ride payment", () => {
    const payment = ridePayment();
    expect(() => createPaymentReceiptOperation(payment, [allocation(payment, "MERCHANT", "merchant_1")]))
      .toThrow(/merchant allocations are valid only for order payments/i);
  });

  it("requires a courier allocation to match the order's assigned delivery provider", () => {
    const payment = orderPayment();
    expect(() => createPaymentReceiptOperation(payment, [allocation(payment, "DELIVERY", "courier_other")]))
      .toThrow(/match the assigned order delivery provider/i);
  });

  it("requires cash order collection to be confirmed by its assigned courier", () => {
    const payment = orderPayment({ method: "CASH", provider: "CASH" });
    const collection = cashCollection(payment, "courier_other");
    expect(() => createPaymentReceiptOperation(payment, [allocation(payment, "MERCHANT", "merchant_1")], { cashCollection: collection }))
      .toThrow(/cash collection evidence does not match/i);
  });

  it("requires cash ride driver earnings to belong to the collector", () => {
    const payment = ridePayment({ method: "CASH", provider: "CASH" });
    const collection = cashCollection(payment, "driver_other");
    expect(() => createPaymentReceiptOperation(payment, [allocation(payment, "DRIVER", "driver_1")], { cashCollection: collection }))
      .toThrow(/cash driver earnings must belong to the assigned cash collector/i);
  });

  it("accepts a matching courier allocation and cash collector", () => {
    const payment = orderPayment({ method: "CASH", provider: "CASH" });
    const allocations = [
      { ...allocation(payment, "MERCHANT", "merchant_1"), amountMinor: 700 },
      { ...allocation(payment, "DELIVERY", "courier_1"), id: "allocation_2", amountMinor: 200 },
      { ...allocation(payment, "PLATFORM", "platform"), id: "allocation_3", amountMinor: 100 },
    ];
    expect(() => createPaymentReceiptOperation(payment, allocations, {
      cashCollection: cashCollection(payment, "courier_1"),
    })).not.toThrow();
  });
});
