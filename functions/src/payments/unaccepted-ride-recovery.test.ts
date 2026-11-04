import { describe, expect, it } from "vitest";
import {
  buildUnacceptedPaidRideRecoveryRoot,
  UNACCEPTED_PAID_RIDE_TIMEOUT_MS,
} from "./unaccepted-ride-recovery.js";

const now = 1_798_000_000_000;
function root(overrides: Record<string, unknown> = {}): any {
  return {
    trips: {
      trip_1: {
        id: "trip_1", passengerId: "passenger_1", paymentId: "payment_1",
        customerTotalMinor: 18500, status: "REQUESTED", driverId: "",
        createdAtTimestamp: now - 60_000, updatedAtTimestamp: now - 60_000,
        paymentStatus: "PAID", paymentState: "PAID",
      },
    },
    payments: {
      payment_1: {
        id: "payment_1", transactionType: "RIDE", tripId: "trip_1",
        payerId: "passenger_1", method: "GCASH", provider: "BDO_CHECKOUT",
        status: "SUCCEEDED", amountMinor: 18500, currency: "PHP",
        completedAt: now - UNACCEPTED_PAID_RIDE_TIMEOUT_MS - 1000,
        refundedAmountMinor: 0, refundReservedAmountMinor: 0,
      },
    },
    activeRideRequests: { passenger_1: { trip_1: { status: "REQUESTED" }, other_trip: { status: "REQUESTED" } } },
    ...overrides,
  };
}

describe("unaccepted paid ride recovery", () => {
  it("cancels the expired ride, reserves the full refundable amount and opens one case", () => {
    const result = buildUnacceptedPaidRideRecoveryRoot(root(), "trip_1", now)!;
    const trip = result.trips.trip_1;
    const payment = result.payments.payment_1;
    expect(trip.status).toBe("CANCELLED");
    expect(trip.paymentStatus).toBe("REFUND_PENDING");
    expect(trip.paymentState).toBe("REFUND_PENDING");
    expect(trip.cancellationReason).toBe("NO_DRIVER_ACCEPTED");
    expect(payment.status).toBe("SUCCEEDED");
    expect(payment.refundReservedAmountMinor).toBe(18500);
    expect(trip.refundCaseId).toMatch(/^ride_timeout_/);
    expect(result.refundCases[trip.refundCaseId]).toMatchObject({
      paymentId: "payment_1", tripId: "trip_1", amountMinor: 18500,
      status: "REQUESTED", origin: "UNACCEPTED_RIDE_TIMEOUT",
    });
    expect(result.activeRideRequests.passenger_1).toEqual({ other_trip: { status: "REQUESTED" } });
  });

  it("does not touch rides before the ten-minute timeout", () => {
    const current = root();
    current.payments.payment_1.completedAt = now - UNACCEPTED_PAID_RIDE_TIMEOUT_MS + 1;
    expect(buildUnacceptedPaidRideRecoveryRoot(current, "trip_1", now)).toBeNull();
  });

  it("measures the timeout from successful payment, not ride creation", () => {
    const current = root();
    current.trips.trip_1.createdAtTimestamp = now - 24 * 60 * 60 * 1000;
    current.trips.trip_1.updatedAtTimestamp = now - 60_000;
    current.payments.payment_1.completedAt = now - 60_000;
    expect(buildUnacceptedPaidRideRecoveryRoot(current, "trip_1", now)).toBeNull();
  });

  it("uses payment completion even if the ride record was updated later", () => {
    const current = root();
    current.trips.trip_1.updatedAtTimestamp = now - 60_000;
    expect(buildUnacceptedPaidRideRecoveryRoot(current, "trip_1", now)).not.toBeNull();
  });

  it("does not cancel a ride accepted concurrently by a driver", () => {
    const current = root();
    current.trips.trip_1.status = "ACCEPTED";
    current.trips.trip_1.driverId = "driver_1";
    expect(buildUnacceptedPaidRideRecoveryRoot(current, "trip_1", now)).toBeNull();
  });

  it("does not refund pending payments, cash rides or invalid payment relationships", () => {
    const pending = root();
    pending.payments.payment_1.status = "PENDING";
    expect(buildUnacceptedPaidRideRecoveryRoot(pending, "trip_1", now)).toBeNull();

    const cash = root();
    cash.payments.payment_1.method = "CASH";
    cash.payments.payment_1.provider = "CASH";
    expect(buildUnacceptedPaidRideRecoveryRoot(cash, "trip_1", now)).toBeNull();

    const mismatched = root();
    mismatched.payments.payment_1.tripId = "another_trip";
    expect(buildUnacceptedPaidRideRecoveryRoot(mismatched, "trip_1", now)).toBeNull();
  });

  it("reserves only the unrefunded and unreserved balance", () => {
    const current = root();
    current.payments.payment_1.status = "PARTIALLY_REFUNDED";
    current.payments.payment_1.refundedAmountMinor = 2500;
    current.payments.payment_1.refundReservedAmountMinor = 1000;
    const result = buildUnacceptedPaidRideRecoveryRoot(current, "trip_1", now)!;
    expect(result.refundCases[result.trips.trip_1.refundCaseId].amountMinor).toBe(15000);
    expect(result.payments.payment_1.refundReservedAmountMinor).toBe(16000);
  });

  it("does not create a zero-value refund case", () => {
    const current = root();
    current.payments.payment_1.refundedAmountMinor = 18500;
    expect(buildUnacceptedPaidRideRecoveryRoot(current, "trip_1", now)).toBeNull();
  });

  it("removes the passenger index even if it was the only active ride", () => {
    const current = root();
    current.activeRideRequests.passenger_1 = { trip_1: { status: "REQUESTED" } };
    const result = buildUnacceptedPaidRideRecoveryRoot(current, "trip_1", now)!;
    expect(result.activeRideRequests).not.toHaveProperty("passenger_1");
  });
});
