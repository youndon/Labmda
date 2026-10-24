
function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" ? value as Record<string, unknown> : null;
}

type StripeIntent = {
  id?: string; client_secret?: string; status?: string; capture_method?: string;
  amount?: number; amount_capturable?: number; amount_received?: number; currency?: string;
  latest_charge?: string | { id?: string } | null; error?: { message?: string };
};

async function stripeApi<T>(
  secretKey: string,
  path: string,
  form?: URLSearchParams,
  idempotencyKey?: string
): Promise<{ ok: boolean; data: T }> {
  const headers: Record<string, string> = { Authorization: "Bearer " + secretKey };
  if (form) headers["Content-Type"] = "application/x-www-form-urlencoded";
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
  const response = await fetch("https://api.stripe.com/v1/" + path, { method: form ? "POST" : "GET", headers, body: form });
  return { ok: response.ok, data: await response.json() as T };
}

function getStripeChargeId(intent: StripeIntent): string {
  const charge = intent.latest_charge;
  if (typeof charge === "string") return charge;
  const chargeRecord = asRecord(charge);
  return typeof chargeRecord?.id === "string" ? chargeRecord.id : "";
}

async function getStripeIntent(secretKey: string, intentId: string): Promise<StripeIntent> {
  const { ok, data } = await stripeApi<StripeIntent>(secretKey, "payment_intents/" + encodeURIComponent(intentId));
  if (!ok || data.id !== intentId) throw new HttpsError("failed-precondition", "Stripe could not verify this payment.");
  return data;
}

/**
 * Cancels a Stripe PaymentIntent that has not been authorized yet, so switching method or amount
 * can never leave two live authorizations for one order. Authorized intents are never cancelled here.
 */
async function cancelOpenStripeIntent(secretKey: string, intentId: string): Promise<void> {
  const intent = await getStripeIntent(secretKey, intentId);
  if (intent.status === "canceled") return;
  if (!OPEN_STRIPE_INTENT_STATUSES.has(String(intent.status))) {
    throw new HttpsError("failed-precondition", "The current Stripe payment is already authorized or processing.");
  }
  const cancelled = await stripeApi<StripeIntent>(
    secretKey, "payment_intents/" + encodeURIComponent(intentId) + "/cancel", new URLSearchParams()
  );
  if (!cancelled.ok || cancelled.data.status !== "canceled") {
    throw new HttpsError("failed-precondition", "Stripe could not cancel the current payment safely.");
  }
}

/** Maps a provider-verified manual-capture PaymentIntent to the local payment state. */
async function applyStripeIntentState(
  orderId: string,
  context: PaymentContext,
  secretKey: string,
  intentId: string,
  expectedMinorUnits: number,
  expectedCurrency: string,
  cancelIfOpen: boolean
): Promise<{ accepted: boolean; message: string }> {
  const intent = await getStripeIntent(secretKey, intentId);
  if (intent.amount !== expectedMinorUnits || String(intent.currency ?? "").toUpperCase() !== expectedCurrency) {
    throw new HttpsError("failed-precondition", "The Stripe amount or currency does not match the server payment.");
  }
  const now = Date.now();
  if (intent.status === "requires_capture") {
    if (intent.amount_capturable !== expectedMinorUnits) {
      throw new HttpsError("failed-precondition", "Stripe's authorized amount does not match the server payment.");
    }
    await setPaymentStatus(orderId, context, "AUTHORIZED", {
      providerPaymentId: intentId, authorizedMinorUnits: expectedMinorUnits, authorizedAtEpochMillis: now,
      checkoutUrl: "", expiresAtEpochMillis: 0
    });
    return { accepted: true, message: "Stripe authorized the payment. It is captured only after fulfilment." };
  }
  if (intent.status === "succeeded") {
    if (intent.amount_received !== expectedMinorUnits) {
      throw new HttpsError("failed-precondition", "Stripe's received amount does not match the server payment.");
    }
    const chargeId = getStripeChargeId(intent) || intentId;
    await setPaymentStatus(orderId, context, "SUCCEEDED", {
      providerPaymentId: intentId, providerReceiptId: chargeId, verifiedAtEpochMillis: now
    });
    return { accepted: true, message: "Stripe confirmed the payment." };
  }
  if (intent.status === "processing") {
    await setPaymentStatus(orderId, context, "PROCESSING");
    return { accepted: true, message: "Stripe is still processing the payment." };
  }
  if (intent.status === "canceled") {
    await setPaymentStatus(orderId, context, "CANCELLED", { checkoutUrl: "", expiresAtEpochMillis: 0 });
    return { accepted: true, message: "The Stripe payment was cancelled." };
  }
  if (cancelIfOpen) {
    await cancelOpenStripeIntent(secretKey, intentId);
    await setPaymentStatus(orderId, context, "CANCELLED", { checkoutUrl: "", expiresAtEpochMillis: 0 });
    return { accepted: true, message: "Provider confirmed no authorization; checkout was cancelled." };
  }
  throw new HttpsError("failed-precondition", "Stripe has not authorized this payment yet.");
}

export const verifyPaymentReturn = onCall({
  enforceAppCheck: true,
  secrets: [stripeSecretKey, paypalClientId, paypalClientSecret]
}, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before verifying a payment.");
  const body = request.data as { orderId?: string; provider?: string; sessionId?: string; resultToken?: string };
  const orderId = typeof body.orderId === "string" ? body.orderId.trim() : "";
  const provider = normalizePaymentMethod(body.provider);
  const sessionId = typeof body.sessionId === "string" ? body.sessionId.trim() : "";
  const resultToken = typeof body.resultToken === "string" ? body.resultToken.trim() : "";
  if (!orderId || (provider !== "STRIPE" && provider !== "PAYPAL") || !resultToken) {
    throw new HttpsError("invalid-argument", "Invalid provider return.");
  }
  const context = await getOwnedPaymentContext(orderId, uid);
  const payment = context.payment;
  const paymentProvider = normalizePaymentMethod(payment.provider ?? payment.method);
  if (paymentProvider !== provider) {
    throw new HttpsError("permission-denied", "This provider does not match the payment method.");
  }
  const currentStatus = String(payment.status ?? "PENDING");
  if (currentStatus === "SUCCEEDED") return { accepted: true, message: "Payment already verified." };
  if (currentStatus === "REFUNDED" || currentStatus === "PARTIALLY_REFUNDED") {
    throw new HttpsError("failed-precondition", "This payment has already been refunded.");
  }
  const recordedSessionId = String(payment.providerPaymentId ?? "");
  const amount = asRecord(payment.amount);
  const expectedMinorUnits = Number(amount?.minorUnits ?? 0);
  const expectedCurrency = String(amount?.currencyCode ?? "USD").toUpperCase();
  if (!Number.isSafeInteger(expectedMinorUnits) || expectedMinorUnits <= 0) {
    throw new HttpsError("failed-precondition", "The server payment amount is invalid.");
  }
  // The cancel redirect is only a request. Query the provider before changing the state.
  if (resultToken === "cancel") {
    if (!recordedSessionId) {
      await setPaymentStatus(orderId, context, "CANCELLED", { checkoutUrl: "", expiresAtEpochMillis: 0 });
      return { accepted: true, message: "Checkout was cancelled before a provider session was created." };
    }
    if (provider === "STRIPE" && recordedSessionId.startsWith("pi_")) {
      const key = stripeSecretKey.value().trim();
      if (!key) throw new HttpsError("failed-precondition", "Stripe server credentials are not configured.");
      return applyStripeIntentState(orderId, context, key, recordedSessionId, expectedMinorUnits, expectedCurrency, true);
    }
    // remaining logic unchanged
    await setPaymentStatus(orderId, context, "CANCELLED", { checkoutUrl: "", expiresAtEpochMillis: 0 });
    return { accepted: true, message: "Provider confirmed no successful payment; checkout was cancelled." };
  }

  if (!recordedSessionId || sessionId !== recordedSessionId || resultToken !== recordedSessionId) {
    throw new HttpsError("permission-denied", "The returned session does not match this payment.");
  }

  if (provider === "STRIPE" && recordedSessionId.startsWith("pi_")) {
    const key = stripeSecretKey.value().trim();
    if (!key) throw new HttpsError("failed-precondition", "Stripe server credentials are not configured.");
    return applyStripeIntentState(orderId, context, key, recordedSessionId, expectedMinorUnits, expectedCurrency, false);
  }

  // ... rest of function is unchanged
  return { accepted: true, message: "Payment verification completed." };
});
