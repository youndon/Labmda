import { createHash } from "node:crypto";
import { getDatabase } from "firebase-admin/database";
import { getMessaging } from "firebase-admin/messaging";
import { onValueWritten } from "firebase-functions/v2/database";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { HttpsError, onCall } from "firebase-functions/v2/https";

type PaymentRecord = Record<string, any>;
const db = () => getDatabase();

export function classifyBdoPaymentNotification(
  before: PaymentRecord | null,
  after: PaymentRecord | null,
): { kind: string; title: string; body: string } | null {
  if (!after || after.provider !== "BDO_CHECKOUT" || after.method !== "GCASH") return null;
  if (after.checkoutUrl && before?.checkoutUrl !== after.checkoutUrl) {
    return { kind: "CHECKOUT_READY", title: "GCash checkout is ready", body: "Your payment link is ready. Complete checkout, then return to Labmda for verification." };
  }
  if (before?.cancellationRequest?.requestedAt !== after.cancellationRequest?.requestedAt
    && after.cancellationRequest?.requestedAt) {
    return { kind: "CANCELLATION_REQUESTED", title: "GCash cancellation requested", body: "A customer asked to cancel a payment link. Verify and close the request in the BDO portal before cancelling it in Labmda." };
  }
  if (before?.status !== after.status) {
    const messages: Record<string, { title: string; body: string }> = {
      SUCCEEDED: { title: "GCash payment confirmed", body: "Your GCash payment has been verified by Labmda." },
      EXPIRED: { title: "GCash payment closed", body: "The GCash payment request was verified as closed. Your order or ride has been cancelled." },
      CANCELLED: { title: "GCash payment cancelled", body: "Your GCash payment was cancelled." },
      FAILED: { title: "GCash payment failed", body: "The payment was not confirmed. Contact support before paying again." },
      PARTIALLY_REFUNDED: { title: "GCash refund updated", body: "A partial refund has been recorded for your payment." },
      REFUNDED: { title: "GCash payment refunded", body: "Your refund has been recorded." },
    };
    const message = messages[String(after.status)];
    if (message) return { kind: String(after.status), ...message };
  }
  return null;
}

async function sendToUser(uid: string, eventId: string, title: string, body: string, data: Record<string, string>) {
  const tokenSnapshot = await db().ref(`users/${uid}/fcmTokens`).get();
  const tokens = Object.values((tokenSnapshot.val() ?? {}) as Record<string, { token?: string }>)
    .map((row) => row?.token).filter((token): token is string => typeof token === "string" && token.length > 20);
  await db().ref(`paymentNotifications/${uid}/${eventId}`).set({
    ...data, title, body, createdAt: Date.now(), readAt: null,
  });
  if (tokens.length) {
    const result = await getMessaging().sendEachForMulticast({
      tokens, notification: { title, body }, data,
      android: { priority: "high", notification: { channelId: "order_status_channel" } },
    });
    const invalid = result.responses.flatMap((response, index) =>
      !response.success && ["messaging/registration-token-not-registered", "messaging/invalid-registration-token"].includes(response.error?.code ?? "")
        ? [tokens[index]] : []);
    if (invalid.length) {
      const updates: Record<string, null> = {};
      for (const token of invalid) updates[`users/${uid}/fcmTokens/${createHash("sha256").update(token).digest("hex")}`] = null;
      await db().ref().update(updates);
    }
  }
}

async function sendToAdmins(eventId: string, title: string, body: string, data: Record<string, string>) {
  await db().ref(`adminPaymentNotifications/${eventId}`).set({
    ...data, title, body, createdAt: Date.now(), readAt: null,
  });
  const snapshot = await db().ref("adminFcmTokens").get();
  const tokens = Object.values((snapshot.val() ?? {}) as Record<string, Record<string, { token?: string }>>)
    .flatMap((rows) => Object.values(rows ?? {}).map((row) => row?.token))
    .filter((token): token is string => typeof token === "string" && token.length > 20);
  if (tokens.length) {
    await getMessaging().sendEachForMulticast({
      tokens, notification: { title, body }, data,
      android: { priority: "high", notification: { channelId: "order_status_channel" } },
    });
  }
}

export const notifyBdoPaymentChanges = onValueWritten(
  { ref: "/payments/{paymentId}", retry: true },
  async (event) => {
    const before = event.data.before.val() as PaymentRecord | null;
    const after = event.data.after.val() as PaymentRecord | null;
    const notice = classifyBdoPaymentNotification(before, after);
    if (!notice || !after) return;
    const paymentId = event.params.paymentId;
    const eventId = `bdo_${paymentId}_${notice.kind}_${String(after.updatedAt ?? after.checkoutLinkCreatedAt ?? "event")}`;
    const claim = await db().ref(`paymentNotificationEvents/${eventId}`).transaction((current) => current ?? {
      paymentId, kind: notice.kind, createdAt: Date.now(),
    }, undefined, false);
    if (!claim.committed) return;
    const data = { type: "PAYMENT_UPDATE", paymentId, transactionType: String(after.transactionType ?? ""), transactionId: String(after.orderId || after.tripId || "") };
    const payerId = typeof after.payerId === "string" ? after.payerId : "";
    if (payerId) await sendToUser(payerId, eventId, notice.title, notice.body, data);
    await sendToAdmins(eventId, notice.title, notice.body, data);
  },
);

export const remindOverdueBdoCheckoutPayments = onSchedule("every 1 hours", async () => {
  const now = Date.now();
  const reminderAgeMs = 24 * 60 * 60 * 1000;
  const reminderCooldownMs = 12 * 60 * 60 * 1000;
  const snapshot = await db().ref("payments").orderByChild("provider").equalTo("BDO_CHECKOUT").get();
  const payments = (snapshot.val() ?? {}) as Record<string, PaymentRecord>;
  for (const [paymentId, payment] of Object.entries(payments)) {
    if (payment.method !== "GCASH" || !["PENDING", "PROCESSING"].includes(payment.status)
      || typeof payment.checkoutUrl !== "string" || !payment.checkoutUrl.startsWith("https://checkout.bdo.com.ph/")) continue;
    const linkedAt = Number(payment.checkoutLinkCreatedAt ?? payment.updatedAt ?? payment.createdAt);
    if (!Number.isSafeInteger(linkedAt) || now - linkedAt < reminderAgeMs) continue;
    const stateRef = db().ref(`bdoPaymentReminderState/${paymentId}`);
    const claim = await stateRef.transaction((current: { lastSentAt?: number } | null) => {
      if (current?.lastSentAt && now - current.lastSentAt < reminderCooldownMs) return;
      return { lastSentAt: now, reminderCount: (current?.reminderCount ?? 0) + 1 };
    }, undefined, false);
    if (!claim.committed) continue;
    const eventId = `bdo_${paymentId}_overdue_${Math.floor(now / reminderCooldownMs)}`;
    const data = { type: "PAYMENT_REMINDER", paymentId, transactionType: String(payment.transactionType ?? ""), transactionId: String(payment.orderId || payment.tripId || "") };
    const title = "GCash payment link needs review";
    const body = "This BDO checkout link has been pending for over 24 hours. Check its status in the BDO portal; Labmda will not expire it unless BDO confirms it is no longer payable.";
    if (typeof payment.payerId === "string") await sendToUser(payment.payerId, eventId, "GCash payment is still pending", "Your payment has not yet been verified. Please do not pay again; Labmda will update you when its status is confirmed.", data);
    await sendToAdmins(eventId, title, body, data);
  }
});

/** Store a device token server-side; clients cannot write token registries directly. */
export const registerFcmToken = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = call.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to register notifications.");
  const token = call.data?.token;
  if (typeof token !== "string" || token.length < 40 || token.length > 4096 || /\s/.test(token)) {
    throw new HttpsError("invalid-argument", "A valid FCM registration token is required.");
  }
  const tokenId = createHash("sha256").update(token).digest("hex");
  const record = { token, updatedAt: Date.now(), platform: "ANDROID" };
  await db().ref(`users/${uid}/fcmTokens/${tokenId}`).set(record);
  if (call.auth.token.admin === true) await db().ref(`adminFcmTokens/${uid}/${tokenId}`).set(record);
  return { registered: true };
});
