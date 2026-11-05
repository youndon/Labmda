import { onValueWritten } from "firebase-functions/v2/database";
import { getDatabase } from "firebase-admin/database";
import { postPaymentReceiptFromDatabase } from "./payment-repository.js";
import { isPermanentPaymentLedgerError } from "./payment-ledger-errors.js";

/**
 * Every GCash transaction is created with its known merchant/platform
 * allocations before checkout. The unassigned courier/driver share is left
 * out of allocations so the receipt adapter posts it to CLEARING.
 * Cash remains claim/collection-driven because its receipt requires a named
 * collector and allocations that exactly match the collected amount.
 */
export function shouldPostSucceededPaymentToLedger(
  before: unknown,
  after: unknown,
  allocationsExist: boolean,
): boolean {
  return after === "SUCCEEDED" && before !== "SUCCEEDED" && allocationsExist;
}

/**
 * Posts every trusted successful payment to the immutable finance journal.
 *
 * This trigger deliberately loads the payment and allocations again from the
 * database. It never accepts client-supplied amounts or beneficiary splits.
 * The journal idempotency key makes trigger retries and the cash callable's
 * eager post safe to run concurrently.
 */
export const postSucceededPaymentToLedger = onValueWritten(
  { ref: "/payments/{paymentId}/status", retry: true },
  async (event) => {
    const before = event.data.before.val();
    const after = event.data.after.val();
    const paymentId = event.params.paymentId;
    const database = getDatabase();
    const allocations = await database.ref(`paymentAllocations/${paymentId}`).get();
    if (!shouldPostSucceededPaymentToLedger(before, after, allocations.exists())) return;

    try {
      await postPaymentReceiptFromDatabase(database, paymentId);
    } catch (error) {
      if (isPermanentPaymentLedgerError(error)) {
        console.error("Permanent payment-ledger validation failure; manual repair required.", {
          paymentId,
          code: "PERMANENT_PAYMENT_LEDGER_ERROR",
          message: error instanceof Error ? error.message : String(error),
        });
        return;
      }
      throw error;
    }
  },
);
