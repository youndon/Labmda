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


export type RefundShareInput = {
  amountCentavos?: unknown;
  status?: unknown;
  walletSettlementStatus?: unknown;
};

export type NetParticipantShares = {
  merchantCentavos: number;
  courierCentavos: number;
  driverCentavos: number;
  platformCentavos: number;
  netAmountCentavos: number;
  refundReconciliationPending: boolean;
};

/**
 * Subtract only provider-confirmed refunds that have already been posted to the
 * wallet/ledger. A succeeded but unposted refund blocks earnings release.
 */
export function calculateNetParticipantShares(input: {
  grossAmountCentavos: number;
  merchantCentavos: number;
  courierCentavos: number;
  driverCentavos: number;
  platformCentavos: number;
  refunds?: Record<string, RefundShareInput>;
}): NetParticipantShares {
  const gross = input.grossAmountCentavos;
  const original = [
    input.merchantCentavos,
    input.courierCentavos,
    input.driverCentavos,
    input.platformCentavos,
  ];
  if (!Number.isSafeInteger(gross) || gross < 0 ||
      original.some((value) => !Number.isSafeInteger(value) || value < 0) ||
      original.reduce((sum, value) => sum + value, 0) !== gross) {
    throw new RangeError("The original payment shares must reconcile to the gross payment.");
  }

  let refundedTotal = 0;
  let refundedMerchant = 0;
  let refundedCourier = 0;
  let refundedDriver = 0;
  let refundedPlatform = 0;
  let refundReconciliationPending = false;

  for (const refund of Object.values(input.refunds ?? {})) {
    if (String(refund.status ?? "").toUpperCase() !== "SUCCEEDED") continue;
    if (String(refund.walletSettlementStatus ?? "").toUpperCase() !== "POSTED") {
      refundReconciliationPending = true;
      continue;
    }
    const refundAmount = Number(refund.amountCentavos);
    if (!Number.isSafeInteger(refundAmount) || refundAmount <= 0) {
      throw new RangeError("A confirmed refund amount is invalid.");
    }
    refundedTotal += refundAmount;
    if (!Number.isSafeInteger(refundedTotal) || refundedTotal > gross) {
      throw new RangeError("Confirmed refunds exceed the original payment amount.");
    }
    const scaled = (share: number): number =>
      Number((BigInt(refundAmount) * BigInt(share)) / BigInt(gross || 1));
    const merchantRefund = scaled(input.merchantCentavos);
    const courierRefund = scaled(input.courierCentavos);
    const driverRefund = scaled(input.driverCentavos);
    const platformRefund = refundAmount - merchantRefund - courierRefund - driverRefund;
    refundedMerchant += merchantRefund;
    refundedCourier += courierRefund;
    refundedDriver += driverRefund;
    refundedPlatform += platformRefund;
  }

  return {
    merchantCentavos: Math.max(0, input.merchantCentavos - refundedMerchant),
    courierCentavos: Math.max(0, input.courierCentavos - refundedCourier),
    driverCentavos: Math.max(0, input.driverCentavos - refundedDriver),
    platformCentavos: Math.max(0, input.platformCentavos - refundedPlatform),
    netAmountCentavos: gross - refundedTotal,
    refundReconciliationPending,
  };
}
