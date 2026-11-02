import { describe, expect, it } from "vitest";
import type { Database } from "firebase-admin/database";
import {
  createFinancialTransfer,
  postFinancialOperation,
  validateFinancialOperation,
} from "./ledger.js";
import { createCashRemittanceOperation, createPaymentReceiptOperation } from "./payment-ledger.js";
import type { CashCollection, Payment, PaymentAllocation } from "../models/payment.js";

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

  it("tracks a cash collector's platform remittance obligation", () => {
    const payment: Payment = {
      id: "cash_payment_1", transactionType: "RIDE", orderId: "", tripId: "trip_1",
      deliveryId: "", payerId: "passenger_1", method: "CASH",
      status: "SUCCEEDED", amountMinor: 500, currency: "PHP", refundedAmountMinor: 0,
      createdAt: 1_798_000_000_000, updatedAt: 1_798_000_000_100,
      completedAt: 1_798_000_000_100,
    };
    const allocations: PaymentAllocation[] = [
      { id: "ride_driver", paymentId: payment.id, transactionType: "RIDE", orderId: "", tripId: "trip_1", deliveryId: "", beneficiaryId: "driver_1", beneficiaryType: "DRIVER", amountMinor: 400, currency: "PHP", status: "PENDING", createdAt: payment.createdAt, updatedAt: payment.updatedAt },
      { id: "ride_platform", paymentId: payment.id, transactionType: "RIDE", orderId: "", tripId: "trip_1", deliveryId: "", beneficiaryId: "platform", beneficiaryType: "PLATFORM", amountMinor: 100, currency: "PHP", status: "PENDING", createdAt: payment.createdAt, updatedAt: payment.updatedAt },
    ];
    const cashCollection: CashCollection = {
      id: "cash_collection_ride_1", paymentId: payment.id, orderId: "", tripId: "trip_1",
      deliveryId: "", collectorId: "driver_1", payerId: payment.payerId, amountMinor: 500,
      currency: "PHP", status: "COLLECTED", collectedAt: payment.updatedAt,
      createdAt: payment.createdAt, notes: "",
    };
    const operation = createPaymentReceiptOperation(payment, allocations, { cashCollection });
    expect(operation.postings[0]).toMatchObject({ accountId: "cash_collection_clearing", partyType: "CASH", deltaMinor: -500 });
    expect(operation.postings[1]).toMatchObject({ accountId: "cash_retained_driver_1", bucket: "AVAILABLE", deltaMinor: 400, partyType: "DRIVER" });
    expect(operation.postings[2]).toMatchObject({ accountId: "cash_owed_driver_1", bucket: "CASH_OWED", partyType: "CASH", partyId: "driver_1", deltaMinor: 100 });
    expect(() => validateFinancialOperation(operation)).not.toThrow();
    expect(() => createPaymentReceiptOperation(payment, allocations)).toThrow(/confirmed cash collection/i);

    const remittance = createCashRemittanceOperation({
      cashCollectionId: "cash_collection_1",
      collectorId: "driver_1",
      amountMinor: 100,
      createdAt: payment.updatedAt + 100,
    });
    expect(remittance.postings).toMatchObject([
      { accountId: "cash_owed_driver_1", bucket: "CASH_OWED", deltaMinor: -100 },
      { accountId: "platform_revenue", bucket: "AVAILABLE", deltaMinor: 100 },
    ]);
    expect(() => validateFinancialOperation(remittance)).not.toThrow();
  });

  it("holds merchant cash in the assigned collector account until merchant settlement", () => {
    const payment: Payment = {
      id: "cash_order_1", transactionType: "ORDER", orderId: "order_cash_1", tripId: "",
      deliveryId: "courier_1", payerId: "customer_1", method: "CASH",
      status: "SUCCEEDED", amountMinor: 1_000, currency: "PHP", refundedAmountMinor: 0,
      createdAt: 1_798_000_000_000, updatedAt: 1_798_000_000_100,
      completedAt: 1_798_000_000_100,
    };
    const allocations: PaymentAllocation[] = [
      { id: "cash_merchant", paymentId: payment.id, transactionType: "ORDER", orderId: payment.orderId, tripId: "", deliveryId: payment.deliveryId, beneficiaryId: "merchant_1", beneficiaryType: "MERCHANT", amountMinor: 700, currency: "PHP", status: "PENDING", createdAt: payment.createdAt, updatedAt: payment.updatedAt },
      { id: "cash_courier", paymentId: payment.id, transactionType: "ORDER", orderId: payment.orderId, tripId: "", deliveryId: payment.deliveryId, beneficiaryId: "courier_1", beneficiaryType: "DELIVERY", amountMinor: 200, currency: "PHP", status: "READY", createdAt: payment.createdAt, updatedAt: payment.updatedAt },
      { id: "cash_platform", paymentId: payment.id, transactionType: "ORDER", orderId: payment.orderId, tripId: "", deliveryId: payment.deliveryId, beneficiaryId: "platform", beneficiaryType: "PLATFORM", amountMinor: 100, currency: "PHP", status: "PENDING", createdAt: payment.createdAt, updatedAt: payment.updatedAt },
    ];
    const cashCollection: CashCollection = {
      id: "cash_cash_order_1", paymentId: payment.id, orderId: payment.orderId, tripId: "",
      deliveryId: payment.deliveryId, collectorId: "courier_1", payerId: payment.payerId,
      amountMinor: 1_000, currency: "PHP", status: "COLLECTED",
      collectedAt: payment.updatedAt, createdAt: payment.createdAt, notes: "",
    };
    const operation = createPaymentReceiptOperation(payment, allocations, { cashCollection });
    expect(operation.postings).toContainEqual(expect.objectContaining({
      accountId: "cash_owed_courier_1_merchant_merchant_1", bucket: "CASH_OWED",
      deltaMinor: 700, partyType: "CASH", partyId: "courier_1",
    }));
    expect(operation.postings).toContainEqual(expect.objectContaining({
      accountId: "cash_owed_courier_1", bucket: "CASH_OWED", deltaMinor: 100,
    }));
    expect(operation.postings).toContainEqual(expect.objectContaining({
      accountId: "cash_retained_courier_1", bucket: "AVAILABLE", deltaMinor: 200, partyType: "COURIER",
    }));
    expect(operation.postings.reduce((sum, posting) => sum + posting.deltaMinor, 0)).toBe(0);
    expect(() => validateFinancialOperation(operation)).not.toThrow();
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
      amountMinor: 101, currency: "PHP", status: "PENDING",
      createdAt: payment.createdAt, updatedAt: payment.updatedAt,
    };
    expect(() => createPaymentReceiptOperation(payment, [allocation])).toThrow(/succeeded/i);
    expect(() => createPaymentReceiptOperation({ ...payment, status: "SUCCEEDED" }, [allocation])).toThrow(/cannot exceed/i);
  });

  it("rejects a cash remittance larger than the collector's recorded cash owed", async () => {
    const remittance = createCashRemittanceOperation({
      cashCollectionId: "cash_collection_over",
      collectorId: "driver_1",
      amountMinor: 101,
      createdAt: 1_798_000_000_200,
    });
    const database = {
      ref: () => ({
        transaction: async (update: (state: unknown) => unknown) => update({
          accounts: {
            cash_owed_driver_1: {
              availableMinor: 0, pendingMinor: 0, cashOwedMinor: 100,
              clearingMinor: 0, updatedAt: 1_798_000_000_100,
            },
          },
        }),
      }),
    } as unknown as Database;
    await expect(postFinancialOperation(database, remittance)).rejects.toThrow(/exceeds the collector cash-owed balance/i);
  });

  it("maintains a private user-scoped activity projection for provider wallets", async () => {
    const operation = createFinancialTransfer({
      ...base,
      operationId: "merchant_earning_projection_1",
      eventType: "MERCHANT_EARNING",
      referenceType: "ORDER",
      referenceId: "order_projection_1",
      idempotencyKey: "merchant_earning_projection_1",
      amountMinor: 4_250,
      source: { accountId: "platform_escrow", bucket: "CLEARING", deltaMinor: 0, partyType: "PLATFORM", partyId: "platform" },
      destination: { accountId: "wallet_merchant_owner_1", bucket: "AVAILABLE", deltaMinor: 0, partyType: "MERCHANT", partyId: "owner_1" },
    });
    let committedState: unknown = {};
    const database = {
      ref: () => ({
        transaction: async (update: (state: unknown) => unknown) => {
          committedState = update(committedState);
          return { committed: true, snapshot: { val: () => committedState } };
        },
      }),
    } as unknown as Database;
    await postFinancialOperation(database, operation);
    const state = committedState as {
      providerActivity: Record<string, Record<string, { postings: Array<{ partyType: string; partyId: string }> }>>;
    };
    expect(state.providerActivity.owner_1.merchant_earning_projection_1.postings).toEqual([
      expect.objectContaining({ partyType: "MERCHANT", partyId: "owner_1" }),
    ]);
    expect(state.providerActivity.platform).toBeUndefined();
  });

  it("rejects payout reservations that exceed the provider available balance", async () => {
    const operation = createFinancialTransfer({
      ...base,
      operationId: "payout_request_1",
      eventType: "PAYOUT",
      referenceType: "PAYOUT",
      referenceId: "payout_1",
      idempotencyKey: "payout:payout_1:reserve",
      amountMinor: 10_001,
      source: { accountId: "wallet_driver_driver_1", bucket: "AVAILABLE", deltaMinor: 0, partyType: "DRIVER", partyId: "driver_1" },
      destination: { accountId: "wallet_driver_driver_1", bucket: "CLEARING", deltaMinor: 0, partyType: "DRIVER", partyId: "driver_1" },
    });
    const database = {
      ref: () => ({
        transaction: async (update: (state: unknown) => unknown) => {
          const next = update({
            accounts: {
              wallet_driver_driver_1: {
                availableMinor: 10_000, pendingMinor: 0, cashOwedMinor: 0,
                clearingMinor: 0, updatedAt: 1_798_000_000_100,
              },
            },
          });
          return { committed: true, snapshot: { val: () => next } };
        },
      }),
    } as unknown as Database;
    await expect(postFinancialOperation(database, operation)).rejects.toThrow(/available balance/i);
  });

  it("keeps unallocated payment remainder in a clearing account", () => {
    const payment: Payment = {
      id: "payment_remainder", transactionType: "ORDER", orderId: "order_remainder", tripId: "",
      deliveryId: "", payerId: "customer_1", method: "GCASH",
      status: "SUCCEEDED", amountMinor: 1_000, currency: "PHP", refundedAmountMinor: 0,
      createdAt: 1_798_000_000_000, updatedAt: 1_798_000_000_100,
      completedAt: 1_798_000_000_100,
    };
    const allocation: PaymentAllocation = {
      id: "allocation_remainder", paymentId: payment.id, transactionType: "ORDER",
      orderId: "order_remainder", tripId: "", deliveryId: "", beneficiaryId: "merchant_1",
      beneficiaryType: "MERCHANT", amountMinor: 900, currency: "PHP", status: "PENDING",
      createdAt: payment.createdAt, updatedAt: payment.updatedAt,
    };
    const operation = createPaymentReceiptOperation(payment, [allocation]);
    expect(operation.postings.at(-1)).toMatchObject({
      accountId: "unallocated_payment_payment_remainder",
      bucket: "CLEARING",
      deltaMinor: 100,
      partyType: "PLATFORM",
    });
    expect(operation.postings.reduce((sum, posting) => sum + posting.deltaMinor, 0)).toBe(0);
  });

});
