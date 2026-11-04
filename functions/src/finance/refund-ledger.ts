import type { FinancialOperation, LedgerPosting } from "./ledger.js";

/**
 * Build a proportional reversal of a succeeded payment journal.
 * Largest-remainder allocation keeps each debit/credit side exact to the centavo.
 */
export function buildRefundReversal(args: {
  refundId: string;
  paymentId: string;
  paymentAmountMinor: number;
  refundAmountMinor: number;
  createdAt: number;
  reason: string;
  originalPostings: LedgerPosting[];
  currentBalances?: Record<string, { availableMinor?: number; pendingMinor?: number }>;
}): FinancialOperation {
  const { refundId, paymentId, paymentAmountMinor, refundAmountMinor, originalPostings } = args;
  // Older callers/tests may not have a balance snapshot. Treat missing data as
  // zero known balance so the unrecovered portion is explicitly posted as a
  // negative AVAILABLE clawback rather than crashing or silently disappearing.
  const currentBalances = args.currentBalances ?? {};
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(refundId) || !/^[A-Za-z0-9_-]{1,128}$/.test(paymentId)) {
    throw new Error("Refund and payment IDs must be valid Firebase identifiers.");
  }
  if (!Number.isSafeInteger(paymentAmountMinor) || paymentAmountMinor <= 0
    || !Number.isSafeInteger(refundAmountMinor) || refundAmountMinor <= 0
    || refundAmountMinor > paymentAmountMinor) {
    throw new Error("Refund amounts must be positive PHP-centavo integers not exceeding the payment.");
  }
  if (!Number.isSafeInteger(args.createdAt) || args.createdAt <= 0 || !args.reason.trim()) {
    throw new Error("Refund timestamp and reason are required.");
  }
  const allocate = (items: LedgerPosting[]): number[] => {
    const weightTotal = items.reduce((sum, item) => sum + Math.abs(item.deltaMinor), 0);
    if (!Number.isSafeInteger(weightTotal) || weightTotal !== paymentAmountMinor) {
      throw new Error("Original journal debit and credit totals must each equal the payment amount.");
    }
    const rows = items.map((item, index) => {
      if (!Number.isSafeInteger(item.deltaMinor) || item.deltaMinor === 0) throw new Error("Original journal contains an invalid posting.");
      const numerator = BigInt(Math.abs(item.deltaMinor)) * BigInt(refundAmountMinor);
      return { index, amount: Number(numerator / BigInt(weightTotal)), remainder: numerator % BigInt(weightTotal) };
    });
    const allocated = rows.reduce((sum, row) => sum + row.amount, 0);
    rows.sort((a, b) => a.remainder === b.remainder ? a.index - b.index : a.remainder > b.remainder ? -1 : 1);
    for (let i = 0; i < refundAmountMinor - allocated; i++) rows[i % rows.length].amount += 1;
    rows.sort((a, b) => a.index - b.index);
    return rows.map((row) => row.amount);
  };
  const negative = originalPostings.filter((item) => item.deltaMinor < 0);
  const positive = originalPostings.filter((item) => item.deltaMinor > 0);
  if (!negative.length || !positive.length) throw new Error("Original payment journal must contain both debits and credits.");
  const negativeAmounts = allocate(negative);
  const positiveAmounts = allocate(positive);
  const postings: LedgerPosting[] = [
    ...negative.map((item, index) => ({ ...item, deltaMinor: negativeAmounts[index] })),
  ];
  positive.forEach((item, index) => {
    let remaining = positiveAmounts[index]!;
    if (remaining <= 0) return;
    // Refund the beneficiary's current liability, not the bucket used by the
    // original receipt. Pending is consumed first; released funds are debited
    // from AVAILABLE. Any shortfall becomes an AVAILABLE clawback (negative
    // balance), which blocks later payouts until future earnings repay it.
    const balance = currentBalances[item.accountId] ?? {};
    const pending = Number.isSafeInteger(balance.pendingMinor) ? Math.max(0, balance.pendingMinor!) : 0;
    const available = Number.isSafeInteger(balance.availableMinor) ? Math.max(0, balance.availableMinor!) : 0;
    const pendingDebit = Math.min(remaining, pending);
    if (pendingDebit > 0) {
      postings.push({ ...item, bucket: "PENDING", deltaMinor: -pendingDebit });
      remaining -= pendingDebit;
    }
    const availableDebit = Math.min(remaining, available);
    if (availableDebit > 0) {
      postings.push({ ...item, bucket: "AVAILABLE", deltaMinor: -availableDebit });
      remaining -= availableDebit;
    }
    if (remaining > 0) {
      postings.push({ ...item, bucket: "AVAILABLE", deltaMinor: -remaining });
    }
  });
  if (postings.reduce((sum, item) => sum + BigInt(item.deltaMinor), 0n) !== 0n) {
    throw new Error("Refund reversal did not balance.");
  }
  return {
    operationId: `refund_${refundId}_posted`,
    eventType: "REFUND",
    referenceType: "REFUND",
    referenceId: refundId,
    currency: "PHP",
    idempotencyKey: `refund_${refundId}_posted`,
    createdAt: args.createdAt,
    description: `Refund reversal for payment ${paymentId}: ${args.reason}`,
    postings,
  };
}
