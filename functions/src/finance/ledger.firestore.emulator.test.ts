import { randomUUID } from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { deleteApp, initializeApp, type App } from "firebase-admin/app";
import { getFinancialAccount, postFinancialOperation, reconcileFinancialLedger, createFinancialTransfer } from "./ledger.js";

const emulatorOnly = !!process.env.FIRESTORE_EMULATOR_HOST;

describe.skipIf(!emulatorOnly)("Firestore emulator: financial ledger transactions", () => {
  let app: App | undefined;
  beforeAll(() => {
    app = initializeApp({ projectId: process.env.GCLOUD_PROJECT || "demo-labmda" });
  });
  afterAll(async () => {
    if (app) await deleteApp(app);
  });

  it("is idempotent and prevents concurrent payouts from overspending an account", async () => {
    const suffix = randomUUID().replace(/-/g, "");
    const accountId = `wallet_driver_${suffix}`;
    const partyId = `driver_${suffix}`;
    const now = Date.now();
    const seed = createFinancialTransfer({
      operationId: `test_seed_${suffix}`, eventType: "ADJUSTMENT", referenceType: "MANUAL",
      referenceId: `seed_${suffix}`, idempotencyKey: `seed_${suffix}`, createdAt: now,
      description: "Emulator test opening balance", amountMinor: 100,
      source: { accountId: `test_external_${suffix}`, bucket: "CLEARING", deltaMinor: 0, partyType: "PAYMENT_PROVIDER", partyId: "test" },
      destination: { accountId, bucket: "AVAILABLE", deltaMinor: 0, partyType: "DRIVER", partyId },
    });
    await postFinancialOperation(seed);
    const makePayout = (number: number) => createFinancialTransfer({
      operationId: `test_payout_${suffix}_${number}`, eventType: "PAYOUT", referenceType: "PAYOUT",
      referenceId: `payout_${suffix}_${number}`, idempotencyKey: `payout_${suffix}_${number}`,
      createdAt: now + number, description: "Emulator concurrent payout reservation", amountMinor: 60,
      source: { accountId, bucket: "AVAILABLE", deltaMinor: 0, partyType: "DRIVER", partyId },
      destination: { accountId, bucket: "CLEARING", deltaMinor: 0, partyType: "DRIVER", partyId },
    });
    const candidates = [makePayout(1), makePayout(2)];
    const results = await Promise.allSettled(candidates.map(postFinancialOperation));
    const winners = results.filter((result) => result.status === "fulfilled");
    const losers = results.filter((result) => result.status === "rejected");
    expect(winners).toHaveLength(1);
    expect(losers).toHaveLength(1);
    const winningOperation = candidates[results.findIndex((result) => result.status === "fulfilled")]!;
    expect(await postFinancialOperation(winningOperation)).toEqual({
      operationId: winningOperation.operationId, duplicate: true,
    });
    const account = await getFinancialAccount(accountId);
    expect(account?.availableMinor).toBe(40);
    expect(account?.clearingMinor).toBe(60);
    const reconciliation = await reconcileFinancialLedger();
    expect(reconciliation.balanced).toBe(true);
    expect(reconciliation.discrepancies).toEqual([]);
  });
  it("serializes many concurrent payout reservations against the same wallet", async () => {
    const suffix = randomUUID().replace(/-/g, "");
    const accountId = `wallet_driver_stress_${suffix}`;
    const partyId = `driver_stress_${suffix}`;
    const now = Date.now();
    await postFinancialOperation(createFinancialTransfer({
      operationId: `stress_seed_${suffix}`, eventType: "ADJUSTMENT", referenceType: "MANUAL",
      referenceId: `stress_seed_${suffix}`, idempotencyKey: `stress_seed_${suffix}`,
      createdAt: now, description: "Concurrent payout stress opening balance", amountMinor: 100,
      source: { accountId: `stress_external_${suffix}`, bucket: "CLEARING", deltaMinor: 0, partyType: "PAYMENT_PROVIDER", partyId: "test" },
      destination: { accountId, bucket: "AVAILABLE", deltaMinor: 0, partyType: "DRIVER", partyId },
    }));
    const payouts = Array.from({ length: 4 }, (_, index) => createFinancialTransfer({
      operationId: `stress_payout_${suffix}_${index}`, eventType: "PAYOUT", referenceType: "PAYOUT",
      referenceId: `stress_payout_${suffix}_${index}`, idempotencyKey: `stress_payout_${suffix}_${index}`,
      createdAt: now + index + 1, description: "Concurrent payout stress reservation", amountMinor: 30,
      source: { accountId, bucket: "AVAILABLE", deltaMinor: 0, partyType: "DRIVER", partyId },
      destination: { accountId, bucket: "CLEARING", deltaMinor: 0, partyType: "DRIVER", partyId },
    }));
    const results = await Promise.allSettled(payouts.map(postFinancialOperation));
    expect(results.filter((result) => result.status === "fulfilled")).toHaveLength(3);
    expect(results.filter((result) => result.status === "rejected")).toHaveLength(1);
    const account = await getFinancialAccount(accountId);
    expect(account?.availableMinor).toBe(10);
    expect(account?.clearingMinor).toBe(90);
    const reconciliation = await reconcileFinancialLedger();
    expect(reconciliation.balanced).toBe(true);
  });

});
