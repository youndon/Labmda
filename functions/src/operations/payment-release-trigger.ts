import { onValueWritten } from "firebase-functions/v2/database";
import { releaseCompletedPaymentAllocations } from "./transaction-operations.js";

type CompletedServiceRecord = {
  status?: unknown;
  paymentId?: unknown;
};

function paymentIdOf(value: CompletedServiceRecord | null): string {
  return typeof value?.paymentId === "string" ? value.paymentId.trim() : "";
}

/** Retry failed payment releases when an order first becomes DELIVERED. */
export const releaseOrderAllocationsOnDelivered = onValueWritten(
  { ref: "/orders/{orderId}", retry: true },
  async (event) => {
    const before = event.data.before.val() as CompletedServiceRecord | null;
    const after = event.data.after.val() as CompletedServiceRecord | null;
    if (after?.status !== "DELIVERED" || before?.status === "DELIVERED") return;
    const paymentId = paymentIdOf(after);
    if (paymentId) await releaseCompletedPaymentAllocations(paymentId);
  },
);

/** Retry failed payment releases when a ride first becomes COMPLETED. */
export const releaseRideAllocationsOnCompleted = onValueWritten(
  { ref: "/trips/{tripId}", retry: true },
  async (event) => {
    const before = event.data.before.val() as CompletedServiceRecord | null;
    const after = event.data.after.val() as CompletedServiceRecord | null;
    if (after?.status !== "COMPLETED" || before?.status === "COMPLETED") return;
    const paymentId = paymentIdOf(after);
    if (paymentId) await releaseCompletedPaymentAllocations(paymentId);
  },
);
