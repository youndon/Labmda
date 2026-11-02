export type PayoutTransferState = "APPROVED" | "PAYING";

export class PayoutTransferReferenceError extends Error {
  constructor(
    message: string,
    readonly code: "invalid-argument" | "failed-precondition",
  ) {
    super(message);
    this.name = "PayoutTransferReferenceError";
  }
}

/**
 * Resolve the bank/GCash transfer reference before posting the payout ledger
 * operation. Once a payout enters PAYING, its external reference is immutable:
 * retrying the same request is safe, but changing the evidence is not.
 */
export function resolvePayoutTransferReference(
  status: PayoutTransferState,
  storedValue: unknown,
  submittedValue: unknown,
): string {
  const submitted = typeof submittedValue === "string" ? submittedValue.trim() : "";
  if (submitted.length > 120) {
    throw new PayoutTransferReferenceError("The transfer reference is too long.", "invalid-argument");
  }

  if (status === "APPROVED") {
    if (!submitted) {
      throw new PayoutTransferReferenceError("Enter the completed GCash or BDO transfer reference.", "invalid-argument");
    }
    return submitted;
  }

  const stored = typeof storedValue === "string" ? storedValue.trim() : "";
  if (!stored) {
    throw new PayoutTransferReferenceError(
      "This payout is marked PAYING without a saved transfer reference. Resolve it manually before retrying.",
      "failed-precondition",
    );
  }
  if (submitted && submitted !== stored) {
    throw new PayoutTransferReferenceError(
      "The transfer reference cannot be changed after payout processing has started.",
      "failed-precondition",
    );
  }
  return stored;
}
