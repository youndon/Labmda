import { describe, expect, it } from "vitest";
import { buildAutomaticRideRefundCase } from "./ride-refunds.js";

describe("automatic unaccepted-ride refund cases", () => {
  const input = {
    tripId: "trip_1", paymentId: "payment_1", payerId: "passenger_1",
    amountMinor: 18500, createdAt: 1_798_000_000_100,
  };

  it("uses a deterministic ride-specific refund case ID", () => {
    const first = buildAutomaticRideRefundCase(input);
    expect(first).toEqual(buildAutomaticRideRefundCase(input));
    expect(first.id).toMatch(/^ride_timeout_[a-f0-9]{32}$/);
    expect(first.tripId).toBe("trip_1");
    expect(first.origin).toBe("UNACCEPTED_RIDE_TIMEOUT");
    expect(first.requestedByRole).toBe("SYSTEM");
    expect(first.amountMinor).toBe(18500);
    expect(first.status).toBe("REQUESTED");
  });

  it("rejects invalid identifiers, amounts and timestamps", () => {
    expect(() => buildAutomaticRideRefundCase({ ...input, tripId: "bad/id" })).toThrow(/invalid/i);
    expect(() => buildAutomaticRideRefundCase({ ...input, amountMinor: 0 })).toThrow(/invalid/i);
    expect(() => buildAutomaticRideRefundCase({ ...input, createdAt: 0 })).toThrow(/invalid/i);
  });
});
