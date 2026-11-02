import { createHmac, timingSafeEqual } from "node:crypto";

export type PaymentOutcome = "SUCCEEDED" | "FAILED";
export interface CreatePaymentSessionInput {
  paymentId: string;
  amountMinor: number;
  currency: "PHP";
  payerId: string;
  expiresAt: number;
  returnUrl: string;
}
export interface ProviderSession {
  provider: "SIMULATED";
  providerSessionId: string;
  checkoutUrl: string;
  expiresAt: number;
  simulationOnly: true;
}
export interface PaymentProvider {
  readonly name: string;
  createSession(input: CreatePaymentSessionInput): Promise<ProviderSession>;
}
export interface CreateRefundInput {
  paymentId: string;
  providerSessionId: string;
  amountMinor: number;
  currency: "PHP";
  reason: string;
}
export interface ProviderRefund {
  provider: "SIMULATED";
  providerRefundId: string;
  status: "SIMULATED_SUCCEEDED";
  simulationOnly: true;
}
export interface PaymentRefundProvider {
  createRefund(input: CreateRefundInput): Promise<ProviderRefund>;
}
export interface PaymentSignatureVerifier {
  verify(rawBody: string, signature: string): boolean;
}

export class SimulatedPaymentProvider implements PaymentProvider {
  readonly name = "SIMULATED" as const;
  constructor(private readonly allowSimulation: boolean) {
    if (!allowSimulation) throw new Error("Simulated payments are disabled outside the development emulator.");
  }
  async createSession(input: CreatePaymentSessionInput): Promise<ProviderSession> {
    if (!Number.isSafeInteger(input.amountMinor) || input.amountMinor <= 0 || input.currency !== "PHP") {
      throw new Error("A simulated session requires a positive PHP-centavo amount.");
    }
    const id = `sim_${input.paymentId}`;
    return {
      provider: "SIMULATED",
      providerSessionId: id,
      checkoutUrl: `labmda-dev://payments/${encodeURIComponent(id)}`,
      expiresAt: input.expiresAt,
      simulationOnly: true,
    };
  }
}

export class SimulatedPaymentRefundProvider implements PaymentRefundProvider {
  constructor(private readonly allowSimulation: boolean) {
    if (!allowSimulation) throw new Error("Simulated refunds are disabled outside the development emulator.");
  }
  async createRefund(input: CreateRefundInput): Promise<ProviderRefund> {
    if (!Number.isSafeInteger(input.amountMinor) || input.amountMinor <= 0 || input.currency !== "PHP") {
      throw new Error("A simulated refund requires a positive PHP-centavo amount.");
    }
    return {
      provider: "SIMULATED",
      providerRefundId: `sim_refund_${input.paymentId}_${input.amountMinor}`,
      status: "SIMULATED_SUCCEEDED",
      simulationOnly: true,
    };
  }
}

/** HMAC verifier contract used by provider webhook adapters. Compare signatures in constant time. */
export class HmacPaymentSignatureVerifier implements PaymentSignatureVerifier {
  constructor(private readonly secret: string) {
    if (!secret) throw new Error("Webhook signing secret is required.");
  }
  sign(rawBody: string): string {
    return createHmac("sha256", this.secret).update(rawBody).digest("hex");
  }
  verify(rawBody: string, signature: string): boolean {
    if (!/^[a-f0-9]{64}$/i.test(signature)) return false;
    const expected = Buffer.from(this.sign(rawBody), "hex");
    const supplied = Buffer.from(signature, "hex");
    return expected.length === supplied.length && timingSafeEqual(expected, supplied);
  }
}

/** Explicitly emulator-only guard. Cloud Functions production instances cannot select this provider. */
export function isSimulationRuntime(): boolean {
  return process.env.FUNCTIONS_EMULATOR === "true"
    && process.env.NODE_ENV !== "production"
    && !process.env.K_SERVICE;
}
