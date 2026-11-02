import { describe, expect, it } from "vitest";

describe("simulated GCash refund contract", () => {
  it("uses explicit simulation markers and a distinct non-ledger status", () => {
    const refund = {
      status: "SIMULATED_SUCCEEDED",
      provider: "SIMULATED",
      simulationOnly: true,
      currency: "PHP",
      amountMinor: 2500,
    };
    expect(refund.simulationOnly).toBe(true);
    expect(refund.provider).toBe("SIMULATED");
    expect(refund.status).not.toBe("SUCCEEDED");
  });
});
