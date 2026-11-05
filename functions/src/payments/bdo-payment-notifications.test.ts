import { describe, expect, it } from "vitest";
import { classifyBdoPaymentNotification } from "./bdo-payment-notifications.js";

describe("classifyBdoPaymentNotification", () => {
  it("notifies the customer when a checkout link is attached", () => {
    expect(classifyBdoPaymentNotification(
      { provider: "BDO_CHECKOUT", method: "GCASH", status: "PENDING" },
      { provider: "BDO_CHECKOUT", method: "GCASH", status: "PENDING", checkoutUrl: "https://checkout.bdo.com.ph/ref" },
    )?.kind).toBe("CHECKOUT_READY");
  });

  it("alerts admins when the customer requests cancellation without falsely cancelling payment", () => {
    expect(classifyBdoPaymentNotification(
      { provider: "BDO_CHECKOUT", method: "GCASH", status: "PENDING" },
      { provider: "BDO_CHECKOUT", method: "GCASH", status: "PENDING", cancellationRequest: { status: "REQUESTED", requestedAt: 123 } },
    )?.kind).toBe("CANCELLATION_REQUESTED");
  });

  it("notifies when trusted backend status changes", () => {
    expect(classifyBdoPaymentNotification(
      { provider: "BDO_CHECKOUT", method: "GCASH", status: "PROCESSING" },
      { provider: "BDO_CHECKOUT", method: "GCASH", status: "SUCCEEDED" },
    )).toMatchObject({ kind: "SUCCEEDED", title: "GCash payment confirmed" });
  });

  it("ignores unrelated providers and unchanged payment records", () => {
    expect(classifyBdoPaymentNotification(null, { provider: "SIMULATED", method: "GCASH", status: "SUCCEEDED" })).toBeNull();
    expect(classifyBdoPaymentNotification(
      { provider: "BDO_CHECKOUT", method: "GCASH", status: "PENDING" },
      { provider: "BDO_CHECKOUT", method: "GCASH", status: "PENDING" },
    )).toBeNull();
  });
});
