/** Deterministic data/validation failures must not be retried as transient infrastructure errors. */
export class PermanentPaymentLedgerError extends Error {
  readonly code = "PERMANENT_PAYMENT_LEDGER_ERROR";

  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = "PermanentPaymentLedgerError";
  }
}

export function isPermanentPaymentLedgerError(error: unknown): boolean {
  if (error instanceof PermanentPaymentLedgerError) return true;
  if (!error || typeof error !== "object") return false;
  const candidate = error as { code?: unknown };
  return candidate.code === "PERMANENT_PAYMENT_LEDGER_ERROR";
}
