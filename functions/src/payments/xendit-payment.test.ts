import { describe, expect, it } from "vitest";
import type { Database } from "firebase-admin/database";
import {
  amountPesosToMinor,
  createXenditGcashPaymentRequest,
  processXenditPaymentWebhook,
  verifyXenditCallbackToken,
} from "./xendit-payment.js";

function fakeDatabase(seed: Record<string, any>) {
  let value = structuredClone(seed);
  const database = {
    ref: () => ({
      transaction: async (update: (current: Record<string, any> | null) => Record<string, any> | null | undefined) => {
        const next = update(structuredClone(value));
        if (next === undefined) return { committed: false, snapshot: { val: () => structuredClone(value) } };
        value = structuredClone(next) as Record<string, any>;
        return { committed: true, snapshot: { val: () => structuredClone(value) } };
      },
    }),
  };
  return { database: database as unknown as Database, value: () => structuredClone(value) };
}

const payment = {
  id: "payment_1", payerId: "customer_1", method: "GCASH", provider: "XENDIT",
  providerRequestId: "pr-12345678-1234-1234-1234-123456789012",
  providerBusinessId: "business_1", amountMinor: 12_500, currency: "PHP",
  status: "PROCESSING", updatedAt: 100,
};

describe("Xendit GCash checkout", () => {
  it("converts PHP major units to exact centavos", () => {
    expect(amountPesosToMinor(125)).toBe(12_500);
    expect(amountPesosToMinor(12.34)).toBe(1_234);
    expect(amountPesosToMinor(12.345)).toBeNull();
    expect(amountPesosToMinor("12.50")).toBeNull();
  });

  it("creates a PHP GCash Payment Request with a stable idempotency key", async () => {
    let requestUrl = "";
    let requestInit: RequestInit | undefined;
    const fetcher: typeof fetch = async (input, init) => {
      requestUrl = String(input);
      requestInit = init;
      return new Response(JSON.stringify({
        payment_request_id: "pr-12345678-1234-1234-1234-123456789012",
        reference_id: "payment_1", business_id: "business_1",
        request_amount: 125, currency: "PHP", status: "REQUIRES_ACTION",
        actions: [{ type: "REDIRECT_CUSTOMER", value: "https://checkout.xendit.co/pay/abc" }],
      }), { status: 200, headers: { "Content-Type": "application/json" } });
    };
    const result = await createXenditGcashPaymentRequest({
      paymentId: "payment_1", amountMinor: 12_500, secretKey: "xnd_development_test", fetcher,
    });
    expect(requestUrl).toBe("https://api.xendit.co/v3/payment_requests");
    expect(requestInit?.headers).toMatchObject({
      "api-version": "2024-11-11",
      "idempotency-key": "labmda-payment-payment_1",
    });
    expect(JSON.parse(String(requestInit?.body))).toMatchObject({
      reference_id: "payment_1", type: "PAY", country: "PH", currency: "PHP",
      request_amount: 125, capture_method: "AUTOMATIC", channel_code: "GCASH",
    });
    expect(result).toMatchObject({
      paymentRequestId: "pr-12345678-1234-1234-1234-123456789012",
      businessId: "business_1", checkoutUrl: "https://checkout.xendit.co/pay/abc",
      status: "REQUIRES_ACTION",
    });
  });

  it("rejects a provider response with a mismatched amount", async () => {
    const fetcher: typeof fetch = async () => new Response(JSON.stringify({
      payment_request_id: "pr-12345678-1234-1234-1234-123456789012",
      reference_id: "payment_1", business_id: "business_1",
      request_amount: 124, currency: "PHP", status: "REQUIRES_ACTION",
      actions: [{ type: "REDIRECT_CUSTOMER", value: "https://checkout.xendit.co/pay/abc" }],
    }), { status: 200 });
    await expect(createXenditGcashPaymentRequest({
      paymentId: "payment_1", amountMinor: 12_500, secretKey: "xnd_development_test", fetcher,
    })).rejects.toThrow(/does not match/);
  });
});

describe("Xendit authenticated payment notifications", () => {
  it("compares callback tokens and rejects missing or incorrect values", () => {
    expect(verifyXenditCallbackToken("private-token", "private-token")).toBe(true);
    expect(verifyXenditCallbackToken("private-token", "wrong-token")).toBe(false);
    expect(verifyXenditCallbackToken("private-token", undefined)).toBe(false);
  });

  it("marks the matching payment succeeded exactly once from an authenticated capture event", async () => {
    const fake = fakeDatabase({ payments: { payment_1: { ...payment } } });
    const event = {
      event: "payment.capture", business_id: "business_1",
      data: {
        payment_id: "py-123", capture_id: "cap-123", reference_id: "payment_1",
        payment_request_id: payment.providerRequestId, request_amount: 125,
        currency: "PHP", channel_code: "GCASH", status: "SUCCEEDED",
      },
    };
    const first = await processXenditPaymentWebhook(fake.database, event);
    expect(first).toMatchObject({ accepted: true, duplicate: false, status: "SUCCEEDED" });
    expect(fake.value().payments.payment_1).toMatchObject({
      status: "SUCCEEDED", providerReference: "py-123", providerCaptureId: "cap-123",
    });
    const second = await processXenditPaymentWebhook(fake.database, event);
    expect(second).toMatchObject({ accepted: true, duplicate: true, status: "SUCCEEDED" });
    expect(Object.keys(fake.value().paymentWebhookEvents)).toHaveLength(1);
  });

  it("rejects mismatched payment amounts without changing the payment", async () => {
    const fake = fakeDatabase({ payments: { payment_1: { ...payment } } });
    const result = await processXenditPaymentWebhook(fake.database, {
      event: "payment.capture", business_id: "business_1",
      data: {
        payment_id: "py-123", capture_id: "cap-bad", reference_id: "payment_1",
        payment_request_id: payment.providerRequestId, request_amount: 124,
        currency: "PHP", channel_code: "GCASH", status: "SUCCEEDED",
      },
    });
    expect(result.accepted).toBe(false);
    expect(fake.value().payments.payment_1.status).toBe("PROCESSING");
    expect(fake.value().paymentWebhookEvents).toBeUndefined();
  });

  it("does not let a late failure notification downgrade a captured payment", async () => {
    const fake = fakeDatabase({ payments: { payment_1: { ...payment, status: "SUCCEEDED" } } });
    const result = await processXenditPaymentWebhook(fake.database, {
      event: "payment.failure", business_id: "business_1",
      data: {
        reference_id: "payment_1", payment_request_id: payment.providerRequestId,
        request_amount: 125, currency: "PHP", channel_code: "GCASH", failure_code: "FAILED",
      },
    });
    expect(result).toMatchObject({ accepted: true, status: "SUCCEEDED" });
    expect(fake.value().payments.payment_1.status).toBe("SUCCEEDED");
  });

  it("rejects an event for another Xendit business", async () => {
    const fake = fakeDatabase({ payments: { payment_1: { ...payment } } });
    const result = await processXenditPaymentWebhook(fake.database, {
      event: "payment.capture", business_id: "another_business",
      data: {
        payment_id: "py-123", capture_id: "cap-other", reference_id: "payment_1",
        payment_request_id: payment.providerRequestId, request_amount: 125,
        currency: "PHP", channel_code: "GCASH", status: "SUCCEEDED",
      },
    });
    expect(result.accepted).toBe(false);
    expect(fake.value().payments.payment_1.status).toBe("PROCESSING");
  });
});
