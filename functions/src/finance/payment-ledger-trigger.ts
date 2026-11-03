import { onValueWritten } from "firebase-functions/v2/database";
import { getDatabase } from "firebase-admin/database";
import { postPaymentReceiptFromDatabase } from "./payment-repository.js";

/**
 * Missing allocations are an expected intermediate state for courier-delivery
 * payments: the courier claim creates them and posts the receipt later.
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

    await postPaymentReceiptFromDatabase(database, paymentId);
  },
);
