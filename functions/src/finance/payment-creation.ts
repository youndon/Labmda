import { getDatabase, type DataSnapshot } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { randomUUID } from "node:crypto";
import type { Payment, PaymentAllocation, PaymentBeneficiaryType, PaymentTransactionType } from "../models/payment.js";

type QuoteRecord = {
  quoteId: string; uid: string; kind: "FOOD" | "SHOP" | "DELIVERY" | "RIDE";
  currency: "PHP"; expiresAt: number; status: "QUOTED" | "CONSUMING" | "CONSUMED";
  paymentMethod?: string; sourceId?: string; breakdown: Record<string, unknown>;
};
type TransactionRecord = Record<string, unknown> & { clientId?: string; passengerId?: string; sourceId?: string; deliveryId?: string; driverId?: string };

const db = () => getDatabase();
const idPattern = /^[A-Za-z0-9_-]{1,128}$/;

function id(value: unknown, label: string): string {
  if (typeof value !== "string" || !idPattern.test(value)) throw new HttpsError("invalid-argument", `${label} is invalid.`);
  return value;
}
function minor(value: unknown, label: string, allowZero = false): number {
  if (!Number.isSafeInteger(value) || (value as number) < (allowZero ? 0 : 1)) {
    throw new HttpsError("failed-precondition", `${label} is not a valid PHP-centavo amount.`);
  }
  return value as number;
}
function stringField(snapshot: DataSnapshot, keys: string[]): string {
  for (const key of keys) {
    const value = snapshot.child(key).val();
    if (typeof value === "string" && value.trim()) return value.trim();
  }
  return "";
}
function allocation(
  payment: Payment, beneficiaryId: string, beneficiaryType: PaymentBeneficiaryType, amountMinor: number, now: number,
): PaymentAllocation {
  return {
    id: beneficiaryType.toLowerCase(), paymentId: payment.id,
    transactionType: payment.transactionType, orderId: payment.orderId, tripId: payment.tripId,
    deliveryId: payment.deliveryId, beneficiaryId, beneficiaryType, amountMinor,
    currency: "PHP", status: "PENDING", createdAt: now, updatedAt: now,
  };
}

/** Pure server-side split builder; all inputs originate from a stored quote and transaction records. */
export function buildCashAllocations(args: {
  payment: Payment; quoteKind: QuoteRecord["kind"]; breakdown: Record<string, unknown>;
  merchantId?: string; collectorId?: string; now: number;
}): PaymentAllocation[] {
  const { payment, quoteKind, breakdown, now } = args;
  const total = payment.amountMinor;
  let beneficiaryAmounts: Array<{ type: PaymentBeneficiaryType; id: string; amount: number }>;
  if (quoteKind === "FOOD" || quoteKind === "SHOP") {
    if (!args.merchantId) throw new Error("Merchant owner UID is required before payment allocation.");
    const merchant = minor(breakdown.merchantEarningsMinor, "merchant earnings", true);
    const courier = minor(breakdown.courierEarningsMinor, "courier earnings", true);
    const platform = total - merchant - courier;
    if (!Number.isSafeInteger(platform) || platform < 0) {
      throw new Error("Platform-funded discounts exceed the platform commission available for this cash transaction.");
    }
    beneficiaryAmounts = [
      { type: "MERCHANT", id: args.merchantId, amount: merchant },
      { type: "DELIVERY", id: args.collectorId || "UNASSIGNED", amount: courier },
      { type: "PLATFORM", id: "platform", amount: platform },
    ];
  } else if (quoteKind === "DELIVERY") {
    const courier = minor(breakdown.courierEarningsMinor, "courier earnings", true);
    const platform = total - courier;
    if (platform < 0) throw new Error("Delivery allocations exceed the customer payment.");
    beneficiaryAmounts = [
      { type: "DELIVERY", id: args.collectorId || "UNASSIGNED", amount: courier },
      { type: "PLATFORM", id: "platform", amount: platform },
    ];
  } else {
    const driver = minor(breakdown.driverEarningsMinor, "driver earnings", true);
    const platform = total - driver;
    if (platform < 0) throw new Error("Ride allocations exceed the customer payment.");
    beneficiaryAmounts = [
      { type: "DRIVER", id: args.collectorId || "UNASSIGNED", amount: driver },
      { type: "PLATFORM", id: "platform", amount: platform },
    ];
  }
  const positive = beneficiaryAmounts.filter((item) => item.amount > 0);
  if (positive.reduce((sum, item) => sum + item.amount, 0) !== total) {
    throw new Error("Cash allocations must exactly equal the customer payment.");
  }
  return positive.map((item) => allocation(payment, item.id, item.type, item.amount, now));
}

async function loadTransaction(kind: QuoteRecord["kind"], transactionId: string, uid: string, sourceId?: string) {
  const isTrip = kind === "RIDE" || kind === "DELIVERY";
  const path = isTrip ? `trips/${transactionId}` : `orders/${transactionId}`;
  const snapshot = await db().ref(path).get();
  if (!snapshot.exists()) throw new HttpsError("not-found", `The ${isTrip ? "trip" : "order"} must exist before payment creation.`);
  const transaction = snapshot.val() as TransactionRecord;
  const owner = isTrip ? transaction.passengerId : transaction.clientId;
  if (owner !== uid) throw new HttpsError("permission-denied", "This transaction does not belong to the signed-in customer.");
  if (!isTrip && transaction.sourceId !== sourceId) throw new HttpsError("failed-precondition", "Order merchant does not match the quote.");
  if (!isTrip && transaction.status === "CANCELLED") throw new HttpsError("failed-precondition", "Cancelled orders cannot be paid.");
  if (isTrip) {
    const serviceType = String(transaction.serviceType ?? "").toLowerCase();
    if (kind === "DELIVERY" && !serviceType.includes("deliver") && !serviceType.includes("freight")) {
      throw new HttpsError("failed-precondition", "A delivery quote can only pay a delivery or freight trip.");
    }
    if (kind === "RIDE" && (serviceType.includes("deliver") || serviceType.includes("freight"))) {
      throw new HttpsError("failed-precondition", "A ride quote cannot pay a delivery or freight trip.");
    }
    if (transaction.status === "CANCELLED") throw new HttpsError("failed-precondition", "Cancelled trips cannot be paid.");
  }
  return { snapshot, transaction, isTrip };
}

function buildPayment(quote: QuoteRecord, transactionId: string, transaction: TransactionRecord, isTrip: boolean, uid: string, now: number): Payment {
  const breakdown = quote.breakdown;
  const amountMinor = minor(breakdown.customerTotalMinor, "customer total");
  return {
    id: `pay_${quote.quoteId}`, transactionType: isTrip ? "RIDE" : "ORDER",
    orderId: isTrip ? "" : transactionId, tripId: isTrip ? transactionId : "",
    deliveryId: isTrip ? "" : (typeof transaction.deliveryId === "string" ? transaction.deliveryId : ""),
    payerId: uid, method: "CASH", status: "PENDING", amountMinor, currency: "PHP",
    refundedAmountMinor: 0, createdAt: now, updatedAt: now, completedAt: null,
  };
}

export const createCashPaymentFromQuote = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = call.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before creating a payment.");
  const quoteId = id(call.data?.quoteId, "Quote ID");
  const transactionId = id(call.data?.transactionId, "Transaction ID");
  const quoteRef = db().ref(`pricing/quotes/${quoteId}`);
  const quoteSnapshot = await quoteRef.get();
  if (!quoteSnapshot.exists()) throw new HttpsError("not-found", "Pricing quote was not found.");
  const quote = quoteSnapshot.val() as QuoteRecord;
  if (quote.quoteId !== quoteId || quote.uid !== uid || quote.currency !== "PHP") {
    throw new HttpsError("permission-denied", "This quote does not belong to you or is invalid.");
  }
  if (quote.paymentMethod !== "CASH") throw new HttpsError("failed-precondition", "Only CASH payment creation is enabled in this phase. GCash will be connected separately.");
  if (quote.status !== "QUOTED" && quote.status !== "CONSUMING" && quote.status !== "CONSUMED") {
    throw new HttpsError("failed-precondition", "This quote is not available for payment.");
  }
  if (quote.status === "QUOTED" && (!Number.isSafeInteger(quote.expiresAt) || quote.expiresAt <= Date.now())) {
    throw new HttpsError("deadline-exceeded", "This quote has expired. Request a new quote.");
  }
  if (!["FOOD", "SHOP", "DELIVERY", "RIDE"].includes(quote.kind)) {
    throw new HttpsError("failed-precondition", "Unsupported quote type.");
  }

  const paymentId = `pay_${quoteId}`;
  const claim = await quoteRef.transaction((current: QuoteRecord | null) => {
    if (!current || current.uid !== uid || current.quoteId !== quoteId) return;
    if (current.status === "QUOTED") {
      if (current.expiresAt <= Date.now()) return;
      return { ...current, status: "CONSUMING", paymentId, transactionId, consumedAt: Date.now() };
    }
    if ((current.status === "CONSUMING" || current.status === "CONSUMED")
      && current.paymentId === paymentId && current.transactionId === transactionId) return current;
    return;
  }, undefined, false);
  const claimed = claim.snapshot.val() as (QuoteRecord & { paymentId?: string; transactionId?: string }) | null;
  if (!claimed || claimed.paymentId !== paymentId || claimed.transactionId !== transactionId) {
    throw new HttpsError("already-exists", "This quote has already been used for another transaction.");
  }

  const { snapshot: transactionSnapshot, transaction, isTrip } = await loadTransaction(quote.kind, transactionId, uid, quote.sourceId);
  const payment = buildPayment(quote, transactionId, transaction, isTrip, uid, Date.now());
  const existingPayment = await db().ref(`payments/${paymentId}`).get();
  if (existingPayment.exists()) {
    const prior = existingPayment.val() as Payment;
    if (prior.payerId !== uid || prior.orderId !== payment.orderId || prior.tripId !== payment.tripId || prior.amountMinor !== payment.amountMinor) {
      throw new HttpsError("already-exists", "A conflicting payment record already exists for this quote.");
    }
    return { paymentId, amountMinor: prior.amountMinor, currency: "PHP", status: prior.status, duplicate: true };
  }

  let merchantId = "";
  if (quote.kind === "FOOD" || quote.kind === "SHOP") {
    const sourceRoot = quote.kind === "FOOD" ? "restaurants" : "shops";
    const source = await db().ref(`${sourceRoot}/${quote.sourceId}`).get();
    if (!source.exists()) throw new HttpsError("not-found", "Merchant account was not found.");
    merchantId = stringField(source, ["ownerUid", "ownerId", "userId", "managerUid", "createdBy"]);
    if (!merchantId) throw new HttpsError("failed-precondition", "Merchant owner UID is missing. Add ownerUid to the merchant record before enabling cash checkout.");
    if (quote.kind === "SHOP" && source.child("status").val() !== "APPROVED") {
      throw new HttpsError("failed-precondition", "Only approved shops can receive customer payments.");
    }
  }
  const now = Date.now();
  const allocations = buildCashAllocations({
    payment, quoteKind: quote.kind, breakdown: quote.breakdown,
    merchantId, collectorId: isTrip ? (typeof transaction.driverId === "string" ? transaction.driverId : "") : payment.deliveryId,
    now,
  });
  const updates: Record<string, unknown> = {
    [`payments/${paymentId}`]: payment,
    [`pricing/quotes/${quoteId}/status`]: "CONSUMED",
    [`pricing/quotes/${quoteId}/consumedAt`]: now,
    [`pricing/quotes/${quoteId}/paymentId`]: paymentId,
    [`pricing/quotes/${quoteId}/transactionId`]: transactionId,
  };
  for (const item of allocations) updates[`paymentAllocations/${paymentId}/${item.id}`] = item;
  if (isTrip) updates[`trips/${transactionId}/paymentId`] = paymentId;
  else updates[`orders/${transactionId}/paymentId`] = paymentId;
  await db().ref().update(updates);
  return { paymentId, amountMinor: payment.amountMinor, currency: "PHP", status: "PENDING", duplicate: false };
});
