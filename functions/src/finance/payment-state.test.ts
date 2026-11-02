import { describe, expect, it } from "vitest";
import { assertPaymentStatusTransition, canTransitionPaymentStatus, isFinalPaymentStatus } from "./payment-state.js";

describe("provider-independent payment state machine", () => {
  it("allows pending cash and online payments to reach their normalized outcomes", () => {
    for (const status of ["PROCESSING", "SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"] as const) {
      expect(canTransitionPaymentStatus("PENDING", status)).toBe(true);
    }
  });

  it("allows processing online payments to finish or expire", () => {
    for (const status of ["SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"] as const) {
      expect(canTransitionPaymentStatus("PROCESSING", status)).toBe(true);
    }
  });

  it("prevents failed, cancelled and expired payments from being reopened", () => {
    for (const status of ["FAILED", "CANCELLED", "EXPIRED"] as const) {
      expect(canTransitionPaymentStatus(status, "SUCCEEDED")).toBe(false);
      expect(isFinalPaymentStatus(status)).toBe(true);
    }
  });

  it("allows only refund transitions after successful collection", () => {
    expect(canTransitionPaymentStatus("SUCCEEDED", "PARTIALLY_REFUNDED")).toBe(true);
    expect(canTransitionPaymentStatus("SUCCEEDED", "REFUNDED")).toBe(true);
    expect(canTransitionPaymentStatus("SUCCEEDED", "PENDING")).toBe(false);
    expect(() => assertPaymentStatusTransition("FAILED", "SUCCEEDED")).toThrow(/invalid payment status transition/i);
  });

  it("allows same-state retries as no-ops", () => {
    expect(canTransitionPaymentStatus("SUCCEEDED", "SUCCEEDED")).toBe(true);
  });
});
