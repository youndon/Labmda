import { describe, expect, it } from "vitest";
import { commitSimulatedRefund } from "./refund-transaction.js";
import { createRtdbEmulatorContext } from "../testing/rtdb-emulator.js";

const emulatorOnly = !!process.env.FIREBASE_DATABASE_EMULATOR_HOST;

describe.skipIf(!emulatorOnly)("RTDB emulator: refund null-cache retry", () => {
  it("retries the null root read and atomically records the refund amount and record", async () => {
    const now = Date.now();
    const refund = {
      id: "refund_emulator", sessionId: "session_emulator", paymentId: "payment_emulator",
      requestedBy: "customer_1", amountMinor: 2_500, currency: "PHP" as const,
      reason: "Test refund", status: "SIMULATED_SUCCEEDED", provider: "SIMULATED",
      providerRefundId: "sim_refund_emulator", simulationOnly: true as const,
      createdAt: now, updatedAt: now, completedAt: now,
    };
    const { database, observed, cleanup } = await createRtdbEmulatorContext({
      paymentSessions: {
        session_emulator: {
          payerId: "customer_1", paymentId: "payment_emulator", provider: "SIMULATED",
          simulationOnly: true, status: "SUCCEEDED", amountMinor: 10_000,
          refundedAmountMinor: 0,
        },
      },
    });
    try {
      const result = await commitSimulatedRefund(database, {
        sessionId: "session_emulator", uid: "customer_1", amountMinor: 2_500,
        now, refundId: refund.id, refund,
      });
      expect(observed[0]).toBeNull();
      expect(observed.some((value: any) => value?.paymentSessions?.session_emulator?.status === "SUCCEEDED")).toBe(true);
      expect(result.committed).toBe(true);
      const saved = await database.ref().get();
      expect(saved.child("paymentSessions/session_emulator/refundedAmountMinor").val()).toBe(2_500);
      expect(saved.child("paymentRefunds/refund_emulator/status").val()).toBe("SIMULATED_SUCCEEDED");
    } finally {
      await cleanup();
    }
  });
});
