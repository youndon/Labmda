import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { confirmBdoGcashPaymentRecord } from "./bdo-payment-confirmation.js";
import { expireBdoGcashPaymentRecord } from "./bdo-payment-expiration.js";
import { recordAdminFinancialAudit } from "../finance/admin-financial.js";

const db = () => getDatabase();
const safeId = /^[A-Za-z0-9_-]{1,128}$/;

export function isBdoCheckoutPaymentRequestUrl(value: string): boolean {
  try {
    const parsed = new URL(value);
    return parsed.protocol === "https:" && parsed.hostname === "checkout.bdo.com.ph" && parsed.pathname.length > 1;
  } catch {
    return false;
  }
}


/**
 * Manual verification bridge for BDO Checkout while BDO's merchant-specific
 * server integration/webhook specification is not available to the project.
 * An administrator must verify the exact paid transaction in BDO Checkout
 * before invoking this callable. This is not a public provider webhook.
 */
export const confirmBdoGcashPayment = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = call.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to continue.");
  if (call.auth?.token?.admin !== true) throw new HttpsError("permission-denied", "Administrator access is required.");

  const paymentId = String(call.data?.paymentId ?? "");
  const transferReference = String(call.data?.transferReference ?? "").trim();
  const amountMinor = call.data?.amountMinor;
  const verifiedMethod = String(call.data?.verifiedMethod ?? "");
  if (verifiedMethod !== "GCASH" || !safeId.test(paymentId) || !/^[A-Za-z0-9_-]{4,120}$/.test(transferReference)
    || !Number.isSafeInteger(amountMinor) || (amountMinor as number) <= 0) {
    throw new HttpsError("invalid-argument", "Confirm that the BDO transaction was paid by GCash, then provide its payment ID, reference, and exact PHP-centavo amount.");
  }

  let result: { paymentId: string; status: string; transferReference: string; duplicate: boolean };
  try {
    result = await confirmBdoGcashPaymentRecord(db(), {
      paymentId, transferReference, amountMinor: amountMinor as number, verifiedBy: uid,
    });
  } catch (error) {
    throw new HttpsError(
      "failed-precondition",
      error instanceof Error ? error.message : "BDO payment confirmation could not be verified.",
    );
  }
  return result;
});


/**
 * Attaches a one-time payment request link manually generated in the BDO
 * Checkout merchant portal. It does not mark the payment successful.
 */
export const attachBdoCheckoutLink = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = call.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to continue.");
  if (call.auth?.token?.admin !== true) throw new HttpsError("permission-denied", "Administrator access is required.");

  const paymentId = String(call.data?.paymentId ?? "");
  const checkoutUrl = String(call.data?.checkoutUrl ?? "").trim();
  const amountMinor = call.data?.amountMinor;
  if (!safeId.test(paymentId) || !Number.isSafeInteger(amountMinor) || (amountMinor as number) <= 0) {
    throw new HttpsError("invalid-argument", "Payment ID and positive PHP-centavo amount are required.");
  }
  if (!isBdoCheckoutPaymentRequestUrl(checkoutUrl)) {
    throw new HttpsError("invalid-argument", "Only a genuine https://checkout.bdo.com.ph payment request link is accepted.");
  }
  const linkKey = Buffer.from(new URL(checkoutUrl).pathname).toString("base64url");

  const result = await db().ref().transaction((root: Record<string, any> | null) => {
    if (root === null) return null;
    const payment = root.payments?.[paymentId] as Record<string, any> | undefined;
    const linkOwner = root.bdoCheckoutLinkIndex?.[linkKey];
    if (linkOwner && linkOwner !== paymentId) return;
    if (!payment || payment.method !== "GCASH" || payment.provider !== "BDO_CHECKOUT"
      || payment.currency !== "PHP" || payment.amountMinor !== amountMinor
      || (payment.status !== "PENDING" && payment.status !== "PROCESSING")) return;
    if (payment.checkoutUrl === checkoutUrl && payment.checkoutAmountMinor === amountMinor) return root;
    const now = Date.now();
    const version = Number.isSafeInteger(payment.checkoutLinkVersion) ? payment.checkoutLinkVersion + 1 : 1;
    const history = { ...(payment.checkoutLinkHistory ?? {}) };
    if (payment.checkoutUrl) {
      history[String(version - 1)] = {
        checkoutUrl: payment.checkoutUrl,
        createdAt: payment.checkoutLinkCreatedAt ?? null,
        replacedAt: now,
        replacedBy: uid,
      };
    }
    const nextPayment = {
      ...payment, checkoutUrl, checkoutAmountMinor: amountMinor,
      checkoutLinkVersion: version, checkoutLinkHistory: history,
      checkoutLinkCreatedAt: now, checkoutLinkCreatedBy: uid, updatedAt: now,
    };
    const nextRoot: Record<string, any> = {
      ...root,
      payments: { ...root.payments, [paymentId]: nextPayment },
      bdoCheckoutLinkIndex: { ...(root.bdoCheckoutLinkIndex ?? {}), [linkKey]: paymentId },
    };
    if (payment.transactionType === "ORDER" && payment.orderId) {
      const order = root.orders?.[payment.orderId];
      if (!order || order.paymentId !== paymentId || order.status !== "PENDING_PAYMENT") return;
      nextRoot.orders = { ...root.orders, [payment.orderId]: { ...order, checkoutUrl, updatedAtTimestamp: now } };
    } else if (payment.transactionType === "RIDE" && payment.tripId) {
      const trip = root.trips?.[payment.tripId];
      if (!trip || trip.paymentId !== paymentId || trip.status !== "PENDING_PAYMENT") return;
      nextRoot.trips = { ...root.trips, [payment.tripId]: { ...trip, checkoutUrl, updatedAtTimestamp: now } };
    } else {
      return;
    }
    return nextRoot;
  }, undefined, false);

  const current = result.snapshot.val() as Record<string, any> | null;
  const saved = current?.payments?.[paymentId];
  if (!saved || saved.checkoutUrl !== checkoutUrl || saved.checkoutAmountMinor !== amountMinor) {
    throw new HttpsError("failed-precondition", "The BDO Checkout link could not be attached to this pending payment.");
  }
  return { paymentId, checkoutUrl, status: saved.status, duplicate: !result.committed };
});


/**
 * Records an expiry only after an administrator confirms in the BDO merchant
 * portal that the attached payment request is expired, voided or no longer
 * payable. No automatic expiry is inferred from elapsed time.
 */
export const expireBdoGcashPayment = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = call.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to continue.");
  if (call.auth?.token?.admin !== true) throw new HttpsError("permission-denied", "Administrator access is required.");

  const paymentId = String(call.data?.paymentId ?? "");
  const closureReference = String(call.data?.closureReference ?? "").trim();
  const reason = String(call.data?.reason ?? "").trim();
  let result: Awaited<ReturnType<typeof expireBdoGcashPaymentRecord>>;
  try {
    result = await expireBdoGcashPaymentRecord(db(), {
      paymentId, closureReference, reason, expiredBy: uid,
    });
  } catch (error) {
    throw new HttpsError(
      "failed-precondition",
      error instanceof Error ? error.message : "BDO payment expiry could not be verified.",
    );
  }
  if (!result.duplicate) {
    await recordAdminFinancialAudit({
      uid, action: "BDO_GCASH_PAYMENT_EXPIRED", targetType: "PAYMENT",
      targetId: paymentId, after: { ...result, reason },
    });
  }
  return result;
});
