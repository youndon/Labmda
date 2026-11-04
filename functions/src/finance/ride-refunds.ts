import { createHash } from "node:crypto";

export type AutomaticRideRefundCase = {
  id: string;
  paymentId: string;
  tripId: string;
  payerId: string;
  amountMinor: number;
  currency: "PHP";
  reason: string;
  status: "REQUESTED";
  requestedBy: "system";
  requestedByRole: "SYSTEM";
  origin: "UNACCEPTED_RIDE_TIMEOUT";
  createdAt: number;
  updatedAt: number;
};

/** Stable identity makes scheduled retries safe and prevents duplicate reservations. */
export function buildAutomaticRideRefundCase(args: {
  tripId: string;
  paymentId: string;
  payerId: string;
  amountMinor: number;
  createdAt: number;
}): AutomaticRideRefundCase {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(args.tripId)
    || !/^[A-Za-z0-9_-]{1,128}$/.test(args.paymentId)
    || !/^[A-Za-z0-9_-]{1,128}$/.test(args.payerId)
    || !Number.isSafeInteger(args.amountMinor) || args.amountMinor <= 0
    || !Number.isSafeInteger(args.createdAt) || args.createdAt <= 0) {
    throw new Error("Automatic ride refund details are invalid.");
  }
  const suffix = createHash("sha256").update(args.tripId).digest("hex").slice(0, 32);
  const id = "ride_timeout_" + suffix;
  return {
    id, paymentId: args.paymentId, tripId: args.tripId, payerId: args.payerId,
    amountMinor: args.amountMinor, currency: "PHP",
    reason: "No driver accepted the paid ride before the request timeout",
    status: "REQUESTED", requestedBy: "system", requestedByRole: "SYSTEM",
    origin: "UNACCEPTED_RIDE_TIMEOUT", createdAt: args.createdAt, updatedAt: args.createdAt,
  };
}
