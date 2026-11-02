import { onValueWritten } from "firebase-functions/v2/database";
import { getDatabase } from "firebase-admin/database";
import { synchronizePaymentState } from "./payment-state-sync.js";

export const syncPaymentStateToCustomerTransaction = onValueWritten(
  { ref: "/payments/{paymentId}", retry: true },
  async (event) => {
    const before = event.data.before.val() as Record<string, unknown> | null;
    const after = event.data.after.val() as Record<string, unknown> | null;
    if (!after || before?.status === after.status) return;
    await synchronizePaymentState(getDatabase(), event.params.paymentId);
  },
);
