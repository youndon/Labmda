import { afterEach, describe, expect, it } from "vitest";
import { synchronizePaymentState } from "./payment-state-sync.js";
import { createRtdbEmulatorContext } from "../testing/rtdb-emulator.js";

const emulatorOnly = !!process.env.FIREBASE_DATABASE_EMULATOR_HOST;

describe.skipIf(!emulatorOnly)("RTDB emulator: end-to-end order and ride payment projections", () => {
  let cleanup: (() => Promise<void>) | undefined;

  afterEach(async () => {
    await cleanup?.();
    cleanup = undefined;
  });

  it("unlocks food, shop and standalone delivery orders only after successful payment", async () => {
    const { database, cleanup: dispose } = await createRtdbEmulatorContext({
      payments: {
        food_payment: { id: "food_payment", transactionType: "ORDER", orderId: "food_order", method: "GCASH", provider: "BDO_CHECKOUT", status: "SUCCEEDED", updatedAt: 100 },
        shop_payment: { id: "shop_payment", transactionType: "ORDER", orderId: "shop_order", method: "GCASH", provider: "BDO_CHECKOUT", status: "SUCCEEDED", updatedAt: 101 },
        delivery_payment: { id: "delivery_payment", transactionType: "ORDER", orderId: "delivery_order", method: "GCASH", provider: "BDO_CHECKOUT", status: "SUCCEEDED", updatedAt: 102 },
      },
      orders: {
        food_order: { id: "food_order", orderType: "FOOD", paymentId: "food_payment", status: "PENDING_PAYMENT", paymentStatus: "PENDING" },
        shop_order: { id: "shop_order", orderType: "RETAIL", paymentId: "shop_payment", status: "PENDING_PAYMENT", paymentStatus: "PENDING" },
        delivery_order: { id: "delivery_order", orderType: "DELIVERY", paymentId: "delivery_payment", status: "PENDING_PAYMENT", paymentStatus: "PENDING" },
      },
    });
    cleanup = dispose;

    expect(await synchronizePaymentState(database, "food_payment")).toBe(true);
    expect(await synchronizePaymentState(database, "shop_payment")).toBe(true);
    expect(await synchronizePaymentState(database, "delivery_payment")).toBe(true);

    const saved = await database.ref().get();
    expect(saved.child("orders/food_order").val()).toMatchObject({ status: "PLACED", paymentStatus: "PAID" });
    expect(saved.child("orders/shop_order").val()).toMatchObject({ status: "PLACED", paymentStatus: "PAID" });
    expect(saved.child("orders/delivery_order").val()).toMatchObject({ status: "OUT_FOR_DELIVERY", paymentStatus: "PAID" });
  });

  it("activates a ride only after successful payment and removes it after expiry", async () => {
    const { database, cleanup: dispose } = await createRtdbEmulatorContext({
      payments: {
        ride_payment: { id: "ride_payment", transactionType: "RIDE", tripId: "trip_1", method: "GCASH", provider: "BDO_CHECKOUT", status: "SUCCEEDED", updatedAt: 200 },
      },
      trips: {
        trip_1: { id: "trip_1", paymentId: "ride_payment", passengerId: "passenger_1", status: "PENDING_PAYMENT", paymentState: "PENDING" },
      },
      activeRideRequests: {},
    });
    cleanup = dispose;

    expect(await synchronizePaymentState(database, "ride_payment")).toBe(true);
    let saved = await database.ref().get();
    expect(saved.child("trips/trip_1").val()).toMatchObject({ status: "REQUESTED", paymentState: "PAID" });
    expect(saved.child("activeRideRequests/passenger_1/trip_1/status").val()).toBe("REQUESTED");

    await database.ref("payments/ride_payment").update({ status: "EXPIRED", updatedAt: 201 });
    expect(await synchronizePaymentState(database, "ride_payment")).toBe(true);
    saved = await database.ref().get();
    expect(saved.child("trips/trip_1").val()).toMatchObject({ status: "CANCELLED", paymentState: "EXPIRED" });
    expect(saved.child("activeRideRequests/passenger_1/trip_1").exists()).toBe(false);
  });

  it("does not project payment status onto an order linked to a different payment", async () => {
    const { database, cleanup: dispose } = await createRtdbEmulatorContext({
      payments: {
        payment_1: { id: "payment_1", transactionType: "ORDER", orderId: "order_1", method: "GCASH", status: "SUCCEEDED", updatedAt: 300 },
      },
      orders: {
        order_1: { id: "order_1", paymentId: "another_payment", status: "PENDING_PAYMENT", paymentStatus: "PENDING" },
      },
    });
    cleanup = dispose;

    expect(await synchronizePaymentState(database, "payment_1")).toBe(false);
    const saved = await database.ref("orders/order_1").get();
    expect(saved.val()).toMatchObject({ status: "PENDING_PAYMENT", paymentStatus: "PENDING" });
  });
});
