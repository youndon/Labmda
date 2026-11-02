import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const rules = JSON.parse(
  readFileSync(resolve(process.cwd(), "../database.order-ride.rules.fragment.json"), "utf8"),
).rules;

describe("order and ride Realtime Database rules fragment", () => {
  it("denies direct client writes to authoritative orders and trips", () => {
    expect(rules.orders[".write"]).toBe(false);
    expect(rules.trips[".write"]).toBe(false);
    expect(rules.orders["$orderId"][".write"]).toBe(false);
    expect(rules.trips["$tripId"][".write"]).toBe(false);
  });

  it("requires indexed participant/dispatch queries", () => {
    expect(rules.orders[".indexOn"]).toEqual(expect.arrayContaining(["clientId", "sourceId", "deliveryId", "status"]));
    expect(rules.trips[".indexOn"]).toEqual(expect.arrayContaining(["passengerId", "driverId", "status"]));
  });

  it("keeps driver presence read-only to clients", () => {
    expect(rules.driverPresence[".write"]).toBe(false);
  });

  it("prevents users from self-approving driver or delivery services", () => {
    expect(rules.users["$uid"].driverService.status[".write"]).toContain("newData.val() == 'PENDING'");
    expect(rules.users["$uid"].deliveryServices.status[".write"]).toContain("newData.val() == 'PENDING'");
  });
});
