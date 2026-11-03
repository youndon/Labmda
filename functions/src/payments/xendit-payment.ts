import { createHash, timingSafeEqual } from "node:crypto";
import { getDatabase, type Database } from "firebase-admin/database";
import { defineSecret, defineString } from "firebase-functions/params";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";

const xenditSecretKey = defineSecret("XENDIT_SECRET_KEY");
const xenditWebhookToken = defineSecret("XENDIT_WEBHOOK_TOKEN");
const livePaymentsEnabled = defineString("XENDIT_LIVE_PAYMENTS_ENABLED", { default: "false" });

type XenditAction = { type?: string; value?: string; descriptor?: string };
type XenditPaymentRequestResponse = {
  payment_request_id?: string;
  reference_id?: string;
  business_id?: string;
  request_amount?: number;
  currency?: string;
  status?: string;
  actions?: XenditAction[];
};

type XenditWebhook = {
  event?: string;
  business_id?: string;
  created?: string;
  data?: {
    payment_id?: string;
    capture_id?: string;
    reference_id?: string;
    payment_request_id?: string;
    business_id?: string;
    request_amount?: number;
    currency?: string;
    channel_code?: string;
    status?: string;
    failure_code?: string;
    captures?: Array<{ capture_id?: string; capture_amount?: number }>;
  };
  paymentCapture?: { value?: XenditWebhook };
  paymentFailure?: { value?: XenditWebhook };
  paymentRequestExpiry?: { value?: XenditWebhook };
};

function safeId(value: unknown): value is string {
  return typeof value === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(value);
}

export function amountPesosToMinor(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value) || value <= 0) return null;
  const minor = Math.round(value * 100);
  return Number.isSafeInteger(minor) && Math.abs(value * 100 - minor) < 0.000001 ? minor : null;
}

export function normalizeXenditCheckoutUrl(value: unknown): string | null {
  if (typeof value !== "string" || !value.trim()) return null;
  try {
    const raw = value.trim();
    const normalized = /^[a-z][a-z0-9+.-]*:\/\//i.test(raw) ? raw : `https://${raw}`;
    const url = new URL(normalized);
    return url.protocol === "https:" && Boolean(url.hostname) && !url.username && !url.password
      ? url.toString()
      : null;
  } catch {
    return null;
  }
}

export function verifyXenditCallbackToken(expected: string, supplied: string | undefined): boolean {
  if (!expected || !supplied) return false;
  const left = Buffer.from(expected, "utf8");
  const right = Buffer.from(supplied, "utf8");
  return left.length === right.length && timingSafeEqual(left, right);
}

/** Calls Xendit's Payment Request API. The idempotency key is stable per Labmda payment. */
export async function createXenditGcashPaymentRequest(input: {
  paymentId: string;
  amountMinor: number;
  secretKey: string;
  fetcher?: typeof fetch;
}): Promise<{
  paymentRequestId: string;
  businessId: string;
  checkoutUrl: string;
  status: string;
}> {
  if (!safeId(input.paymentId) || !Number.isSafeInteger(input.amountMinor) || input.amountMinor <= 0) {
    throw new Error("Payment reference and PHP-centavo amount are invalid.");
  }
  if (!input.secretKey.startsWith("xnd_development_") && !input.secretKey.startsWith("xnd_production_")) {
    throw new Error("Xendit API key format is not recognized.");
  }
  const fetcher = input.fetcher ?? fetch;
  const response = await fetcher("https://api.xendit.co/v3/payment_requests", {
    method: "POST",
    headers: {
      Authorization: `Basic ${Buffer.from(`${input.secretKey}:`).toString("base64")}`,
      "Content-Type": "application/json",
      "api-version": "2024-11-11",
      "idempotency-key": `labmda-payment-${input.paymentId}`,
    },
    body: JSON.stringify({
      reference_id: input.paymentId,
      type: "PAY",
      country: "PH",
      currency: "PHP",
      request_amount: Number((input.amountMinor / 100).toFixed(2)),
      capture_method: "AUTOMATIC",
      channel_code: "GCASH",
      description: `Labmda GCash payment ${input.paymentId}`,
      metadata: { labmda_payment_id: input.paymentId },
    }),
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) {
    // Do not persist or log the provider response body; it can contain sensitive details.
    throw new Error(`Xendit could not create the GCash payment request (HTTP ${response.status}).`);
  }
  const body = await response.json() as XenditPaymentRequestResponse;
  const paymentRequestId = body.payment_request_id ?? "";
  const businessId = body.business_id ?? "";
  if (!paymentRequestId || body.reference_id !== input.paymentId || body.currency !== "PHP"
    || amountPesosToMinor(body.request_amount) !== input.amountMinor || !businessId) {
    throw new Error("Xendit returned a payment request that does not match Labmda's reference, amount or currency.");
  }
  const action = (body.actions ?? []).find((item) => item.type === "REDIRECT_CUSTOMER" && normalizeXenditCheckoutUrl(item.value));
  const checkoutUrl = normalizeXenditCheckoutUrl(action?.value) ?? "";
  const status = String(body.status ?? "").toUpperCase();
  if (status === "REQUIRES_ACTION" && !checkoutUrl) {
    throw new Error("Xendit requires customer action but did not return a valid HTTPS checkout URL.");
  }
  return { paymentRequestId, businessId, checkoutUrl, status };
}

/**
 * Applies authenticated Xendit payment events to the canonical payment record.
 * The root RTDB transaction atomically de-duplicates the event and changes payment state.
 */
export async function processXenditPaymentWebhook(
  database: Database,
  event: XenditWebhook,
): Promise<{ accepted: boolean; duplicate: boolean; status?: string }> {
  const normalized = event.event ? event
    : event.paymentCapture?.value ?? event.paymentFailure?.value ?? event.paymentRequestExpiry?.value ?? event;
  const eventName = String(normalized.event ?? "");
  const data = normalized.data;
  if (!data || !["payment.capture", "payment.failure", "payment_request.expiry"].includes(eventName)) {
    return { accepted: true, duplicate: false };
  }
  const paymentId = data.reference_id ?? "";
  const paymentRequestId = data.payment_request_id ?? "";
  if (!safeId(paymentId) || !paymentRequestId || paymentRequestId.length > 128) {
    throw new Error("Xendit webhook is missing a valid Labmda reference or payment request ID.");
  }
  if (data.channel_code && data.channel_code !== "GCASH") {
    throw new Error("Xendit webhook channel does not match GCash.");
  }
  const amountMinor = data.request_amount === undefined ? null : amountPesosToMinor(data.request_amount);
  if ((data.request_amount !== undefined && amountMinor === null)
    || (data.currency !== undefined && data.currency !== "PHP")) {
    throw new Error("Xendit webhook has an invalid amount or currency.");
  }
  if (eventName === "payment.capture" && (amountMinor === null || data.currency !== "PHP" || !data.payment_id)) {
    throw new Error("Xendit capture webhook has an invalid amount, currency or payment reference.");
  }
  const eventIdentity = data.capture_id ?? data.captures?.[0]?.capture_id
    ?? (eventName === "payment.capture" ? data.payment_id : createHash("sha256").update(JSON.stringify(normalized)).digest("hex"));
  const eventKey = createHash("sha256").update(`${eventName}:${eventIdentity}`).digest("hex");
  let rejected = false;
  const now = Date.now();
  const result = await database.ref().transaction((root: Record<string, any> | null) => {
    if (root === null) return null;
    const events = root.paymentWebhookEvents ?? {};
    if (events[eventKey]) {
      const prior = events[eventKey];
      if (prior.paymentId !== paymentId || prior.paymentRequestId !== paymentRequestId
        || prior.amountMinor !== amountMinor || prior.currency !== (data.currency ?? "PHP")) rejected = true;
      return root;
    }
    const payment = root.payments?.[paymentId] as Record<string, any> | undefined;
    if (!payment || payment.id !== paymentId || payment.method !== "GCASH" || payment.provider !== "XENDIT"
      || payment.currency !== "PHP" || payment.providerRequestId !== paymentRequestId
      || (amountMinor !== null && payment.amountMinor !== amountMinor)) {
      rejected = true;
      return root;
    }
    const businessId = data.business_id ?? normalized.business_id ?? "";
    if (payment.providerBusinessId && businessId !== payment.providerBusinessId) {
      rejected = true;
      return root;
    }
    const currentStatus = String(payment.status ?? "").toUpperCase();
    let nextStatus: "SUCCEEDED" | "FAILED" | "EXPIRED";
    if (eventName === "payment.capture") nextStatus = "SUCCEEDED";
    else if (eventName === "payment.failure") nextStatus = "FAILED";
    else nextStatus = "EXPIRED";

    // A late failure/expiry event must never downgrade a payment already captured.
    const finalStatus = currentStatus === "SUCCEEDED" && nextStatus !== "SUCCEEDED"
      ? "SUCCEEDED"
      : nextStatus;
    const nextPayment = {
      ...payment,
      status: finalStatus,
      provider: "XENDIT",
      providerReference: data.payment_id ?? payment.providerReference ?? "",
      providerCaptureId: data.capture_id ?? data.captures?.[0]?.capture_id ?? payment.providerCaptureId ?? "",
      failureCode: eventName === "payment.failure" ? data.failure_code ?? "" : payment.failureCode ?? "",
      updatedAt: now,
      completedAt: finalStatus === "SUCCEEDED" ? payment.completedAt ?? now : payment.completedAt ?? null,
    };
    return {
      ...root,
      payments: { ...root.payments, [paymentId]: nextPayment },
      paymentWebhookEvents: {
        ...events,
        [eventKey]: {
          eventId: eventKey, event: eventName, paymentId, paymentRequestId,
          providerPaymentId: data.payment_id ?? "", captureId: data.capture_id ?? data.captures?.[0]?.capture_id ?? "",
          amountMinor, currency: data.currency ?? "PHP", status: finalStatus, processedAt: now,
        },
      },
    };
  }, undefined, false);

  const root = result.snapshot.val() as Record<string, any> | null;
  const storedEvent = root?.paymentWebhookEvents?.[eventKey];
  if (storedEvent) return { accepted: !rejected, duplicate: !result.committed, status: storedEvent.status };
  return { accepted: !rejected, duplicate: false };
}

/** Customer-authenticated callable: creates or reuses the one Xendit GCash request for this payment. */
export const createXenditGcashCheckout = onCall(
  { enforceAppCheck: true, secrets: [xenditSecretKey] },
  async (call) => {
    const uid = call.auth?.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in before starting GCash checkout.");
    const paymentId = String(call.data?.paymentId ?? "");
    if (!safeId(paymentId)) throw new HttpsError("invalid-argument", "A valid payment ID is required.");
    const db = getDatabase();
    const paymentRef = db.ref(`payments/${paymentId}`);
    const snapshot = await paymentRef.get();
    if (!snapshot.exists()) throw new HttpsError("not-found", "Payment was not found.");
    const payment = snapshot.val() as Record<string, any>;
    if (payment.payerId !== uid) throw new HttpsError("permission-denied", "This payment belongs to another customer.");
    if (payment.method !== "GCASH" || payment.provider !== "XENDIT" || payment.currency !== "PHP"
      || !Number.isSafeInteger(payment.amountMinor) || payment.amountMinor <= 0) {
      throw new HttpsError("failed-precondition", "This payment is not a valid Xendit GCash payment.");
    }
    if (payment.status === "SUCCEEDED") {
      return { paymentId, status: "SUCCEEDED", amountMinor: payment.amountMinor, currency: "PHP", checkoutUrl: "" };
    }
    if (payment.status !== "PENDING" && payment.status !== "PROCESSING") {
      throw new HttpsError("failed-precondition", "This payment is no longer available for checkout.");
    }
    if (payment.providerRequestId && payment.checkoutUrl) {
      return {
        paymentId, status: payment.status, amountMinor: payment.amountMinor, currency: "PHP",
        checkoutUrl: payment.checkoutUrl, providerRequestId: payment.providerRequestId,
      };
    }

    const secretKey = xenditSecretKey.value();
    if (secretKey.startsWith("xnd_production_") && livePaymentsEnabled.value().toLowerCase() !== "true") {
      throw new HttpsError("failed-precondition", "Live Xendit payments are disabled until Labmda's live collection approval is confirmed.");
    }
    let created: Awaited<ReturnType<typeof createXenditGcashPaymentRequest>>;
    try {
      created = await createXenditGcashPaymentRequest({
        paymentId, amountMinor: payment.amountMinor, secretKey,
      });
    } catch (error) {
      throw new HttpsError("unavailable", error instanceof Error ? error.message : "Xendit checkout is unavailable.");
    }

    const now = Date.now();
    const update = await paymentRef.transaction((current: Record<string, any> | null) => {
      if (!current || current.id !== paymentId || current.payerId !== uid
        || current.provider !== "XENDIT" || current.method !== "GCASH"
        || current.amountMinor !== payment.amountMinor
        || (current.status !== "PENDING" && current.status !== "PROCESSING")) return;
      if (current.providerRequestId && current.providerRequestId !== created.paymentRequestId) return;
      const nextStatus = created.status === "SUCCEEDED" ? "SUCCEEDED"
        : created.status === "FAILED" ? "FAILED" : "PROCESSING";
      return {
        ...current, status: nextStatus,
        providerRequestId: created.paymentRequestId,
        providerBusinessId: created.businessId,
        providerEnvironment: secretKey.startsWith("xnd_production_") ? "LIVE" : "TEST",
        checkoutUrl: created.checkoutUrl,
        providerReference: current.providerReference ?? "",
        updatedAt: now,
        completedAt: nextStatus === "SUCCEEDED" ? current.completedAt ?? now : current.completedAt ?? null,
      };
    }, undefined, false);
    const saved = update.snapshot.val() as Record<string, any> | null;
    if (!saved || saved.providerRequestId !== created.paymentRequestId) {
      throw new HttpsError("aborted", "The payment changed while Xendit checkout was being created. Refresh payment status before retrying.");
    }
    return {
      paymentId, status: saved.status, amountMinor: saved.amountMinor, currency: "PHP",
      checkoutUrl: saved.checkoutUrl ?? "", providerRequestId: saved.providerRequestId,
    };
  },
);

/** Public HTTPS endpoint; Xendit's x-callback-token is checked before parsing or applying state. */
export const xenditPaymentWebhook = onRequest(
  { secrets: [xenditWebhookToken], cors: false },
  async (request, response) => {
    if (request.method !== "POST") {
      response.status(405).send("Method not allowed");
      return;
    }
    const supplied = request.get("x-callback-token");
    if (!verifyXenditCallbackToken(xenditWebhookToken.value(), supplied)) {
      response.status(401).send("Unauthorized");
      return;
    }
    try {
      const event = request.body as XenditWebhook;
      const result = await processXenditPaymentWebhook(getDatabase(), event);
      if (!result.accepted) {
        console.error("Authenticated Xendit event did not match a pending Labmda payment.");
      }
      response.status(200).send("OK");
    } catch (error) {
      console.error("Xendit payment webhook processing failed.", error);
      response.status(500).send("Webhook processing failed");
    }
  },
);
