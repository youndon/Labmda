import { describe, expect, it } from "vitest";
import { buildAbortRefundProcessingRoot, buildCompletedRefundRoot } from "./refund-lifecycle.js";

const baseRoot = () => ({
  payments: {
    payment_order: {
      id: "payment_order", transactionType: "ORDER", orderId: "order_1", amountMinor: 10_000,
      refundedAmountMinor: 0, refundReservedAmountMinor: 10_000, refundProcessingId: "refund_1",
      status: "SUCCEEDED",
    },
    payment_ride: {
      id: "payment_ride", transactionType: "RIDE", tripId: "trip_1", amountMinor: 5_000,
      refundedAmountMinor: 0, refundReservedAmountMinor: 5_000, refundProcessingId: "refund_2",
      status: "SUCCEEDED",
    },
  },
  refundCases: {
    refund_1: { id: "refund_1", paymentId: "payment_order", amountMinor: 10_000, status: "PROCESSING" },
    refund_2: { id: "refund_2", paymentId: "payment_ride", amountMinor: 5_000, status: "PROCESSING" },
  },
  orders: { order_1: { id: "order_1", paymentId: "payment_order", status: "PLACED" } },
  trips: { trip_1: { id: "trip_1", paymentId: "payment_ride", passengerId: "passenger_1", status: "REQUESTED" } },
  activeRideRequests: { passenger_1: { trip_1: { status: "REQUESTED" } } },
  refundProviderReferenceIndex: { ref_hash: "refund_1" },
});

describe("refund lifecycle completion effects", () => {
  it("cancels a fully refunded order", () => {
    const root = baseRoot();
    const next = buildCompletedRefundRoot(root, "refund_1", "admin_1", "BDO-REF-1", "refund_refund_1_posted", 1234)!;
    expect(next.payments.payment_order).toMatchObject({ status: "REFUNDED", refundProcessingId: null });
    expect(next.orders.order_1).toMatchObject({ status: "CANCELLED", paymentStatus: "REFUNDED", cancellationReason: "FULL_REFUND_COMPLETED" });
    expect(next.refundCases.refund_1).toMatchObject({ status: "COMPLETED", ledgerOperationId: "refund_refund_1_posted" });
  });

  it("cancels a fully refunded ride and removes its active ride request", () => {
    const root = baseRoot();
    const next = buildCompletedRefundRoot(root, "refund_2", "admin_1", "BDO-REF-2", "refund_refund_2_posted", 1234)!;
    expect(next.trips.trip_1).toMatchObject({ status: "CANCELLED", paymentStatus: "REFUNDED", paymentState: "REFUNDED" });
    expect(next.activeRideRequests.passenger_1).toBeUndefined();
  });

  it("does not cancel an order for a partial refund", () => {
    const root = baseRoot();
    root.payments.payment_order.amountMinor = 20_000;
    const next = buildCompletedRefundRoot(root, "refund_1", "admin_1", "BDO-REF-1", "refund_refund_1_posted", 1234)!;
    expect(next.payments.payment_order.status).toBe("PARTIALLY_REFUNDED");
    expect(next.orders.order_1.status).toBe("PLACED");
  });

  it("releases a stale processing lock but preserves the approved refund reservation", () => {
    const root = baseRoot();
    root.refundCases.refund_1.status = "ABORTING";
    root.refundCases.refund_1.providerReference = "BDO-REF-1";
    root.refundCases.refund_1.providerReferenceKey = "ref_hash";
    root.refundCases.refund_1.processingAt = 100;
    const next = buildAbortRefundProcessingRoot(root, "refund_1", "admin_1", "Provider confirmed no transfer was sent.", 200)!;
    expect(next.payments.payment_order).toMatchObject({ status: "SUCCEEDED", refundProcessingId: undefined, refundReservedAmountMinor: 10_000 });
    expect(next.refundCases.refund_1).toMatchObject({ status: "APPROVED", abortedBy: "admin_1" });
    expect(next.refundCases.refund_1.providerReference).toBeUndefined();
    expect(next.refundProviderReferenceIndex.ref_hash).toBeUndefined();
  });
});
