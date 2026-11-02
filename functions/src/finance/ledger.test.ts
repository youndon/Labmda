import { describe, expect, it } from "vitest";
import {
  createFinancialTransfer,
  validateFinancialOperation,
} from "./ledger.js";
import { createPaymentReceiptOperation } from "./payment-ledger.js";
import type { Payment, PaymentAllocation } from "../models/payment.js";

describe("financial ledger invariants", () => {
  const base = {
    operationId: "payment_order_123",
    eventType: "CUSTOMER_PAYMENT" as const,
    referenceType: "PAYMENT" as const,
    referenceId: "payment_123",
    idempotencyKey: "provider:payment_123:succeeded",
    createdAt: 1_798_000_000_000,
    description: "Customer online payment received",
    amountMinor: 12_345,
    source: { accountId: "clearing_payment_provider", bucket: "CLEARING" as const, deltaMinor: 0, partyType: "PAYMENT_PROVIDER" as const, partyId: "payment_provider" },
    destination: { accountId: "platform_receivable", bucket: "PENDING" as const, deltaMinor: 0, partyType: "PLATFORM" as const, partyId: "platform" },
  };

  it("creates a balanced PHP-centavo transfer with source and destination", () => {
    const operation = createFinancialTransfer(base);
    expect(operation.currency).toBe("PHP");
    expect(operation.postings.map((posting) => posting.deltaMinor)).toEqual([-12_345, 12_345]);
    expect(() => validateFinancialOperation(operation)).not.toThrow();
  });

  it("rejects an unbalanced operation", () => {
    const operation = createFinancialTransfer(base);
    operation.postings[1]!.deltaMinor += 1;
    expect(() => validateFinancialOperation(operation)).toThrow(/unbalanced/i);
  });

  it("rejects zero, negative, fractional, and unsafe transfer amounts", () => {
    for (const amountMinor of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
      expect(() => createFinancialTransfer({ ...base, amountMinor })).toThrow();
    }
  });

  it("rejects Firebase-unsafe account identifiers", () => {
    const operation = createFinancialTransfer({
      ...base,
      source: { ...base.source, accountId: "merchant/a" },
    });
    expect(() => validateFinancialOperation(operation)).toThrow(/Firebase-safe/i);
  });

  it("rejects unsupported runtime enum values", () => {
    const operation = createFinancialTransfer(base);
    expect(() => validateFinancialOperation({
      ...operation,
      eventType: "UNKNOWN" as typeof operation.eventType,
    })).toThrow(/event type/i);
    expect(() => validateFinancialOperation({
      ...operation,
      postings: [{ ...operation.postings[0]!, bucket: "WALLET" as typeof operation.postings[0]["bucket"] }, operation.postings[1]!],
    })).toThrow(/balance bucket/i);
    expect(() => validateFinancialOperation({
      ...operation,
      postings: [{ ...operation.postings[0]!, partyType: "GATEWAY" as typeof operation.postings[0]["partyType"] }, operation.postings[1]!],
    })).toThrow(/party type/i);
  });

  it("rejects non-PHP currency and empty party references", () => {
    const operation = createFinancialTransfer(base);
    expect(() => validateFinancialOperation({ ...operation, currency: "USD" as "PHP" })).toThrow(/PHP/);
    const invalid = createFinancialTransfer({
      ...base,
      destination: { ...base.destination, partyId: "" },
    });
    expect(() => validateFinancialOperation(invalid)).toThrow(/party/i);
  });

  it("allocates a succeeded customer payment to merchant, courier, and platform wallets", () => {
    const payment: Payment = {
      id: "payment_1", transactionType: "ORDER", orderId: "order_1", tripId: "",
      deliveryId: "delivery_1", payerId: "customer_1", method: "GCASH",
      status: "SUCCEEDED", amountMinor: 1_000, currency: "PHP", refundedAmountMinor: 0,
      createdAt: 1_798_000_000_000, updatedAt: 1_798_000_000_100,
      completedAt: 1_798_000_000_100,
    };
    const allocations: PaymentAllocation[] = [
      { id: "allocation_merchant", paymentId: payment.id, transactionType: "ORDER", orderId: "order_1", tripId: "", deliveryId: "delivery_1", beneficiaryId: "merchant_1", beneficiaryType: "MERCHANT", amountMinor: 700, currency: "PHP", status: "PENDING", createdAt: payment.createdAt, updatedAt: payment.updatedAt },
      { id: "allocation_courier", paymentId: payment.id, transactionType: "ORDER", orderId: "order_1", tripId: "", deliveryId: "delivery_1", beneficiaryId: "courier_1", beneficiaryType: "DELIVERY", amountMinor: 200, currency: "PHP", status: "READY", createdAt: payment.createdAt, updatedAt: payment.updatedAt },
      { id: "allocation_platform", paymentId: payment.id, transactionType: "ORDER", orderId: "order_1", tripId: "", deliveryId: "delivery_1", beneficiaryId: "platform", beneficiaryType: "PLATFORM", amountMinor: 100, currency: "PHP", status: "PENDING", createdAt: payment.createdAt, updatedAt: payment.updatedAt },
    ];
    const operation = createPaymentReceiptOperation(payment, allocations);
    expect(operation.postings.reduce((sum, posting) => sum + posting.deltaMinor, 0)).toBe(0);
    expect(operation.postings[0]).toMatchObject({ accountId: "payment_provider_clearing", deltaMinor: -1_000, partyType: "PAYMENT_PROVIDER" });
    expect(operation.postings[1]).toMatchObject({ accountId: "wallet_merchant_merchant_1", bucket: "PENDING", deltaMinor: 700 });
    expect(operation.postings[2]).toMatchObject({ accountId: "wallet_delivery_courier_1", bucket: "AVAILABLE", deltaMinor: 200, partyType: "COURIER" });
    expect(operation.postings[3]).toMatchObject({ accountId: "wallet_platform_platform", bucket: "PENDING", deltaMinor: 100 });
    expect(() => validateFinancialOperation(operation)).not.toThrow();
  });

  it("uses cash clearing for a succeeded cash payment", () => {
    const payment: Payment = {
      id: "cash_payment_1", transactionType: "RIDE", orderId: "", tripId: "trip_1",
      deliveryId: "", payerId: "passenger_1", method: "CASH",
      status: "SUCCEEDED", amountMinor: 500, currency: "PHP", refundedAmountMinor: 0,
      createdAt: 1_798_000_000_000, updatedAt: 1_798_000_000_100,
      completedAt: 1_798_000_000_100,
    };
    const allocation: PaymentAllocation = {
      id: "ride_allocation", paymentId: payment.id, transactionType: "RIDE", orderId: "",
      tripId: "trip_1", deliveryId: "", beneficiaryId: "driver_1", beneficiaryType: "DRIVER",
      amountMinor: 500, currency: "PHP", status: "PENDING",
      createdAt: payment.createdAt, updatedAt: payment.updatedAt,
    };
    const operation = createPaymentReceiptOperation(payment, [allocation]);
    expect(operation.postings[0]).toMatchObject({ accountId: "cash_collection_clearing", partyType: "CASH", deltaMinor: -500 });
  });

  it("rejects failed payments and allocations that do not reconcile", () => {
    const payment: Payment = {
      id: "payment_bad", transactionType: "ORDER", orderId: "order_bad", tripId: "",
      deliveryId: "", payerId: "customer_1", method: "GCASH",
      status: "FAILED", amountMinor: 100, currency: "PHP", refundedAmountMinor: 0,
      createdAt: 1_798_000_000_000, updatedAt: 1_798_000_000_100, completedAt: null,
    };
    const allocation: PaymentAllocation = {
      id: "allocation_bad", paymentId: payment.id, transactionType: "ORDER", orderId: "order_bad",
      tripId: "", deliveryId: "", beneficiaryId: "merchant_1", beneficiaryType: "MERCHANT",
      amountMinor: 99, currency: "PHP", status: "PENDING",
      createdAt: payment.createdAt, updatedAt: payment.updatedAt,
    };
    expect(() => createPaymentReceiptOperation(payment, [allocation])).toThrow(/succeeded/i);
    expect(() => createPaymentReceiptOperation({ ...payment, status: "SUCCEEDED" }, [allocation])).toThrow(/exactly equal/i);
  });
});
