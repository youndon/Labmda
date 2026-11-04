import { describe, expect, it } from "vitest";
import { buildCancelledOrderRoot, buildClaimAllocationTransfer, buildDeliveryReassignmentTransfer, isAllowedOrderTransition, isAllowedTripTransition } from "./transaction-operations.js";

describe("paid order cancellation and refund reservation", () => {
  const order = {
    id: "order_1", clientId: "customer_1", paymentId: "payment_1",
    status: "PLACED", orderType: "FOOD",
  };
  const payment = {
    id: "payment_1", orderId: "order_1", transactionType: "ORDER",
    payerId: "customer_1", currency: "PHP", status: "SUCCEEDED",
    method: "GCASH", provider: "BDO_CHECKOUT", amountMinor: 10000,
    refundedAmountMinor: 0, refundReservedAmountMinor: 0,
  };

  it("creates and reserves a deterministic refund case without changing the succeeded payment or receipt", () => {
    const originalReceipt = { operationId: "payment_payment_1_received", immutable: true };
    const root = { orders: { order_1: order }, payments: { payment_1: payment },
      refundCases: {}, financialOperations: { payment_payment_1_received: originalReceipt } };
    const result = buildCancelledOrderRoot(root, "order_1", order, "customer_1", "CUSTOMER", 1_798_000_000_100);
    expect(result?.orders.order_1).toMatchObject({ status: "CANCELLED", paymentStatus: "REFUND_PENDING" });
    expect(result?.payments.payment_1).toMatchObject({ status: "SUCCEEDED", refundReservedAmountMinor: 10000 });
    const refund = Object.values(result?.refundCases ?? {})[0] as Record<string, any>;
    expect(refund).toMatchObject({
      paymentId: "payment_1", orderId: "order_1", payerId: "customer_1",
      amountMinor: 10000, currency: "PHP", status: "REQUESTED", origin: "ORDER_CANCELLATION",
    });
    expect(result?.financialOperations.payment_payment_1_received).toBe(originalReceipt);
  });

  it("reserves only the remaining amount after previous refunds and reservations", () => {
    const partialPayment = { ...payment, status: "PARTIALLY_REFUNDED", amountMinor: 10000,
      refundedAmountMinor: 2000, refundReservedAmountMinor: 1000 };
    const root = { orders: { order_1: order }, payments: { payment_1: partialPayment }, refundCases: {} };
    const result = buildCancelledOrderRoot(root, "order_1", order, "merchant_1", "MERCHANT", 1_798_000_000_100);
    expect(result?.payments.payment_1.refundReservedAmountMinor).toBe(8000);
    const refund = Object.values(result?.refundCases ?? {})[0] as Record<string, any>;
    expect(refund.amountMinor).toBe(7000);
    expect(refund.requestedByRole).toBe("MERCHANT");
  });

  it("is idempotent when the same cancelled order is processed again", () => {
    const root = { orders: { order_1: order }, payments: { payment_1: payment }, refundCases: {} };
    const first = buildCancelledOrderRoot(root, "order_1", order, "customer_1", "CUSTOMER", 1_798_000_000_100)!;
    const firstOrder = first.orders.order_1;
    const second = buildCancelledOrderRoot(first, "order_1", firstOrder, "customer_1", "CUSTOMER", 1_798_000_000_200)!;
    expect(second.payments.payment_1.refundReservedAmountMinor).toBe(10000);
    expect(Object.keys(second.refundCases)).toHaveLength(1);
    expect(second.orders.order_1.refundCaseId).toBe(firstOrder.refundCaseId);
  });

  it("does not send cash refunds through the BDO GCash workflow", () => {
    const cashPayment = { ...payment, method: "CASH", provider: "CASH" };
    const root = { orders: { order_1: order }, payments: { payment_1: cashPayment }, refundCases: {} };
    expect(buildCancelledOrderRoot(root, "order_1", order, "customer_1", "CUSTOMER", 1_798_000_000_100)).toBeNull();
  });

  it("does not cancel a payment while its BDO Checkout link is still payable", () => {
    const pendingOrder = { ...order, status: "PENDING_PAYMENT" };
    const pendingPayment = { ...payment, status: "PROCESSING", checkoutUrl: "https://checkout.bdo.com.ph/request/123" };
    const root = { orders: { order_1: pendingOrder }, payments: { payment_1: pendingPayment }, refundCases: {} };
    expect(buildCancelledOrderRoot(root, "order_1", pendingOrder, "customer_1", "CUSTOMER", 1_798_000_000_100)).toBeNull();
  });
});

describe("trusted order state machine", () => {
  it("allows only the merchant workflow transitions", () => {
    expect(isAllowedOrderTransition("PLACED", "ACCEPTED")).toBe(true);
    expect(isAllowedOrderTransition("ACCEPTED", "PREPARING")).toBe(true);
    expect(isAllowedOrderTransition("PREPARING", "OUT_FOR_DELIVERY")).toBe(true);
    expect(isAllowedOrderTransition("OUT_FOR_DELIVERY", "DELIVERED")).toBe(false);
    expect(isAllowedOrderTransition("DELIVERED", "CANCELLED")).toBe(false);
  });

  it("allows cancellation only before preparation begins", () => {
    expect(isAllowedOrderTransition("PLACED", "CANCELLED")).toBe(true);
    expect(isAllowedOrderTransition("ACCEPTED", "CANCELLED")).toBe(true);
    expect(isAllowedOrderTransition("PREPARING", "CANCELLED")).toBe(false);
  });
});

describe("trusted ride state machine", () => {
  it("allows only accepted-to-started and started-to-completed", () => {
    expect(isAllowedTripTransition("ACCEPTED", "IN_PROGRESS")).toBe(true);
    expect(isAllowedTripTransition("IN_PROGRESS", "COMPLETED")).toBe(true);
    expect(isAllowedTripTransition("REQUESTED", "COMPLETED")).toBe(false);
    expect(isAllowedTripTransition("COMPLETED", "CANCELLED")).toBe(false);
  });
});

describe("administrator paid-delivery reassignment ledger transfer", () => {
  it("moves the courier's pending earning from the previous courier to the replacement", () => {
    const operation = buildDeliveryReassignmentTransfer({
      operationId: "delivery_reassign_1", orderId: "order_1",
      previousCourierId: "courier_old", replacementCourierId: "courier_new",
      amountMinor: 8750, createdAt: 1_798_000_000_100,
    });
    expect(operation.eventType).toBe("ADJUSTMENT");
    expect(operation.referenceType).toBe("ORDER");
    expect(operation.referenceId).toBe("order_1");
    expect(operation.postings).toEqual([
      { accountId: "wallet_delivery_courier_old", bucket: "PENDING", deltaMinor: -8750, partyType: "COURIER", partyId: "courier_old" },
      { accountId: "wallet_delivery_courier_new", bucket: "PENDING", deltaMinor: 8750, partyType: "COURIER", partyId: "courier_new" },
    ]);
    expect(operation.postings.reduce((sum, posting) => sum + posting.deltaMinor, 0)).toBe(0);
  });

  it("rejects zero, unsafe and negative courier allocations", () => {
    for (const amountMinor of [0, -1, Number.MAX_SAFE_INTEGER + 1]) {
      expect(() => buildDeliveryReassignmentTransfer({
        operationId: "delivery_reassign_2", orderId: "order_1",
        previousCourierId: "courier_old", replacementCourierId: "courier_new",
        amountMinor, createdAt: 1_798_000_000_100,
      })).toThrow(/invalid/i);
    }
  });

  it("rejects assigning the same courier as both source and destination", () => {
    expect(() => buildDeliveryReassignmentTransfer({
      operationId: "delivery_reassign_3", orderId: "order_1",
      previousCourierId: "courier_same", replacementCourierId: "courier_same",
      amountMinor: 100, createdAt: 1_798_000_000_100,
    })).toThrow(/invalid/i);
  });
});


describe("claim-time clearing allocation transfer", () => {
  it("moves the exact courier share from payment clearing to the courier pending wallet", () => {
    const operation = buildClaimAllocationTransfer({
      paymentId: "payment_order_1", allocationId: "alloc_payment_order_1_delivery",
      beneficiaryId: "courier_1", beneficiaryType: "DELIVERY",
      amountMinor: 8750, createdAt: 1_798_000_000_100,
    });
    expect(operation.operationId).toBe("payment_payment_order_1_allocation_alloc_payment_order_1_delivery_assigned");
    expect(operation.idempotencyKey).toBe(operation.operationId);
    expect(operation.referenceType).toBe("PAYMENT");
    expect(operation.referenceId).toBe("payment_order_1");
    expect(operation.postings).toEqual([
      { accountId: "unallocated_payment_payment_order_1", bucket: "CLEARING", deltaMinor: -8750, partyType: "PLATFORM", partyId: "platform" },
      { accountId: "wallet_delivery_courier_1", bucket: "PENDING", deltaMinor: 8750, partyType: "COURIER", partyId: "courier_1" },
    ]);
    expect(operation.postings.reduce((sum, posting) => sum + posting.deltaMinor, 0)).toBe(0);
  });

  it("moves the exact driver share into the driver's pending wallet", () => {
    const operation = buildClaimAllocationTransfer({
      paymentId: "payment_ride_1", allocationId: "alloc_payment_ride_1_driver",
      beneficiaryId: "driver_1", beneficiaryType: "DRIVER",
      amountMinor: 4200, createdAt: 1_798_000_000_100,
    });
    expect(operation.postings).toEqual([
      { accountId: "unallocated_payment_payment_ride_1", bucket: "CLEARING", deltaMinor: -4200, partyType: "PLATFORM", partyId: "platform" },
      { accountId: "wallet_driver_driver_1", bucket: "PENDING", deltaMinor: 4200, partyType: "DRIVER", partyId: "driver_1" },
    ]);
  });

  it("uses a stable operation and idempotency key for retries", () => {
    const input = {
      paymentId: "payment_retry", allocationId: "alloc_payment_retry_delivery",
      beneficiaryId: "courier_retry", beneficiaryType: "DELIVERY" as const,
      amountMinor: 1000, createdAt: 1_798_000_000_100,
    };
    const first = buildClaimAllocationTransfer(input);
    const retry = buildClaimAllocationTransfer(input);
    expect(retry.operationId).toBe(first.operationId);
    expect(retry.idempotencyKey).toBe(first.idempotencyKey);
    expect(retry.postings).toEqual(first.postings);
  });

  it("rejects invalid or zero claim shares", () => {
    for (const amountMinor of [0, -1, Number.MAX_SAFE_INTEGER + 1]) {
      expect(() => buildClaimAllocationTransfer({
        paymentId: "payment_invalid", allocationId: "allocation_invalid",
        beneficiaryId: "courier_invalid", beneficiaryType: "DELIVERY",
        amountMinor, createdAt: 1_798_000_000_100,
      })).toThrow(/invalid/i);
    }
  });
});
