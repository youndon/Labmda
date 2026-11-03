import { describe, expect, it } from "vitest";
import { hasRecentAuthentication } from "./payout-profile-security.js";

describe("hasRecentAuthentication", () => {
  const now = 1_800_000_000_000;

  it("accepts a recent Firebase auth_time", () => {
    expect(hasRecentAuthentication(now / 1000 - 60, now)).toBe(true);
  });

  it("rejects an auth_time older than ten minutes", () => {
    expect(hasRecentAuthentication(now / 1000 - 601, now)).toBe(false);
  });

  it("rejects missing, malformed and future auth_time values", () => {
    expect(hasRecentAuthentication(undefined, now)).toBe(false);
    expect(hasRecentAuthentication("1800000000", now)).toBe(false);
    expect(hasRecentAuthentication(now / 1000 + 60, now)).toBe(false);
  });

  it("rejects non-positive timestamps", () => {
    expect(hasRecentAuthentication(0, now)).toBe(false);
  });
});
