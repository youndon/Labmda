import { describe, expect, it } from "vitest";
import {
  createFinancialTransfer,
  validateFinancialOperation,
} from "./ledger.js";

describe("financial ledger invariants", () => {
  const base = {
    operationId: "payment_order_123",
    eventType: "CUSTOMER_PAYMENT" as const,
    referenceType: "PAYMENT" as const,
    referenceId: "payment_123",
    idempotencyKey: "xendit:payment_123:succeeded",
    createdAt: 1_798_000_000_000,
    description: "Customer GCash payment received",
    amountMinor: 12_345,
    source: { accountId: "clearing_xendit", bucket: "CLEARING" as const, deltaMinor: 0, partyType: "XENDIT" as const, partyId: "xendit" },
    destination: { accountId: "platform_receivable", bucket: "PENDING" as const, deltaMinor: 0, partyType: "PLATFORM" as const, partyId: "platform" },
  };

  it("creates a balanced PHP-centavo transfer with source and destination", () => {
    const operation = createFinancialTransfer(base);
    expect(operation.currency).toBe("PHP");
    expect(operation.postings.map((posting) => posting.deltaMinor)).toEqual([-12_345, 12_345]);
    expect(() => validateFinancialOperation(operation)).not.toThrow();
  });

  it("rejects an unbalanced operation", () => {
    const operation = createFinancialTransfer(base);
    operation.postings[1]!.deltaMinor += 1;
    expect(() => validateFinancialOperation(operation)).toThrow(/unbalanced/i);
  });

  it("rejects zero, negative, fractional, and unsafe transfer amounts", () => {
    for (const amountMinor of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
      expect(() => createFinancialTransfer({ ...base, amountMinor })).toThrow();
    }
  });

  it("rejects Firebase-unsafe account identifiers", () => {
    const operation = createFinancialTransfer({
      ...base,
      source: { ...base.source, accountId: "merchant/a" },
    });
    expect(() => validateFinancialOperation(operation)).toThrow(/Firebase-safe/i);
  });

  it("rejects non-PHP currency and empty party references", () => {
    const operation = createFinancialTransfer(base);
    expect(() => validateFinancialOperation({ ...operation, currency: "USD" as "PHP" })).toThrow(/PHP/);
    const invalid = createFinancialTransfer({
      ...base,
      destination: { ...base.destination, partyId: "" },
    });
    expect(() => validateFinancialOperation(invalid)).toThrow(/party/i);
  });
});
