import { randomUUID } from "node:crypto";
import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { recordAdminFinancialAudit } from "./admin-financial.js";
import { createFinancialTransfer, postFinancialOperation, listEntriesForAccount } from "./ledger.js";

const db = () => getDatabase();
type Direction = "INBOUND" | "OUTBOUND";
type Expected = { id: string; transferReference: string; amountMinor: number; source: "BDO" | "MANUAL"; status: "EXPECTED" | "SETTLED"; createdAt: number; createdBy: string };
type Transfer = { id: string; bank: "BDO"; direction: Direction; transferReference: string; amountMinor: number; currency: "PHP"; transactionAt: number; status: "MATCHED" | "UNMATCHED"; matchedType: "PAYOUT" | "EXPECTED_SETTLEMENT" | null; matchedId: string | null; recordedAt: number; recordedBy: string; note: string };

function admin(call: { auth?: { uid?: string; token?: Record<string, unknown> } | null }): string {
  if (!call.auth?.uid) throw new HttpsError("unauthenticated", "Sign in to continue.");
  if (call.auth.token?.admin !== true) throw new HttpsError("permission-denied", "Administrator access is required.");
  return call.auth.uid;
}
function ref(value: unknown): string {
  const v = typeof value === "string" ? value.trim() : "";
  if (!/^[A-Za-z0-9_-]{4,120}$/.test(v)) throw new HttpsError("invalid-argument", "A valid bank transfer reference is required.");
  return v.toUpperCase();
}
function amount(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) <= 0) throw new HttpsError("invalid-argument", "Amount must be a positive PHP-centavo integer.");
  return value as number;
}

/** Register an expected BDO Checkout/manual settlement into the company's BDO account. */
export const createExpectedBdoSettlement = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = admin(call);
  const data = call.data as { settlementId?: unknown; transferReference?: unknown; amountMinor?: unknown; source?: unknown };
  const id = typeof data.settlementId === "string" ? data.settlementId : randomUUID();
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(id)) throw new HttpsError("invalid-argument", "Settlement ID is invalid.");
  const transferReference = ref(data.transferReference);
  const amountMinor = amount(data.amountMinor);
  const source = data.source === undefined ? "BDO" : data.source;
  if (source !== "BDO" && source !== "MANUAL") throw new HttpsError("invalid-argument", "Source must be BDO or MANUAL.");
  const record: Expected = { id, transferReference, amountMinor, source, status: "EXPECTED", createdAt: Date.now(), createdBy: uid };
  const result = await db().ref("expectedBdoSettlements").transaction((raw: Record<string, Expected> | null) => {
    const current = raw ?? {};
    const prior = current[id];
    if (prior) return prior.transferReference === transferReference && prior.amountMinor === amountMinor && prior.source === source ? current : undefined;
    if (Object.values(current).some((item) => item.transferReference === transferReference)) return;
    return { ...current, [id]: record };
  }, undefined, false);
  const saved = result.snapshot.child(id).val() as Expected | null;
  if (!saved || saved.transferReference !== transferReference || saved.amountMinor !== amountMinor || saved.source !== source) {
    throw new HttpsError("already-exists", "Settlement ID is already registered with different details.");
  }
  if (result.committed) await recordAdminFinancialAudit({ uid, action: "BDO_EXPECTED_SETTLEMENT_CREATED", targetType: "BDO_SETTLEMENT", targetId: id, after: saved });
  return { settlementId: id, status: saved.status, duplicate: !result.committed };
});

/**
 * Record a real BDO statement line. Inbound lines match expected BDO Checkout/manual
 * settlements; outbound lines match already-paid provider payouts. This does
 * not initiate transfers or mark payouts paid.
 */
export const recordBdoBankTransfer = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = admin(call);
  const data = call.data as { transferReference?: unknown; amountMinor?: unknown; direction?: unknown; transactionAt?: unknown; note?: unknown };
  const transferReference = ref(data.transferReference);
  const amountMinor = amount(data.amountMinor);
  if (data.direction !== "INBOUND" && data.direction !== "OUTBOUND") throw new HttpsError("invalid-argument", "Direction must be INBOUND or OUTBOUND.");
  const direction = data.direction as Direction;
  const transactionAt = Number(data.transactionAt);
  if (!Number.isSafeInteger(transactionAt) || transactionAt <= 0) throw new HttpsError("invalid-argument", "A valid bank transaction timestamp is required.");
  const id = `bdo_${transferReference}`;
  const result = await db().ref(`bdoBankTransfers/${id}`).transaction((prior) => {
    if (prior) return prior.direction === direction && prior.amountMinor === amountMinor && prior.transferReference === transferReference ? prior : undefined;
    return {
      id, bank: "BDO", direction, transferReference, amountMinor, currency: "PHP",
      transactionAt, status: "UNMATCHED", matchedType: null, matchedId: null,
      recordedAt: Date.now(), recordedBy: uid, note: String(data.note ?? "").slice(0, 500),
    } satisfies Transfer;
  }, undefined, false);
  let saved = result.snapshot.val() as Transfer | null;
  if (!saved || saved.direction !== direction || saved.amountMinor !== amountMinor) {
    throw new HttpsError("already-exists", "Transfer reference was already recorded with different details.");
  }
  if (saved.status === "UNMATCHED") {
    if (direction === "INBOUND") {
      const matches = await db().ref("expectedBdoSettlements").orderByChild("transferReference").equalTo(transferReference).get();
      const candidate = Object.values((matches.val() ?? {}) as Record<string, Expected>)[0];
      if (candidate && candidate.amountMinor === amountMinor && candidate.status === "EXPECTED") {
        const updated = await db().ref(`expectedBdoSettlements/${candidate.id}`).transaction((current) =>
          current === null ? null : current.status === "EXPECTED" && current.amountMinor === amountMinor
            ? { ...current, status: "SETTLED", settledAt: transactionAt, bankTransferId: id } : undefined, undefined, false);
        if (updated.committed) {
          await db().ref(`bdoBankTransfers/${id}`).update({ status: "MATCHED", matchedType: "EXPECTED_SETTLEMENT", matchedId: candidate.id });
        }
      }
    } else {
      const matches = await db().ref("payoutRequests").orderByChild("transferReference").equalTo(transferReference).get();
      const candidates = Object.values((matches.val() ?? {}) as Record<string, Record<string, unknown>>);
      // Never guess when historical records contain a duplicate reference.
      const candidate = candidates.length === 1 ? candidates[0] : undefined;
      if (candidate && candidate.status === "PAID" && candidate.amountMinor === amountMinor) {
        await db().ref(`bdoBankTransfers/${id}`).update({ status: "MATCHED", matchedType: "PAYOUT", matchedId: String(candidate.payoutId) });
        await db().ref(`payoutRequests/${String(candidate.payoutId)}`).update({ bdoReconciledAt: transactionAt, bdoTransferId: id });
      }
    }
  }
  saved = (await db().ref(`bdoBankTransfers/${id}`).get()).val() as Transfer;
  const movement = createFinancialTransfer({
    operationId: `bdo_bank_transfer_${id}`, eventType: "ADJUSTMENT", referenceType: "MANUAL", referenceId: id,
    idempotencyKey: `bdo_bank_transfer_${id}`, createdAt: transactionAt,
    description: `BDO statement ${direction.toLowerCase()} transfer ${transferReference}`, amountMinor,
    source: direction === "INBOUND"
      ? { accountId: `bdo_external_inbound_${id}`, bucket: "CLEARING", deltaMinor: 0, partyType: "PAYMENT_PROVIDER", partyId: "bdo_external" }
      : { accountId: "bdo_bank", bucket: "CLEARING", deltaMinor: 0, partyType: "PAYMENT_PROVIDER", partyId: "bdo" },
    destination: direction === "INBOUND"
      ? { accountId: "bdo_bank", bucket: "CLEARING", deltaMinor: 0, partyType: "PAYMENT_PROVIDER", partyId: "bdo" }
      : { accountId: `bdo_external_outbound_${id}`, bucket: "CLEARING", deltaMinor: 0, partyType: "PAYMENT_PROVIDER", partyId: "bdo_external" },
  });
  const ledger = await postFinancialOperation(movement);
  if (result.committed) await recordAdminFinancialAudit({ uid, action: "BDO_BANK_TRANSFER_RECORDED", targetType: "BDO_TRANSFER", targetId: id, after: saved });
  return { transferId: id, status: saved.status, matchedType: saved.matchedType, matchedId: saved.matchedId, ledgerOperationId: ledger.operationId, duplicate: !result.committed };
});

/** Durable audit report of unmatched BDO statement lines, settlements and payouts. */
export const reconcileBdoSettlements = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = admin(call);
  const input = call.data as { openingBalanceMinor?: unknown; closingBalanceMinor?: unknown; periodStartAt?: unknown; periodEndAt?: unknown };
  const opening = input.openingBalanceMinor;
  const closing = input.closingBalanceMinor;
  const start = input.periodStartAt;
  const end = input.periodEndAt;
  if (!Number.isSafeInteger(opening) || (opening as number) < 0 || !Number.isSafeInteger(closing) || (closing as number) < 0
    || !Number.isSafeInteger(start) || !Number.isSafeInteger(end) || (start as number) <= 0 || (end as number) <= (start as number)) {
    throw new HttpsError("invalid-argument", "Provide non-negative PHP-centavo opening/closing balances and a valid statement period.");
  }
  const [bankSnapshot, expectedSnapshot, payoutsSnapshot, entries] = await Promise.all([
    db().ref("bdoBankTransfers").get(), db().ref("expectedBdoSettlements").get(),
    db().ref("payoutRequests").get(), listEntriesForAccount("bdo_bank"),
  ]);
  const bank = Object.values((bankSnapshot.val() ?? {}) as Record<string, Transfer>);
  const expected = Object.values((expectedSnapshot.val() ?? {}) as Record<string, Expected>);
  const payouts = Object.values((payoutsSnapshot.val() ?? {}) as Record<string, Record<string, unknown>>);
  const unmatched = bank.filter((item) => item.status !== "MATCHED");
  const unsettled = expected.filter((item) => item.status !== "SETTLED");
  const paid = payouts.filter((item) => item.status === "PAID");
  const unreconciled = paid.filter((item) => !item.bdoReconciledAt);
  const inboundMinor = bank.filter((item) => item.direction === "INBOUND").reduce((sum, item) => sum + item.amountMinor, 0);
  const outboundMinor = bank.filter((item) => item.direction === "OUTBOUND").reduce((sum, item) => sum + item.amountMinor, 0);
  const ledgerMovementMinor = entries.filter((entry) => entry.bucket === "CLEARING"
    && Number.isSafeInteger(entry.deltaMinor) && Number.isSafeInteger(entry.createdAt)
    && entry.createdAt! >= (start as number) && entry.createdAt! <= (end as number))
    .reduce((sum, entry) => sum + Number(entry.deltaMinor), 0);
  const expectedClosingBalanceMinor = (opening as number) + ledgerMovementMinor;
  const bankBalanceDifferenceMinor = (closing as number) - expectedClosingBalanceMinor;
  const report = {
    id: randomUUID(), bank: "BDO", currency: "PHP", createdAt: Date.now(), createdBy: uid,
    statement: { periodStartAt: start, periodEndAt: end, openingBalanceMinor: opening, closingBalanceMinor: closing, ledgerMovementMinor, expectedClosingBalanceMinor, bankBalanceDifferenceMinor },
    totals: { inboundMinor, outboundMinor, netMovementMinor: inboundMinor - outboundMinor },
    counts: { bankTransfers: bank.length, matched: bank.length - unmatched.length, unmatched: unmatched.length,
      expectedSettlements: expected.length, unsettledSettlements: unsettled.length, paidPayouts: paid.length, unreconciledPayouts: unreconciled.length },
    balanced: unmatched.length === 0 && unsettled.length === 0 && unreconciled.length === 0 && bankBalanceDifferenceMinor === 0,
    unmatchedBankTransfers: unmatched, unsettledExpectedSettlements: unsettled,
    unreconciledPayouts: unreconciled.map((item) => ({ payoutId: item.payoutId, amountMinor: item.amountMinor, transferReference: item.transferReference ?? null })),
  };
  await db().ref(`bdoReconciliations/${report.id}`).set(report);
  await recordAdminFinancialAudit({ uid, action: "BDO_RECONCILIATION_RUN", targetType: "BDO_RECONCILIATION", targetId: report.id, after: report });
  return report;
});
