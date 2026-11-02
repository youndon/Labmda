import { describe, expect, it } from "vitest";
import { isAllowedOrderTransition, isAllowedTripTransition } from "./transaction-operations.js";

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
