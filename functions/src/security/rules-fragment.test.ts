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

  it("limits driver presence reads to passengers with an active ride request", () => {
    expect(rules.driverPresence[".read"]).toContain("activeRideRequests");
    expect(rules.driverPresence[".write"]).toBe(false);
    expect(rules.activeRideRequests["$passengerUid"][".write"]).toBe(false);
  });

  it("isolates driver GPS writes and allows only approved online drivers to publish coordinates", () => {
    expect(rules.driverLocations["$uid"][".write"]).toContain("driverService");
    expect(rules.driverLocations["$uid"][".validate"]).toContain("latitude");
  });

  it("matches app-owned driver and merchant field writes without granting parent-node writes", () => {
    const driver = rules.users["$uid"].driverService;
    expect(driver[".write"]).toBeUndefined();
    expect(driver.vehicleInfo.type[".write"]).toContain("auth.uid == $uid");
    expect(rules.shops["$shopId"].isOnline[".write"]).toContain("ownerUid");
    expect(rules.restaurants["$restaurantId"].isOpen[".write"]).toContain("$restaurantId");
    expect(rules.shopProducts["$shopId"]["$productId"][".write"]).toContain("ownerUid");
    expect(rules.restaurantMeals["$restaurantId"]["$mealId"][".write"]).toContain("$restaurantId");
  });

  it("prevents users from self-approving driver or delivery services", () => {
    expect(rules.users["$uid"].driverService.status[".write"]).toContain("newData.val() == 'PENDING'");
    expect(rules.users["$uid"].deliveryServices.status[".write"]).toContain("newData.val() == 'PENDING'");
  });
});
