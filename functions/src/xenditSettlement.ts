export type GcashPaymentReference = {
  referenceId?: unknown;
  providerReference?: unknown;
  providerPaymentId?: unknown;
  amountCentavos?: unknown;
};

export type XenditTransaction = {
  id?: unknown;
  product_id?: unknown;
  reference_id?: unknown;
  status?: unknown;
  channel_code?: unknown;
  currency?: unknown;
  amount?: unknown;
  cashflow?: unknown;
  settlement_status?: unknown;
  estimated_settlement_time?: unknown;
  updated?: unknown;
  product_data?: {
    payment_request_id?: unknown;
  } | null;
};

/** Xendit transaction and balance amounts are expressed in major PHP units. */
export function phpAmountToCentavos(value: unknown): number | null {
  const amount = typeof value === "number" ? value :
    typeof value === "string" && value.trim() !== "" ? Number(value) : Number.NaN;
  if (!Number.isFinite(amount) || amount < 0 || amount > Number.MAX_SAFE_INTEGER / 100) {
    return null;
  }
  const centavos = Math.round((amount + Number.EPSILON) * 100);
  return Number.isSafeInteger(centavos) ? centavos : null;
}

/** Match a transaction to the exact Labmda Payment Request before trusting settlement. */
export function matchesGcashPaymentTransaction(
  payment: GcashPaymentReference,
  transaction: XenditTransaction
): boolean {
  const expectedAmount = Number(payment.amountCentavos);
  const actualAmount = phpAmountToCentavos(transaction.amount);
  const productData = transaction.product_data ?? {};
  const paymentRequestId = typeof payment.providerReference === "string"
    ? payment.providerReference
    : "";
  const providerPaymentId = typeof payment.providerPaymentId === "string"
    ? payment.providerPaymentId
    : "";
  const productMatches =
    (paymentRequestId !== "" && productData.payment_request_id === paymentRequestId) ||
    (providerPaymentId !== "" && transaction.product_id === providerPaymentId);

  return typeof payment.referenceId === "string" &&
    payment.referenceId !== "" &&
    transaction.reference_id === payment.referenceId &&
    productMatches &&
    String(transaction.status ?? "").toUpperCase() === "SUCCESS" &&
    String(transaction.cashflow ?? "").toUpperCase() === "MONEY_IN" &&
    String(transaction.currency ?? "").toUpperCase() === "PHP" &&
    String(transaction.channel_code ?? "").toUpperCase() === "GCASH" &&
    Number.isSafeInteger(expectedAmount) &&
    expectedAmount >= 0 &&
    actualAmount === expectedAmount;
}

/** A successful GCash collection is not settled until Xendit says SETTLED or EARLY_SETTLED. */
export function isSettledGcashPaymentTransaction(
  payment: GcashPaymentReference,
  transaction: XenditTransaction
): boolean {
  return matchesGcashPaymentTransaction(payment, transaction) &&
    ["SETTLED", "EARLY_SETTLED"].includes(
      String(transaction.settlement_status ?? "").toUpperCase()
    );
}
