import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";

type FinanceAccount = {
  availableMinor?: number;
  pendingMinor?: number;
  cashOwedMinor?: number;
  clearingMinor?: number;
};
type FinanceOperation = {
  operationId?: string;
  eventType?: string;
  referenceType?: string;
  referenceId?: string;
  description?: string;
  createdAt?: number;
  postings?: Array<{ accountId?: string; bucket?: string; deltaMinor?: number }>;
};

function requireAdmin(claims: Record<string, unknown> | undefined): void {
  if (claims?.admin !== true) {
    throw new HttpsError("permission-denied", "Administrator access is required.");
  }
}
function safeMinor(value: unknown): number {
  return Number.isSafeInteger(value) ? Number(value) : 0;
}

/** Read-only administrator snapshot derived from the immutable financial ledger. */
export const getAdminFinancialOverview = onCall({ enforceAppCheck: true }, async (call) => {
  if (!call.auth?.uid) throw new HttpsError("unauthenticated", "Sign in to continue.");
  requireAdmin(call.auth.token as Record<string, unknown> | undefined);

  const database = getDatabase();
  const [accountsSnapshot, operationsSnapshot, requested, approved, paying, paid] = await Promise.all([
    database.ref("finance/accounts").get(),
    database.ref("finance/operations").orderByChild("createdAt").limitToLast(1000).get(),
    database.ref("payoutRequests").orderByChild("status").equalTo("REQUESTED").get(),
    database.ref("payoutRequests").orderByChild("status").equalTo("APPROVED").get(),
    database.ref("payoutRequests").orderByChild("status").equalTo("PAYING").get(),
    database.ref("payoutRequests").orderByChild("status").equalTo("PAID").get(),
  ]);

  const accounts = (accountsSnapshot.val() ?? {}) as Record<string, FinanceAccount>;
  // A bounded recent sample keeps dashboard reads independent of total journal size.
  const operations = Object.values((operationsSnapshot.val() ?? {}) as Record<string, FinanceOperation>);
  const platformAccounts = Object.entries(accounts).filter(([id]) => id === "platform_revenue" || id.startsWith("wallet_platform_"));
  const platformAvailableMinor = platformAccounts.reduce((sum, [, account]) => sum + safeMinor(account.availableMinor), 0);
  const platformClearingMinor = platformAccounts.reduce((sum, [, account]) => sum + safeMinor(account.clearingMinor), 0);
  const providerAccounts = Object.entries(accounts).filter(([id]) =>
    id.startsWith("wallet_merchant_")
    || id.startsWith("wallet_driver_")
    || id.startsWith("wallet_delivery_"),
  );

  const providerBalances = providerAccounts.reduce((sum, [, account]) => ({
    availableMinor: sum.availableMinor + safeMinor(account.availableMinor),
    pendingMinor: sum.pendingMinor + safeMinor(account.pendingMinor),
    clearingMinor: sum.clearingMinor + safeMinor(account.clearingMinor),
  }), { availableMinor: 0, pendingMinor: 0, clearingMinor: 0 });

  const cashOutstandingMinor = Object.entries(accounts)
    .filter(([id]) => id.startsWith("cash_owed_"))
    .reduce((sum, [, account]) => sum + safeMinor(account.cashOwedMinor), 0);

  const paymentOperations = operations.filter((operation) => operation.eventType === "CUSTOMER_PAYMENT");
  const paymentVolumeMinor = paymentOperations.reduce((sum, operation) => {
    const sourceDebit = (operation.postings ?? [])
      .filter((posting) =>
        (posting.accountId === "payment_provider_clearing" || posting.accountId === "cash_collection_clearing")
        && posting.bucket === "CLEARING"
        && safeMinor(posting.deltaMinor) < 0,
      )
      .reduce((amount, posting) => amount + Math.abs(safeMinor(posting.deltaMinor)), 0);
    return sum + sourceDebit;
  }, 0);

  const payoutGroups = [
    { status: "REQUESTED", records: requested.val() ?? {} },
    { status: "APPROVED", records: approved.val() ?? {} },
    { status: "PAYING", records: paying.val() ?? {} },
    { status: "PAID", records: paid.val() ?? {} },
  ];
  const payoutSummary = payoutGroups.reduce((summary, group) => {
    const records = Object.values(group.records as Record<string, { amountMinor?: number }>);
    summary[group.status] = {
      count: records.length,
      amountMinor: records.reduce((sum, item) => sum + safeMinor(item.amountMinor), 0),
    };
    return summary;
  }, {} as Record<string, { count: number; amountMinor: number }>);

  const manilaOffsetMs = 8 * 60 * 60 * 1000;
  const today = new Date(Date.now() + manilaOffsetMs);
  const todayStart = Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate()) - manilaOffsetMs;
  const daily = Array.from({ length: 7 }, (_, index) => {
    const start = todayStart - (6 - index) * 24 * 60 * 60 * 1000;
    const date = new Date(start + manilaOffsetMs).toISOString().slice(0, 10);
    return { date, amountMinor: 0 };
  });
  const dailyByDate = new Map(daily.map((item) => [item.date, item]));
  paymentOperations.forEach((operation) => {
    if (!Number.isFinite(operation.createdAt)) return;
    const date = new Date(Number(operation.createdAt) + manilaOffsetMs).toISOString().slice(0, 10);
    const bucket = dailyByDate.get(date);
    if (!bucket) return;
    const amount = (operation.postings ?? [])
      .filter((posting) =>
        (posting.accountId === "payment_provider_clearing" || posting.accountId === "cash_collection_clearing")
        && safeMinor(posting.deltaMinor) < 0,
      )
      .reduce((sum, posting) => sum + Math.abs(safeMinor(posting.deltaMinor)), 0);
    bucket.amountMinor += amount;
  });

  const recentOperations = operations
    .filter((operation) => Number.isFinite(operation.createdAt))
    .sort((a, b) => Number(b.createdAt) - Number(a.createdAt))
    .slice(0, 8)
    .map((operation) => ({
      operationId: operation.operationId ?? "",
      eventType: operation.eventType ?? "UNKNOWN",
      referenceId: operation.referenceId ?? "",
      description: operation.description ?? "",
      createdAt: safeMinor(operation.createdAt),
      amountMinor: (operation.postings ?? [])
        .filter((posting) => safeMinor(posting.deltaMinor) < 0)
        .reduce((sum, posting) => sum + Math.abs(safeMinor(posting.deltaMinor)), 0),
    }));

  return {
    currency: "PHP",
    generatedAt: Date.now(),
    platformRevenueAvailableMinor: platformAvailableMinor,
    platformRevenueClearingMinor: platformClearingMinor,
    providerBalances,
    cashOutstandingMinor,
    paymentVolumeMinor,
    paymentCount: paymentOperations.length,
    paymentSampleLimit: 1000,
    payoutSummary,
    dailyPaymentVolume: daily,
    recentOperations,
  };
});
