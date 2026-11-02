import { describe, expect, it } from "vitest";
import {
  assertBdoCheckoutReady,
  BdoCheckoutNotReadyError,
  type BdoCheckoutReadiness,
} from "./bdo-checkout-contract.js";

const ready: BdoCheckoutReadiness = {
  enabled: true,
  merchantApproved: true,
  approvalReference: "BDO-APPROVAL-REF",
  merchantAccountId: "BDO-MERCHANT-ID",
  contractVersion: "merchant-pack-v1",
  sandboxCredentialsConfigured: true,
  checkoutCreationConfigured: true,
  paymentStatusVerificationConfigured: true,
  webhookVerificationConfigured: true,
  gcashOnlyMethodConfigurationConfirmed: true,
  sandboxEndToEndTestPassed: true,
};

describe("BDO Checkout readiness gate", () => {
  it("accepts a fully approved and verified integration", () => {
    expect(assertBdoCheckoutReady(ready)).toBe(ready);
  });

  it("fails closed by default when any production prerequisite is missing", () => {
    const blocked = { ...ready, enabled: false, webhookVerificationConfigured: false };
    try {
      assertBdoCheckoutReady(blocked);
      throw new Error("Expected readiness check to fail.");
    } catch (error) {
      expect(error).toBeInstanceOf(BdoCheckoutNotReadyError);
      expect((error as BdoCheckoutNotReadyError).missingRequirements).toEqual([
        "explicit admin enablement",
        "documented webhook authentication and verification",
      ]);
    }
  });

  it("requires confirmation that checkout can be restricted to GCash", () => {
    expect(() => assertBdoCheckoutReady({
      ...ready,
      gcashOnlyMethodConfigurationConfirmed: false,
    })).toThrow(/restricted to GCash/);
  });

  it("requires server-side credentials and authoritative status verification", () => {
    expect(() => assertBdoCheckoutReady({
      ...ready,
      sandboxCredentialsConfigured: false,
      paymentStatusVerificationConfigured: false,
    })).toThrow(/server-side sandbox credentials, authoritative payment-status verification/);
  });

  it("requires a passing sandbox end-to-end test", () => {
    expect(() => assertBdoCheckoutReady({
      ...ready,
      sandboxEndToEndTestPassed: false,
    })).toThrow(/successful end-to-end sandbox test/);
  });
});
