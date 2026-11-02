import type { Database } from "firebase-admin/database";

export type BalanceBucket = "AVAILABLE" | "PENDING" | "CASH_OWED" | "CLEARING";
export type FinancialEventType =
  | "CUSTOMER_PAYMENT"
  | "MERCHANT_EARNING"
  | "DRIVER_EARNING"
  | "COURIER_EARNING"
  | "PLATFORM_REVENUE"
  | "CASH_COLLECTION"
  | "PAYOUT"
  | "REFUND"
  | "ADJUSTMENT";

export interface LedgerPosting {
  accountId: string;
  bucket: BalanceBucket;
  /** Signed PHP centavos. Negative is a debit; positive is a credit. */
  deltaMinor: number;
  /** Identifies the real-world source or destination of this posting. */
  partyType: "CUSTOMER" | "PLATFORM" | "MERCHANT" | "DRIVER" | "COURIER" | "PAYMENT_PROVIDER" | "CASH";
  partyId: string;
}

export interface FinancialOperation {
  operationId: string;
  eventType: FinancialEventType;
  referenceType: "ORDER" | "RIDE" | "PAYMENT" | "PAYOUT" | "REFUND" | "MANUAL";
  referenceId: string;
  currency: "PHP";
  /** Stable idempotency key supplied by the trusted caller. */
  idempotencyKey: string;
  createdAt: number;
  description: string;
  postings: LedgerPosting[];
}

interface AccountBalance {
  availableMinor: number;
  pendingMinor: number;
  cashOwedMinor: number;
  clearingMinor: number;
  updatedAt: number;
}
interface StoredOperation extends FinancialOperation {
  entries: Array<LedgerPosting & { entryId: string; createdAt: number; operationId: string }>;
}
interface FinanceState {
  operations?: Record<string, StoredOperation>;
  entries?: Record<string, StoredOperation["entries"][number]>;
  accounts?: Record<string, AccountBalance>;
  idempotency?: Record<string, string>;
}

const MAX_SAFE = Number.MAX_SAFE_INTEGER;
const EVENT_TYPES: readonly FinancialEventType[] = [
  "CUSTOMER_PAYMENT", "MERCHANT_EARNING", "DRIVER_EARNING", "COURIER_EARNING",
  "PLATFORM_REVENUE", "CASH_COLLECTION", "PAYOUT", "REFUND", "ADJUSTMENT",
];
const BUCKETS: readonly BalanceBucket[] = ["AVAILABLE", "PENDING", "CASH_OWED", "CLEARING"];
const PARTY_TYPES: readonly LedgerPosting["partyType"][] = [
  "CUSTOMER", "PLATFORM", "MERCHANT", "DRIVER", "COURIER", "PAYMENT_PROVIDER", "CASH",
];
const REFERENCE_TYPES: readonly FinancialOperation["referenceType"][] = [
  "ORDER", "RIDE", "PAYMENT", "PAYOUT", "REFUND", "MANUAL",
];
const BUCKET_FIELD: Record<BalanceBucket, keyof AccountBalance> = {
  AVAILABLE: "availableMinor",
  PENDING: "pendingMinor",
  CASH_OWED: "cashOwedMinor",
  CLEARING: "clearingMinor",
};

function validKey(value: string, field: string): void {
  if (!value || value.length > 180 || [".", "#", "$", "[", "]", "/"].some((part) => value.includes(part))) {
    throw new Error(`${field} must be a non-empty Firebase-safe identifier.`);
  }
}

export function validateFinancialOperation(operation: FinancialOperation): void {
  validKey(operation.operationId, "operationId");
  validKey(operation.idempotencyKey, "idempotencyKey");
  validKey(operation.referenceId, "referenceId");
  if (!EVENT_TYPES.includes(operation.eventType)) throw new Error("Unsupported financial event type.");
  if (!REFERENCE_TYPES.includes(operation.referenceType)) throw new Error("Unsupported financial reference type.");
  if (operation.currency !== "PHP") throw new Error("Ledger currency must be PHP.");
  if (!Number.isSafeInteger(operation.createdAt) || operation.createdAt <= 0) throw new Error("createdAt must be a positive epoch-millisecond timestamp.");
  if (!operation.description.trim()) throw new Error("description is required.");
  if (!Array.isArray(operation.postings) || operation.postings.length < 2) throw new Error("A financial operation requires at least two postings.");
  const total = operation.postings.reduce((sum, posting) => {
    validKey(posting.accountId, "accountId");
    if (!BUCKETS.includes(posting.bucket)) throw new Error("Unsupported balance bucket.");
    if (!PARTY_TYPES.includes(posting.partyType)) throw new Error("Unsupported financial party type.");
    validKey(posting.partyId, "partyId");
    if (!Number.isSafeInteger(posting.deltaMinor) || posting.deltaMinor === 0 || Math.abs(posting.deltaMinor) > MAX_SAFE) {
      throw new Error("Each posting delta must be a non-zero safe integer in PHP centavos.");
    }
    if (!posting.partyId.trim()) throw new Error("Every posting must identify its source or destination party.");
    return sum + BigInt(posting.deltaMinor);
  }, 0n);
  if (total !== 0n) throw new Error("Financial operation is unbalanced: debits and credits must net to zero.");
}

function sameOperation(a: StoredOperation, b: FinancialOperation): boolean {
  const { entries: _entries, ...storedOperation } = a;
  return JSON.stringify(storedOperation) === JSON.stringify(b);
}

/**
 * Atomically appends an immutable operation and updates its wallet projections.
 * The finance root transaction deliberately favors correctness for this foundation;
 * high-volume deployments can shard this transaction after introducing a durable
 * transactional journal/outbox without changing the operation contract.
 */
export async function postFinancialOperation(
  database: Database,
  operation: FinancialOperation,
): Promise<{ operationId: string; duplicate: boolean }> {
  validateFinancialOperation(operation);
  const result = await database.ref("finance").transaction((raw: FinanceState | null) => {
    const state: FinanceState = raw ?? {};
    const operations = state.operations ?? {};
    const entries = state.entries ?? {};
    const accounts = state.accounts ?? {};
    const idempotency = state.idempotency ?? {};
    const priorId = idempotency[operation.idempotencyKey];

    if (priorId) return state;
    if (operations[operation.operationId]) return state;

    const nextEntries = { ...entries };
    const nextAccounts = { ...accounts };
    const storedEntries: StoredOperation["entries"] = [];

    operation.postings.forEach((posting, index) => {
      const entryId = `${operation.operationId}_${index + 1}`;
      if (nextEntries[entryId]) throw new Error("Ledger entry ID collision.");
      const entry = { ...posting, entryId, operationId: operation.operationId, createdAt: operation.createdAt };
      storedEntries.push(entry);
      nextEntries[entryId] = entry;

      const previous = nextAccounts[posting.accountId] ?? {
        availableMinor: 0, pendingMinor: 0, cashOwedMinor: 0, clearingMinor: 0, updatedAt: operation.createdAt,
      };
      const field = BUCKET_FIELD[posting.bucket];
      const updated = previous[field] + posting.deltaMinor;
      if (!Number.isSafeInteger(updated)) throw new Error("Account balance exceeds the safe integer range.");
      if (posting.bucket === "CASH_OWED" && updated < 0) throw new Error("Cash remittance exceeds the collector cash-owed balance.");
      nextAccounts[posting.accountId] = { ...previous, [field]: updated, updatedAt: operation.createdAt };
    });

    const stored: StoredOperation = { ...operation, entries: storedEntries };
    return {
      ...state,
      operations: { ...operations, [operation.operationId]: stored },
      entries: nextEntries,
      accounts: nextAccounts,
      idempotency: { ...idempotency, [operation.idempotencyKey]: operation.operationId },
    };
  }, undefined, false);

  const state = result.snapshot.val() as FinanceState | null;
  const existing = state?.operations?.[operation.operationId];
  const priorId = state?.idempotency?.[operation.idempotencyKey];
  if (priorId === operation.operationId && existing && sameOperation(existing, operation)) {
    return { operationId: operation.operationId, duplicate: !result.committed };
  }
  if (priorId || existing) throw new Error("Idempotency or operation ID was reused with different financial data.");
  throw new Error("Financial operation was not committed.");
}

export interface ReconciliationResult {
  balanced: boolean;
  checkedEntries: number;
  discrepancies: Array<{ accountId: string; bucket: BalanceBucket; ledgerMinor: number; projectionMinor: number }>;
}

/** Rebuild account balances from immutable entries and compare with cached projections. */
export async function reconcileFinancialLedger(database: Database): Promise<ReconciliationResult> {
  const snapshot = await database.ref("finance").get();
  const state = (snapshot.val() ?? {}) as FinanceState;
  const totals = new Map<string, AccountBalance>();
  const entries = Object.values(state.entries ?? {});

  for (const entry of entries) {
    const current = totals.get(entry.accountId) ?? {
      availableMinor: 0, pendingMinor: 0, cashOwedMinor: 0, clearingMinor: 0, updatedAt: 0,
    };
    const field = BUCKET_FIELD[entry.bucket];
    const next = current[field] + entry.deltaMinor;
    if (!Number.isSafeInteger(next)) throw new Error(`Reconciliation overflow for account ${entry.accountId}.`);
    totals.set(entry.accountId, { ...current, [field]: next, updatedAt: Math.max(current.updatedAt, entry.createdAt) });
  }

  const discrepancies: ReconciliationResult["discrepancies"] = [];
  const accountIds = new Set([...totals.keys(), ...Object.keys(state.accounts ?? {})]);
  const buckets: BalanceBucket[] = ["AVAILABLE", "PENDING", "CASH_OWED", "CLEARING"];
  for (const accountId of accountIds) {
    const ledger = totals.get(accountId);
    const projection = state.accounts?.[accountId];
    for (const bucket of buckets) {
      const field = BUCKET_FIELD[bucket];
      const ledgerMinor = ledger?.[field] ?? 0;
      const projectionMinor = projection?.[field] ?? 0;
      if (ledgerMinor !== projectionMinor) discrepancies.push({ accountId, bucket, ledgerMinor, projectionMinor });
    }
  }
  return { balanced: discrepancies.length === 0, checkedEntries: entries.length, discrepancies };
}

/** Build a balanced two-sided transfer between a source and destination account. */
export function createFinancialTransfer(args: {
  operationId: string;
  eventType: FinancialEventType;
  referenceType: FinancialOperation["referenceType"];
  referenceId: string;
  idempotencyKey: string;
  createdAt: number;
  description: string;
  amountMinor: number;
  source: LedgerPosting;
  destination: LedgerPosting;
}): FinancialOperation {
  if (!Number.isSafeInteger(args.amountMinor) || args.amountMinor <= 0) throw new Error("amountMinor must be a positive safe integer in PHP centavos.");
  return {
    operationId: args.operationId,
    eventType: args.eventType,
    referenceType: args.referenceType,
    referenceId: args.referenceId,
    currency: "PHP",
    idempotencyKey: args.idempotencyKey,
    createdAt: args.createdAt,
    description: args.description,
    postings: [
      { ...args.source, deltaMinor: -args.amountMinor },
      { ...args.destination, deltaMinor: args.amountMinor },
    ],
  };
}
