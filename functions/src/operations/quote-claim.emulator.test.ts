import { describe, expect, it } from "vitest";
import { claimQuote } from "./quote-operations.js";
import { createRtdbEmulatorContext } from "../testing/rtdb-emulator.js";

const emulatorOnly = !!process.env.FIREBASE_DATABASE_EMULATOR_HOST;

describe.skipIf(!emulatorOnly)("RTDB emulator: quote claim null-cache retry", () => {
  it("retries the initial null callback with the server quote before claiming it", async () => {
    const { database, instrumentReference, observed, cleanup } = await createRtdbEmulatorContext({
      pricing: {
        quotes: {
          quote_emulator: {
            uid: "customer_1", kind: "FOOD", status: "QUOTED",
            expiresAt: Date.now() + 60_000, sourceId: "restaurant_1",
          },
        },
      },
    });
    try {
      const reference = instrumentReference(database.ref("pricing/quotes/quote_emulator"));
      const result = await claimQuote(reference, "customer_1", "FOOD");
      expect(observed[0]).toBeNull();
      expect(observed.some((value: any) => value?.uid === "customer_1" && value?.status === "QUOTED")).toBe(true);
      expect(result.committed).toBe(true);
      expect(result.quote.status).toBe("CREATING");
    } finally {
      await cleanup();
    }
  });
});
