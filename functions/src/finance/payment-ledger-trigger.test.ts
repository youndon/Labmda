import { describe, expect, it } from "vitest";
import { shouldPostSucceededPaymentToLedger } from "./payment-ledger-trigger.js";
import { isPermanentPaymentLedgerError, PermanentPaymentLedgerError } from "./payment-ledger-errors.js";

describe("successful-payment ledger trigger eligibility", () => {
  it("does not retry a succeeded payment while courier allocations have not been created", () => {
    expect(shouldPostSucceededPaymentToLedger("PENDING", "SUCCEEDED", false)).toBe(false);
  });

  it("posts a newly succeeded payment once its allocations exist", () => {
    expect(shouldPostSucceededPaymentToLedger("PENDING", "SUCCEEDED", true)).toBe(true);
  });

  it("does not repost an already succeeded payment", () => {
    expect(shouldPostSucceededPaymentToLedger("SUCCEEDED", "SUCCEEDED", true)).toBe(false);
  });

  it("ignores transitions that do not enter SUCCEEDED", () => {
    expect(shouldPostSucceededPaymentToLedger("PENDING", "FAILED", true)).toBe(false);
    expect(shouldPostSucceededPaymentToLedger(null, "PENDING", true)).toBe(false);
  });
});

describe("payment ledger trigger error classification", () => {
  it("identifies deterministic payment/journal validation failures as permanent", () => {
    expect(isPermanentPaymentLedgerError(new PermanentPaymentLedgerError("Allocation currency must be PHP."))).toBe(true);
  });

  it("keeps infrastructure failures retryable", () => {
    expect(isPermanentPaymentLedgerError(new Error("Firestore temporarily unavailable."))).toBe(false);
  });
});
