import { createHash } from "node:crypto";

export type AutomaticOrderRefundCase = {
  id: string;
  paymentId: string;
  orderId: string;
  payerId: string;
  amountMinor: number;
  currency: "PHP";
  reason: string;
  status: "REQUESTED";
  requestedBy: string;
  requestedByRole: "CUSTOMER" | "MERCHANT" | "ADMIN";
  origin: "ORDER_CANCELLATION";
  createdAt: number;
  updatedAt: number;
};

/** Deterministic case identity makes retries of the same cancellation idempotent. */
export function buildAutomaticOrderRefundCase(args: {
  orderId: string;
  paymentId: string;
  payerId: string;
  amountMinor: number;
  reason: string;
  requestedBy: string;
  requestedByRole: "CUSTOMER" | "MERCHANT" | "ADMIN";
  createdAt: number;
}): AutomaticOrderRefundCase {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(args.orderId)
    || !/^[A-Za-z0-9_-]{1,128}$/.test(args.paymentId)
    || !/^[A-Za-z0-9_-]{1,128}$/.test(args.payerId)
    || !/^[A-Za-z0-9_-]{1,128}$/.test(args.requestedBy)
    || !["CUSTOMER", "MERCHANT", "ADMIN"].includes(args.requestedByRole)
    || !Number.isSafeInteger(args.amountMinor) || args.amountMinor <= 0
    || !Number.isSafeInteger(args.createdAt) || args.createdAt <= 0
    || !args.reason.trim()) {
    throw new Error("Automatic order refund details are invalid.");
  }
  const suffix = createHash("sha256").update(args.orderId).digest("hex").slice(0, 32);
  const id = "order_cancel_" + suffix;
  return {
    id, paymentId: args.paymentId, orderId: args.orderId, payerId: args.payerId,
    amountMinor: args.amountMinor, currency: "PHP", reason: args.reason.trim().slice(0, 500),
    status: "REQUESTED", requestedBy: args.requestedBy, requestedByRole: args.requestedByRole,
    origin: "ORDER_CANCELLATION", createdAt: args.createdAt, updatedAt: args.createdAt,
  };
}
