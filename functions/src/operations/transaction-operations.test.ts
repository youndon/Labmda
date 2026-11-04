import { describe, expect, it } from "vitest";
import { buildClaimAllocationTransfer, buildDeliveryReassignmentTransfer, isAllowedOrderTransition, isAllowedTripTransition } from "./transaction-operations.js";

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
