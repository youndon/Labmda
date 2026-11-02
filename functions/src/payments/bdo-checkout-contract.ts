/**
 * Labmda's normalized contract for the future approved BDO Checkout adapter.
 *
 * These are internal domain types, NOT BDO HTTP request/response schemas.
 * Do not implement wire mappings until BDO supplies the merchant-specific
 * Website Plug-in/API and callback specifications.
 */

export interface BdoCheckoutReadiness {
  enabled: boolean;
  merchantApproved: boolean;
  approvalReference: string;
  merchantAccountId: string;
  contractVersion: string;
  sandboxCredentialsConfigured: boolean;
  checkoutCreationConfigured: boolean;
  paymentStatusVerificationConfigured: boolean;
  webhookVerificationConfigured: boolean;
  gcashOnlyMethodConfigurationConfirmed: boolean;
  sandboxEndToEndTestPassed: boolean;
}

export class BdoCheckoutNotReadyError extends Error {
  constructor(readonly missingRequirements: string[]) {
    super(`BDO Checkout is not enabled: ${missingRequirements.join(", ")}.`);
    this.name = "BdoCheckoutNotReadyError";
  }
}

/**
 * Fail closed until merchant approval, the real API contract, server-only
 * credentials, authoritative verification and sandbox tests are all complete.
 */
export function assertBdoCheckoutReady(
  readiness: BdoCheckoutReadiness,
): BdoCheckoutReadiness {
  const missing: string[] = [];
  if (readiness.enabled !== true) missing.push("explicit admin enablement");
  if (readiness.merchantApproved !== true) missing.push("BDO merchant approval");
  if (!readiness.approvalReference.trim()) missing.push("BDO approval reference");
  if (!readiness.merchantAccountId.trim()) missing.push("BDO merchant account ID");
  if (!readiness.contractVersion.trim()) missing.push("verified merchant API contract version");
  if (readiness.sandboxCredentialsConfigured !== true) missing.push("server-side sandbox credentials");
  if (readiness.checkoutCreationConfigured !== true) missing.push("documented checkout creation mapping");
  if (readiness.paymentStatusVerificationConfigured !== true) missing.push("authoritative payment-status verification");
  if (readiness.webhookVerificationConfigured !== true) missing.push("documented webhook authentication and verification");
  if (readiness.gcashOnlyMethodConfigurationConfirmed !== true) missing.push("confirmation that checkout can be restricted to GCash");
  if (readiness.sandboxEndToEndTestPassed !== true) missing.push("successful end-to-end sandbox test");
  if (missing.length) throw new BdoCheckoutNotReadyError(missing);
  return readiness;
}

/** Amounts are PHP centavos. This is an internal command, not a BDO wire schema. */
export interface BdoCheckoutCommand {
  paymentId: string;
  idempotencyKey: string;
  amountMinor: number;
  currency: "PHP";
  returnUrl: string;
  expiresAt: number;
}

export interface BdoCheckoutSession {
  providerCheckoutId: string;
  checkoutUrl: string;
  expiresAt: number;
}

export interface BdoCheckoutWebhookEvent {
  eventId: string;
  paymentId: string;
  providerTransactionId: string;
  amountMinor: number;
  currency: "PHP";
  status: "PENDING" | "SUCCEEDED" | "FAILED" | "EXPIRED";
  occurredAt: number;
}

export interface BdoPaymentVerification {
  paymentId: string;
  providerTransactionId: string;
  amountMinor: number;
  currency: "PHP";
  status: "PENDING" | "SUCCEEDED" | "FAILED";
  verifiedAt: number;
}

/**
 * Implement only against the actual merchant integration pack. The webhook
 * verifier must authenticate the raw body and provider headers before returning
 * a normalized event. Never infer success from a browser redirect.
 */
export interface BdoCheckoutProviderAdapter {
  createCheckout(command: BdoCheckoutCommand): Promise<BdoCheckoutSession>;
  verifyPayment(paymentId: string, providerTransactionId: string): Promise<BdoPaymentVerification>;
  verifyWebhook(
    rawBody: Uint8Array,
    headers: Readonly<Record<string, string | undefined>>,
  ): Promise<BdoCheckoutWebhookEvent>;
}
