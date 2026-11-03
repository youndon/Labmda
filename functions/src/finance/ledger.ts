import { FieldPath, getFirestore, type QueryDocumentSnapshot } from "firebase-admin/firestore";

export type BalanceBucket = "AVAILABLE" | "PENDING" | "CASH_OWED" | "CLEARING";
export type FinancialEventType = "CUSTOMER_PAYMENT" | "MERCHANT_EARNING" | "DRIVER_EARNING" | "COURIER_EARNING" | "PLATFORM_REVENUE" | "CASH_COLLECTION" | "PAYOUT" | "REFUND" | "ADJUSTMENT";
export interface LedgerPosting {
  accountId: string; bucket: BalanceBucket; deltaMinor: number;
  partyType: "CUSTOMER" | "PLATFORM" | "MERCHANT" | "DRIVER" | "COURIER" | "PAYMENT_PROVIDER" | "CASH";
  partyId: string;
}
export interface FinancialOperation {
  operationId: string; eventType: FinancialEventType;
  referenceType: "ORDER" | "RIDE" | "PAYMENT" | "PAYOUT" | "REFUND" | "MANUAL";
  referenceId: string; currency: "PHP"; idempotencyKey: string; createdAt: number;
  description: string; postings: LedgerPosting[];
}
export interface AccountBalance {
  accountId: string; availableMinor: number; pendingMinor: number;
  cashOwedMinor: number; clearingMinor: number; updatedAt: number;
}
export interface StoredEntry extends LedgerPosting { entryId: string; operationId: string; createdAt: number }
export interface StoredOperation extends FinancialOperation { entries: StoredEntry[] }

const firestore = () => getFirestore();
const buckets: readonly BalanceBucket[] = ["AVAILABLE", "PENDING", "CASH_OWED", "CLEARING"];
const events: readonly FinancialEventType[] = ["CUSTOMER_PAYMENT", "MERCHANT_EARNING", "DRIVER_EARNING", "COURIER_EARNING", "PLATFORM_REVENUE", "CASH_COLLECTION", "PAYOUT", "REFUND", "ADJUSTMENT"];
const parties: readonly LedgerPosting["partyType"][] = ["CUSTOMER", "PLATFORM", "MERCHANT", "DRIVER", "COURIER", "PAYMENT_PROVIDER", "CASH"];
const references: readonly FinancialOperation["referenceType"][] = ["ORDER", "RIDE", "PAYMENT", "PAYOUT", "REFUND", "MANUAL"];
const field: Record<BalanceBucket, "availableMinor" | "pendingMinor" | "cashOwedMinor" | "clearingMinor"> = {
  AVAILABLE: "availableMinor", PENDING: "pendingMinor", CASH_OWED: "cashOwedMinor", CLEARING: "clearingMinor",
};
function validKey(value: string, name: string): void {
  if (typeof value !== "string" || !value.trim() || value.length > 180 || value.includes("/")) throw new Error(`${name} must be a non-empty Firebase-safe identifier.`);
}
function emptyAccount(accountId: string, at: number): AccountBalance {
  return { accountId, availableMinor: 0, pendingMinor: 0, cashOwedMinor: 0, clearingMinor: 0, updatedAt: at };
}
export function validateFinancialOperation(operation: FinancialOperation): void {
  validKey(operation.operationId, "operationId"); validKey(operation.idempotencyKey, "idempotencyKey"); validKey(operation.referenceId, "referenceId");
  if (!events.includes(operation.eventType)) throw new Error("Unsupported financial event type.");
  if (!references.includes(operation.referenceType)) throw new Error("Unsupported financial reference type.");
  if (operation.currency !== "PHP") throw new Error("Ledger currency must be PHP.");
  if (!Number.isSafeInteger(operation.createdAt) || operation.createdAt <= 0) throw new Error("createdAt must be a positive epoch-millisecond timestamp.");
  if (!operation.description?.trim()) throw new Error("description is required.");
  if (!Array.isArray(operation.postings) || operation.postings.length < 2 || operation.postings.length > 100) throw new Error("A financial operation requires 2–100 postings.");
  const total = operation.postings.reduce((sum, posting) => {
    validKey(posting.accountId, "accountId"); validKey(posting.partyId, "partyId");
    if (!buckets.includes(posting.bucket)) throw new Error("Unsupported balance bucket.");
    if (!parties.includes(posting.partyType)) throw new Error("Unsupported financial party type.");
    if (!Number.isSafeInteger(posting.deltaMinor) || posting.deltaMinor === 0) throw new Error("Posting deltas must be non-zero safe integers in PHP centavos.");
    return sum + BigInt(posting.deltaMinor);
  }, 0n);
  if (total !== 0n) throw new Error("Financial operation is unbalanced: debits and credits must net to zero.");
}
function sameOperation(stored: StoredOperation, operation: FinancialOperation): boolean {
  return stored.operationId === operation.operationId && stored.eventType === operation.eventType
    && stored.referenceType === operation.referenceType && stored.referenceId === operation.referenceId
    && stored.currency === operation.currency && stored.idempotencyKey === operation.idempotencyKey
    && stored.createdAt === operation.createdAt && stored.description === operation.description
    && stored.postings.length === operation.postings.length
    && stored.postings.every((posting, index) => {
      const expected = operation.postings[index]!;
      return posting.accountId === expected.accountId && posting.bucket === expected.bucket
        && posting.deltaMinor === expected.deltaMinor && posting.partyType === expected.partyType
        && posting.partyId === expected.partyId;
    });
}

/** Atomically commits the immutable journal, idempotency, balances and provider activity in Firestore. */
export async function postFinancialOperation(operation: FinancialOperation): Promise<{ operationId: string; duplicate: boolean }> {
  validateFinancialOperation(operation);
  const db = firestore();
  const operationRef = db.collection("financialOperations").doc(operation.operationId);
  const idempotencyRef = db.collection("financialIdempotency").doc(operation.idempotencyKey);
  const accountRefs = [...new Set(operation.postings.map((p) => p.accountId))].map((id) => db.collection("financialAccounts").doc(id));
  const entryRefs = operation.postings.map((_, index) => db.collection("financialEntries").doc(`${operation.operationId}_${index + 1}`));
  const providerMap = new Map<string, LedgerPosting[]>();
  for (const posting of operation.postings) {
    if (posting.partyType === "MERCHANT" || posting.partyType === "DRIVER" || posting.partyType === "COURIER") {
      providerMap.set(posting.partyId, [...(providerMap.get(posting.partyId) ?? []), posting]);
    }
  }
  const providerRefs = [...providerMap.keys()].map((uid) => db.collection("financialProviderActivity").doc(uid).collection("operations").doc(operation.operationId));

  return db.runTransaction(async (tx) => {
    const snapshots = await Promise.all([tx.get(operationRef), tx.get(idempotencyRef), ...accountRefs.map((ref) => tx.get(ref))]);
    const prior = snapshots[0]!;
    const idempotency = snapshots[1]!;
    if (prior.exists || idempotency.exists) {
      const saved = prior.data() as StoredOperation | undefined;
      if (idempotency.get("operationId") === operation.operationId && saved && sameOperation(saved, operation)) return { operationId: operation.operationId, duplicate: true };
      throw new Error("Idempotency or operation ID was reused with different financial data.");
    }
    const balances = new Map<string, AccountBalance>();
    accountRefs.forEach((ref, index) => {
      const raw = snapshots[index + 2]!.data() as Partial<AccountBalance> | undefined;
      const account = raw ? {
        accountId: ref.id, availableMinor: Number(raw.availableMinor ?? 0), pendingMinor: Number(raw.pendingMinor ?? 0),
        cashOwedMinor: Number(raw.cashOwedMinor ?? 0), clearingMinor: Number(raw.clearingMinor ?? 0),
        updatedAt: Number(raw.updatedAt ?? operation.createdAt),
      } : emptyAccount(ref.id, operation.createdAt);
      if (![account.availableMinor, account.pendingMinor, account.cashOwedMinor, account.clearingMinor].every(Number.isSafeInteger)) {
        throw new Error(`Account ${ref.id} contains an invalid balance projection.`);
      }
      balances.set(ref.id, account);
    });
    const entries: StoredEntry[] = operation.postings.map((p, i) => ({ ...p, entryId: entryRefs[i]!.id, operationId: operation.operationId, createdAt: operation.createdAt }));
    for (const posting of operation.postings) {
      const balance = balances.get(posting.accountId)!;
      const key = field[posting.bucket];
      const next = balance[key] + posting.deltaMinor;
      if (!Number.isSafeInteger(next)) throw new Error("Account balance exceeds the safe integer range.");
      if (posting.bucket === "CASH_OWED" && next < 0) throw new Error("Cash remittance exceeds the collector cash-owed balance.");
      if (operation.eventType === "PAYOUT" && posting.bucket === "AVAILABLE" && next < 0) throw new Error("Payout exceeds the provider available balance.");
      balance[key] = next; balance.updatedAt = operation.createdAt;
    }
    tx.create(operationRef, { ...operation, entries } satisfies StoredOperation);
    tx.create(idempotencyRef, { operationId: operation.operationId, createdAt: operation.createdAt });
    entryRefs.forEach((ref, i) => tx.create(ref, entries[i]!));
    accountRefs.forEach((ref) => {
      const balance = balances.get(ref.id)!;
      const isCashOwed = ref.id.startsWith("cash_owed_");
      const marker = isCashOwed ? ref.id.lastIndexOf("_merchant_") : -1;
      const collectorId = isCashOwed ? ref.id.slice("cash_owed_".length, marker > 0 ? marker : undefined) : undefined;
      const merchantBeneficiaryId = isCashOwed && marker > 0 ? ref.id.slice(marker + "_merchant_".length) : undefined;
      tx.set(ref, { ...balance, ...(collectorId ? { collectorId } : {}), ...(merchantBeneficiaryId ? { merchantBeneficiaryId } : {}) });
    });
    providerRefs.forEach((ref) => tx.create(ref, { ...operation, postings: providerMap.get(ref.parent.parent!.id) ?? [] }));
    return { operationId: operation.operationId, duplicate: false };
  });
}
export async function getFinancialAccount(id: string): Promise<AccountBalance | null> {
  const doc = await firestore().collection("financialAccounts").doc(id).get();
  return doc.exists ? doc.data() as AccountBalance : null;
}
export async function listFinancialAccounts(): Promise<AccountBalance[]> {
  const snap = await firestore().collection("financialAccounts").get();
  return snap.docs.map((doc) => doc.data() as AccountBalance);
}
export async function getFinancialOperation(id: string): Promise<StoredOperation | null> {
  const doc = await firestore().collection("financialOperations").doc(id).get();
  return doc.exists ? doc.data() as StoredOperation : null;
}
export async function listFinancialOperations(limit = 1000): Promise<StoredOperation[]> {
  const snap = await firestore().collection("financialOperations").orderBy("createdAt", "desc").limit(limit).get();
  return snap.docs.map((doc) => doc.data() as StoredOperation);
}
export async function listProviderOperations(uid: string, limit?: number, startAt?: number): Promise<FinancialOperation[]> {
  const collection = firestore().collection("financialProviderActivity").doc(uid).collection("operations");
  const ordered = (startAt === undefined ? collection : collection.where("createdAt", ">=", startAt))
    .orderBy("createdAt", "desc");
  const snap = await (limit === undefined ? ordered : ordered.limit(limit)).get();
  return snap.docs.map((doc) => doc.data() as FinancialOperation);
}
export async function listCashOwedAccounts(uid?: string, includeMerchantBeneficiary = false): Promise<AccountBalance[]> {
  const query = firestore().collection("financialAccounts").where("accountId", ">=", uid ? `cash_owed_${uid}` : "cash_owed_")
    .where("accountId", "<", uid ? `cash_owed_${uid}\uf8ff` : "cash_owed_\uf8ff");
  const owned = await query.get();
  const accounts = owned.docs.map((doc) => doc.data() as AccountBalance)
    .filter((account) => !uid || account.accountId === `cash_owed_${uid}` || account.accountId.startsWith(`cash_owed_${uid}_merchant_`));
  if (!uid || !includeMerchantBeneficiary) return accounts;
  const merchantAccounts = await firestore().collection("financialAccounts").where("merchantBeneficiaryId", "==", uid).get();
  const byId = new Map(accounts.map((account) => [account.accountId, account]));
  merchantAccounts.docs.forEach((doc) => byId.set(doc.id, doc.data() as AccountBalance));
  return [...byId.values()];
}
export async function listEntriesForAccount(accountId: string): Promise<StoredEntry[]> {
  const snap = await firestore().collection("financialEntries").where("accountId", "==", accountId).get();
  return snap.docs.map((doc) => doc.data() as StoredEntry);
}
export interface ReconciliationResult {
  balanced: boolean; checkedEntries: number;
  discrepancies: Array<{ accountId: string; bucket: BalanceBucket; ledgerMinor: number; projectionMinor: number }>;
}
/** Recomputes account balances from Firestore entries in bounded pages rather than downloading the finance tree. */
export async function reconcileFinancialLedger(): Promise<ReconciliationResult> {
  const db = firestore(); const totals = new Map<string, AccountBalance>(); const collection = db.collection("financialEntries");
  let cursor: QueryDocumentSnapshot | undefined; let checkedEntries = 0;
  while (true) {
    let query = collection.orderBy(FieldPath.documentId()).limit(500);
    if (cursor) query = query.startAfter(cursor);
    const page = await query.get(); if (page.empty) break;
    for (const doc of page.docs) {
      const entry = doc.data() as StoredEntry;
      if (!entry.accountId || !buckets.includes(entry.bucket) || !Number.isSafeInteger(entry.deltaMinor)) throw new Error(`Invalid immutable ledger entry ${doc.id}.`);
      const current = totals.get(entry.accountId) ?? emptyAccount(entry.accountId, 0); const key = field[entry.bucket];
      const next = current[key] + entry.deltaMinor;
      if (!Number.isSafeInteger(next)) throw new Error(`Reconciliation overflow for account ${entry.accountId}.`);
      totals.set(entry.accountId, { ...current, [key]: next, updatedAt: Math.max(current.updatedAt, entry.createdAt) }); checkedEntries++;
    }
    cursor = page.docs[page.docs.length - 1]; if (page.size < 500) break;
  }
  const snapshot = await db.collection("financialAccounts").get();
  const projections = new Map(snapshot.docs.map((doc) => [doc.id, doc.data() as AccountBalance]));
  const ids = new Set([...totals.keys(), ...projections.keys()]); const discrepancies: ReconciliationResult["discrepancies"] = [];
  for (const id of ids) for (const bucket of buckets) {
    const key = field[bucket]; const ledgerMinor = totals.get(id)?.[key] ?? 0; const projectionMinor = projections.get(id)?.[key] ?? 0;
    if (ledgerMinor !== projectionMinor) discrepancies.push({ accountId: id, bucket, ledgerMinor, projectionMinor });
  }
  return { balanced: discrepancies.length === 0, checkedEntries, discrepancies };
}
export function createFinancialTransfer(args: {
  operationId: string; eventType: FinancialEventType; referenceType: FinancialOperation["referenceType"];
  referenceId: string; idempotencyKey: string; createdAt: number; description: string; amountMinor: number;
  source: LedgerPosting; destination: LedgerPosting;
}): FinancialOperation {
  if (!Number.isSafeInteger(args.amountMinor) || args.amountMinor <= 0) throw new Error("amountMinor must be a positive safe integer in PHP centavos.");
  return { operationId: args.operationId, eventType: args.eventType, referenceType: args.referenceType, referenceId: args.referenceId,
    currency: "PHP", idempotencyKey: args.idempotencyKey, createdAt: args.createdAt, description: args.description,
    postings: [{ ...args.source, deltaMinor: -args.amountMinor }, { ...args.destination, deltaMinor: args.amountMinor }] };
}
