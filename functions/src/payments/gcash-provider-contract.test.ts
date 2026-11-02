import { describe, expect, it } from "vitest";
import {
  assertGCashCapabilityReady,
  GCashIntegrationNotReadyError,
  type GCashIntegrationReadiness,
} from "./gcash-provider-contract.js";

const capability = {
  enabled: true,
  providerApproved: true,
  approvalReference: "GCASH-APPROVAL-001",
  merchantAccountId: "merchant_123",
  contractVersion: "merchant-api-v1",
  credentialsConfigured: true,
  statusVerificationConfigured: true,
};
const readiness: GCashIntegrationReadiness = {
  collection: { ...capability },
  disbursement: { ...capability },
};

describe("GCash capability readiness gate", () => {
  it("allows collection only when all requirements are configured", () => {
    expect(assertGCashCapabilityReady(readiness, "COLLECTION").merchantAccountId).toBe("merchant_123");
  });
  it("fails closed when provider approval is absent", () => {
    const config = { ...readiness, collection: { ...capability, providerApproved: false } };
    expect(() => assertGCashCapabilityReady(config, "COLLECTION")).toThrow(GCashIntegrationNotReadyError);
  });
  it("requires authoritative status verification", () => {
    const config = { ...readiness, collection: { ...capability, statusVerificationConfigured: false } };
    expect(() => assertGCashCapabilityReady(config, "COLLECTION")).toThrow("authoritative transaction-status verification");
  });
  it("does not infer disbursement approval from collection approval", () => {
    const config = { collection: { ...capability }, disbursement: { ...capability, providerApproved: false } };
    expect(() => assertGCashCapabilityReady(config, "COLLECTION")).not.toThrow();
    expect(() => assertGCashCapabilityReady(config, "DISBURSEMENT")).toThrow("GCash provider approval");
  });
  it("requires a verified API contract and server-side credentials", () => {
    const config = { ...readiness, disbursement: { ...capability, contractVersion: "", credentialsConfigured: false } };
    try {
      assertGCashCapabilityReady(config, "DISBURSEMENT");
      throw new Error("Expected readiness check to fail.");
    } catch (error) {
      expect(error).toBeInstanceOf(GCashIntegrationNotReadyError);
      expect((error as GCashIntegrationNotReadyError).missingRequirements).toContain("verified API contract version");
      expect((error as GCashIntegrationNotReadyError).missingRequirements).toContain("server-side API credentials");
    }
  });
});
