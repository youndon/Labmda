import type { PaymentStatus } from "../models/payment.js";

/**
 * Shared payment state machine. Provider adapters must normalize provider
 * events into these states before a persisted payment can be changed.
 */
const ALLOWED_TRANSITIONS: Record<PaymentStatus, readonly PaymentStatus[]> = {
  PENDING: ["PROCESSING", "SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"],
  PROCESSING: ["SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"],
  SUCCEEDED: ["PARTIALLY_REFUNDED", "REFUNDED"],
  FAILED: [],
  CANCELLED: [],
  EXPIRED: [],
  PARTIALLY_REFUNDED: ["PARTIALLY_REFUNDED", "REFUNDED"],
  REFUNDED: [],
};

/** Same-state retries are safe no-ops; terminal states cannot be reopened. */
export function canTransitionPaymentStatus(from: PaymentStatus, to: PaymentStatus): boolean {
  return from === to || ALLOWED_TRANSITIONS[from].includes(to);
}

export function assertPaymentStatusTransition(from: PaymentStatus, to: PaymentStatus): void {
  if (!canTransitionPaymentStatus(from, to)) {
    throw new Error(`Invalid payment status transition: ${from} -> ${to}.`);
  }
}

export function isFinalPaymentStatus(status: PaymentStatus): boolean {
  return status === "SUCCEEDED" || status === "FAILED" || status === "CANCELLED"
    || status === "EXPIRED" || status === "REFUNDED";
}
