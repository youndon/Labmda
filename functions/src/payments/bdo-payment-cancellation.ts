import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";

const db = () => getDatabase();
const idPattern = /^[A-Za-z0-9_-]{1,128}$/;

/**
 * A customer may request cancellation after a BDO link is attached.
 * This records the request and alerts administrators, but deliberately does
 * not cancel the order/payment: the external BDO link remains payable until
 * an administrator verifies closure in the BDO portal.
 */
export const requestBdoCheckoutCancellation = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = call.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to request cancellation.");
  const transactionType = call.data?.transactionType;
  const transactionId = call.data?.transactionId;
  if ((transactionType !== "ORDER" && transactionType !== "RIDE")
    || typeof transactionId !== "string" || !idPattern.test(transactionId)) {
    throw new HttpsError("invalid-argument", "A valid order or ride reference is required.");
  }
  const transactionPath = transactionType === "ORDER" ? "orders" : "trips";
  const result = await db().ref().transaction((root: Record<string, any> | null) => {
    if (!root) return;
    const transaction = root[transactionPath]?.[transactionId] as Record<string, any> | undefined;
    if (!transaction || transaction.status !== "PENDING_PAYMENT") return;
    const ownerId = transactionType === "ORDER" ? transaction.clientId : transaction.passengerId;
    if (ownerId !== uid) return;
    const paymentId = transaction.paymentId;
    if (typeof paymentId !== "string" || !idPattern.test(paymentId)) return;
    const payment = root.payments?.[paymentId] as Record<string, any> | undefined;
    if (!payment || payment.id !== paymentId || payment.payerId !== uid
      || payment.provider !== "BDO_CHECKOUT" || payment.method !== "GCASH"
      || payment.currency !== "PHP" || !Number.isSafeInteger(payment.amountMinor) || payment.amountMinor <= 0
      || !["PENDING", "PROCESSING"].includes(payment.status)
      || typeof payment.checkoutUrl !== "string" || !payment.checkoutUrl.startsWith("https://checkout.bdo.com.ph/")) return;
    const currentRequest = payment.cancellationRequest as Record<string, any> | undefined;
    if (currentRequest?.status === "REQUESTED") return root;
    const now = Date.now();
    const nextPayment = {
      ...payment,
      cancellationRequest: { status: "REQUESTED", requestedAt: now, requestedBy: uid },
      updatedAt: now,
    };
    const nextTransaction = {
      ...transaction, cancellationRequested: true, cancellationRequestedAt: now,
      cancellationReason: "CUSTOMER_REQUESTED_BDO_CHECKOUT_CANCELLATION",
      updatedAtTimestamp: now,
    };
    return {
      ...root,
      payments: { ...root.payments, [paymentId]: nextPayment },
      [transactionPath]: { ...root[transactionPath], [transactionId]: nextTransaction },
    };
  }, undefined, false);
  const root = result.snapshot.val() as Record<string, any> | null;
  const transaction = root?.[transactionPath]?.[transactionId] as Record<string, any> | undefined;
  const paymentId = transaction?.paymentId;
  const payment = typeof paymentId === "string" ? root?.payments?.[paymentId] : undefined;
  if (!payment || payment.cancellationRequest?.status !== "REQUESTED") {
    throw new HttpsError("failed-precondition", "This transaction cannot request cancellation. It may already be paid, closed, or no longer eligible.");
  }
  return {
    paymentId,
    status: "CANCELLATION_REQUESTED",
    duplicate: !result.committed,
    message: "Your request was sent. The BDO payment link must be verified as closed before Labmda can cancel this transaction.",
  };
});
