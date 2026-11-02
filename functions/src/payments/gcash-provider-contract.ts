/**
 * Provider-neutral internal contract for the future approved GCash integration.
 *
 * These are Labmda domain contracts, not GCash API request/response schemas.
 * Do not map them to HTTP fields until GCash supplies the merchant API specification.
 */
export type GCashCapability = "COLLECTION" | "DISBURSEMENT";

export interface GCashCapabilityReadiness {
  enabled: boolean;
  providerApproved: boolean;
  approvalReference: string;
  merchantAccountId: string;
  contractVersion: string;
  credentialsConfigured: boolean;
  statusVerificationConfigured: boolean;
}

export interface GCashIntegrationReadiness {
  collection: GCashCapabilityReadiness;
  disbursement: GCashCapabilityReadiness;
}

export class GCashIntegrationNotReadyError extends Error {
  constructor(
    readonly capability: GCashCapability,
    readonly missingRequirements: string[],
  ) {
    super(`GCash ${capability.toLowerCase()} is not enabled: ${missingRequirements.join(", ")}.`);
    this.name = "GCashIntegrationNotReadyError";
  }
}

/**
 * Fails closed until the exact capability is approved, configured and verified.
 * Collection and disbursement approvals are independent; approval for one never
 * enables the other.
 */
export function assertGCashCapabilityReady(
  readiness: GCashIntegrationReadiness,
  capability: GCashCapability,
): GCashCapabilityReadiness {
  const config = readiness[capability === "COLLECTION" ? "collection" : "disbursement"];
  const missing: string[] = [];
  if (config.enabled !== true) missing.push("admin enablement");
  if (config.providerApproved !== true) missing.push("GCash provider approval");
  if (!config.approvalReference.trim()) missing.push("provider approval reference");
  if (!config.merchantAccountId.trim()) missing.push("GCash merchant account");
  if (!config.contractVersion.trim()) missing.push("verified API contract version");
  if (config.credentialsConfigured !== true) missing.push("server-side API credentials");
  if (config.statusVerificationConfigured !== true) missing.push("authoritative transaction-status verification");
  if (missing.length) throw new GCashIntegrationNotReadyError(capability, missing);
  return config;
}

/** Internal checkout input. Amounts are PHP centavos, never floating-point pesos. */
export interface GCashCheckoutCommand {
  paymentId: string;
  idempotencyKey: string;
  amountMinor: number;
  currency: "PHP";
  returnUrl: string;
  expiresAt: number;
}

/** Normalized result from an approved provider adapter; not a raw GCash payload. */
export interface GCashCheckoutResult {
  providerCheckoutId: string;
  checkoutUrl: string;
  expiresAt: number;
}

/** A successful status must only be returned after authoritative provider verification. */
export interface GCashCollectionVerification {
  providerTransactionId: string;
  paymentId: string;
  amountMinor: number;
  currency: "PHP";
  status: "PENDING" | "SUCCEEDED" | "FAILED";
  verifiedAt: number;
}

export interface GCashRefundCommand {
  paymentId: string;
  providerTransactionId: string;
  refundId: string;
  idempotencyKey: string;
  amountMinor: number;
  currency: "PHP";
  reason: string;
}

export interface GCashRefundResult {
  providerRefundId: string;
  status: "PENDING" | "SUCCEEDED" | "FAILED";
}

export interface GCashPayoutCommand {
  payoutId: string;
  idempotencyKey: string;
  recipientMobileNumber: string;
  recipientAccountName: string;
  amountMinor: number;
  currency: "PHP";
}

export interface GCashPayoutResult {
  providerPayoutId: string;
  status: "PENDING" | "SUCCEEDED" | "FAILED";
}

/**
 * Implement this interface only from GCash's approved merchant documentation.
 * The adapter must verify amounts, currency, references and transaction status;
 * it must never treat a browser redirect or client callback as payment evidence.
 */
export interface GCashProviderAdapter {
  createCheckout(command: GCashCheckoutCommand): Promise<GCashCheckoutResult>;
  verifyCollection(paymentId: string, providerTransactionId: string): Promise<GCashCollectionVerification>;
  createRefund(command: GCashRefundCommand): Promise<GCashRefundResult>;
  createPayout(command: GCashPayoutCommand): Promise<GCashPayoutResult>;
  verifyPayout(payoutId: string, providerPayoutId: string): Promise<GCashPayoutResult>;
}
