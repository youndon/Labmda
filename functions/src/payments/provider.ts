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
