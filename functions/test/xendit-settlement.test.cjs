const test = require("node:test");
const assert = require("node:assert/strict");
const {
  phpAmountToCentavos,
  matchesGcashPaymentTransaction,
  isSettledGcashPaymentTransaction,
  calculateNetParticipantShares,
} = require("../lib/xenditSettlement.js");

const payment = {
  referenceId: "labmda_order_order_1234567890abcdef",
  providerReference: "pr-12345678-1234-1234-1234-123456789012",
  providerPaymentId: "py-12345678-1234-1234-1234-123456789012",
  amountCentavos: 59000,
};

const transaction = {
  id: "txn-123",
  product_id: payment.providerPaymentId,
  reference_id: payment.referenceId,
  status: "SUCCESS",
  channel_code: "GCASH",
  currency: "PHP",
  amount: 590,
  cashflow: "MONEY_IN",
  settlement_status: "SETTLED",
  product_data: {
    payment_request_id: payment.providerReference,
  },
};

test("converts major PHP amounts to integer centavos", () => {
  assert.equal(phpAmountToCentavos(590), 59000);
  assert.equal(phpAmountToCentavos(590.25), 59025);
  assert.equal(phpAmountToCentavos("0.01"), 1);
  assert.equal(phpAmountToCentavos(-1), null);
  assert.equal(phpAmountToCentavos("not-a-number"), null);
});

test("matches only the exact successful GCash money-in transaction", () => {
  assert.equal(matchesGcashPaymentTransaction(payment, transaction), true);
  assert.equal(isSettledGcashPaymentTransaction(payment, transaction), true);
});

test("does not treat an unsettled GCash transaction as settled", () => {
  const pending = { ...transaction, settlement_status: "PENDING" };
  assert.equal(matchesGcashPaymentTransaction(payment, pending), true);
  assert.equal(isSettledGcashPaymentTransaction(payment, pending), false);
});

test("accepts Xendit early settlement only after all identifiers and amounts match", () => {
  const early = { ...transaction, settlement_status: "EARLY_SETTLED" };
  assert.equal(isSettledGcashPaymentTransaction(payment, early), true);
  assert.equal(
    isSettledGcashPaymentTransaction(payment, { ...early, amount: 589.99 }),
    false
  );
});

test("rejects a transaction with a different payment request, reference, channel or currency", () => {
  assert.equal(
    matchesGcashPaymentTransaction(payment, {
      ...transaction,
      product_data: { payment_request_id: "pr-other" },
      product_id: "py-other",
    }),
    false
  );
  assert.equal(
    matchesGcashPaymentTransaction(payment, { ...transaction, reference_id: "other-ref" }),
    false
  );
  assert.equal(
    matchesGcashPaymentTransaction(payment, { ...transaction, channel_code: "PH_GCASH" }),
    false
  );
  assert.equal(
    matchesGcashPaymentTransaction(payment, { ...transaction, currency: "USD" }),
    false
  );
});


test("subtracts only posted refunds from participant earnings", () => {
  const net = calculateNetParticipantShares({
    grossAmountCentavos: 100000,
    merchantCentavos: 50000,
    courierCentavos: 20000,
    driverCentavos: 0,
    platformCentavos: 30000,
    refunds: {
      refundA: {
        amountCentavos: 10000,
        status: "SUCCEEDED",
        walletSettlementStatus: "POSTED",
      },
    },
  });
  assert.deepEqual(net, {
    merchantCentavos: 45000,
    courierCentavos: 18000,
    driverCentavos: 0,
    platformCentavos: 27000,
    netAmountCentavos: 90000,
    refundReconciliationPending: false,
  });
});

test("blocks earnings release while a succeeded refund is not posted to the ledger", () => {
  const net = calculateNetParticipantShares({
    grossAmountCentavos: 100000,
    merchantCentavos: 50000,
    courierCentavos: 20000,
    driverCentavos: 0,
    platformCentavos: 30000,
    refunds: {
      refundA: {
        amountCentavos: 10000,
        status: "SUCCEEDED",
        walletSettlementStatus: "PENDING",
      },
    },
  });
  assert.equal(net.refundReconciliationPending, true);
});

test("rejects refunds that exceed the original payment amount", () => {
  assert.throws(() => calculateNetParticipantShares({
    grossAmountCentavos: 100000,
    merchantCentavos: 50000,
    courierCentavos: 20000,
    driverCentavos: 0,
    platformCentavos: 30000,
    refunds: {
      refundA: {
        amountCentavos: 60000,
        status: "SUCCEEDED",
        walletSettlementStatus: "POSTED",
      },
      refundB: {
        amountCentavos: 50000,
        status: "SUCCEEDED",
        walletSettlementStatus: "POSTED",
      },
    },
  }));
});
