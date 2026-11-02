import { describe, expect, it } from "vitest";
import {
  buildCashPaymentBundle,
  validateOrderMatches,
  validateRideMatches,
} from "./payment-initialization.js";
import { buildOrderPaymentAllocations, buildRidePaymentAllocations } from "./payment-allocations.js";

const orderQuote = {
  uid: "customer_1", kind: "FOOD", sourceId: "restaurant_1",
  paymentMethod: "CASH", currency: "PHP", status: "QUOTED", expiresAt: Date.now() + 60_000,
  request: {
    pickup: { latitude: 10, longitude: 20 },
    dropoff: { latitude: 10.1, longitude: 20.1 },
  },
  items: [{ itemId: "meal_1", quantity: 2 }],
  breakdown: {
    currency: "PHP", subtotalMinor: 2_000, deliveryFeeMinor: 500,
    customerTotalMinor: 2_500, merchantEarningsMinor: 1_800,
    courierEarningsMinor: 450, platformCommissionMinor: 250,
    platformDiscountMinor: 0, providerFeeMinor: 0,
  },
};
const order = {
  clientId: "customer_1", sourceId: "restaurant_1", orderType: "FOOD",
  status: "PLACED", paymentMethod: "CASH", pickupLat: 10, pickupLng: 20,
  dropoffLat: 10.1, dropoffLng: 20.1,
  items: [{ id: "meal_1", quantity: 2 }],
  subtotal: 20, deliveryFee: 5, totalPrice: 25, deliveryId: "courier_1",
};
const rideQuote = {
  uid: "passenger_1", kind: "RIDE", paymentMethod: "CASH", currency: "PHP",
  status: "QUOTED", expiresAt: Date.now() + 60_000,
  request: {
    pickup: { latitude: 11, longitude: 21 },
    dropoff: { latitude: 11.1, longitude: 21.1 },
  },
  breakdown: {
    currency: "PHP", fareMinor: 3_000, customerTotalMinor: 3_000,
    driverEarningsMinor: 2_400, platformCommissionMinor: 600, providerFeeMinor: 0,
  },
};

describe("trusted payment initialization validation", () => {
  it("accepts an order only when ownership, item quantities, route and server quote totals match", () => {
    expect(() => validateOrderMatches(order, orderQuote, "customer_1")).not.toThrow();
    expect(() => validateOrderMatches({ ...order, totalPrice: 26 }, orderQuote, "customer_1")).toThrow(/totals do not match/);
    expect(() => validateOrderMatches({ ...order, items: [{ id: "meal_1", quantity: 3 }] }, orderQuote, "customer_1")).toThrow(/items or quantities/);
    expect(() => validateOrderMatches(order, orderQuote, "another_user")).toThrow(/ownership/);
  });

  it("requires an accepted, assigned ride matching the quote", () => {
    const trip = {
      passengerId: "passenger_1", status: "ACCEPTED", driverId: "driver_1",
      pickupLat: 11, pickupLng: 21, dropoffLat: 11.1, dropoffLng: 21.1, fare: 30,
    };
    expect(() => validateRideMatches(trip, rideQuote, "passenger_1")).not.toThrow();
    expect(() => validateRideMatches({ ...trip, status: "REQUESTED" }, rideQuote, "passenger_1")).toThrow(/accepted driver/);
    expect(() => validateRideMatches({ ...trip, fare: 31 }, rideQuote, "passenger_1")).toThrow(/fare does not match/);
  });

  it("builds pending payment and collection records without client-supplied amounts", () => {
    const allocations = buildOrderPaymentAllocations({
      paymentId: "payment_1", orderId: "order_1", merchantOwnerId: "restaurant_1",
      courierId: "courier_1", breakdown: orderQuote.breakdown,
      providerFeePayer: "PLATFORM", createdAt: 100,
    });
    const bundle = buildCashPaymentBundle({
      paymentId: "payment_1", uid: "customer_1", transactionType: "ORDER",
      orderId: "order_1", tripId: "", deliveryId: "courier_1",
      collectorId: "courier_1", amountMinor: 2_500, allocations, now: 100,
    });
    expect(bundle.payment).toMatchObject({
      id: "payment_1", payerId: "customer_1", method: "CASH",
      status: "PENDING", amountMinor: 2_500, currency: "PHP",
    });
    expect(bundle.cashCollection).toMatchObject({
      paymentId: "payment_1", collectorId: "courier_1",
      status: "PENDING", amountMinor: 2_500,
    });
    expect(Object.values(bundle.allocations).reduce((sum, item) => sum + item.amountMinor, 0)).toBe(2_500);
  });

  it("builds a balanced ride allocation for the assigned driver", () => {
    const allocations = buildRidePaymentAllocations({
      paymentId: "ride_payment_1", tripId: "trip_1", driverId: "driver_1",
      fareMinor: 3_000, driverEarningsMinor: 2_400,
      platformCommissionMinor: 600, providerFeeMinor: 0,
      providerFeePayer: "PLATFORM", customerTotalMinor: 3_000, createdAt: 100,
    });
    expect(allocations.reduce((sum, item) => sum + item.amountMinor, 0)).toBe(3_000);
    expect(allocations.find((item) => item.beneficiaryType === "DRIVER")?.beneficiaryId).toBe("driver_1");
  });
});
