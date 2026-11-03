import { describe, expect, it } from "vitest";
import { hasValidDriverCoordinates, isActiveRideStatus } from "./driver-presence.js";

describe("driver presence access guards", () => {
  it.each(["REQUESTED", "ACCEPTED", "IN_PROGRESS"])("treats %s as an active passenger ride", (status) => {
    expect(isActiveRideStatus(status)).toBe(true);
  });

  it.each(["PENDING_PAYMENT", "CANCELLED", "COMPLETED", "", null, undefined])("does not grant presence access for %s", (status) => {
    expect(isActiveRideStatus(status)).toBe(false);
  });

  it("accepts only finite numeric latitude and longitude within geographic bounds", () => {
    expect(hasValidDriverCoordinates({ latitude: 14.5995, longitude: 120.9842 })).toBe(true);
    expect(hasValidDriverCoordinates({ latitude: -90, longitude: 180 })).toBe(true);
  });

  it.each([
    null,
    {},
    { latitude: "14.5", longitude: 120.9 },
    { latitude: 91, longitude: 120.9 },
    { latitude: 14.5, longitude: -181 },
    { latitude: Number.NaN, longitude: 120.9 },
  ])("rejects invalid driver coordinates", (point) => {
    expect(hasValidDriverCoordinates(point)).toBe(false);
  });
});
