"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { mapLegacyRequest } = require("../scripts/migrate-payout-requests.cjs");

test("maps a pending legacy request into the canonical payouts record", () => {
  const result = mapLegacyRequest("payout-1", {
    userId: "merchant-1", requestKey: "request-key", amountCentavos: 12500,
    status: "PENDING_ADMIN_APPROVAL", createdAtTimestamp: 100, maskedAccountNumber: "09*****1234"
  }, {}, 200);
  assert.equal(result.id, "payout-1");
  assert.equal(result.userId, "merchant-1");
  assert.equal(result.amountCentavos, 12500);
  assert.equal(result.currency, "PHP");
  assert.equal(result.status, "PENDING_ADMIN_APPROVAL");
  assert.equal(result.requestStatus, "PENDING_ADMIN_APPROVAL");
  assert.equal(result.request.legacyPath, "payoutRequests/payout-1");
  assert.equal(result.request.maskedAccountNumber, "09*****1234");
});

test("preserves existing provider execution and ledger fields", () => {
  const result = mapLegacyRequest("payout-2", {
    userId: "driver-1", amountCentavos: 8000, status: "PROCESSING", createdAtTimestamp: 100
  }, {
    id: "payout-2", userId: "driver-1", amountCentavos: 8000, status: "ROUTING",
    providerPayoutId: "po-123", reserveLedgerEntryId: "reserve-entry"
  }, 300);
  assert.equal(result.status, "ROUTING");
  assert.equal(result.providerPayoutId, "po-123");
  assert.equal(result.reserveLedgerEntryId, "reserve-entry");
  assert.equal(result.requestStatus, "PROCESSING");
});

test("rejects owner and amount conflicts", () => {
  assert.throws(() => mapLegacyRequest("payout-3", {
    userId: "user-a", amountCentavos: 1000, status: "PENDING_ADMIN_APPROVAL"
  }, { userId: "user-b" }), /different user/);
  assert.throws(() => mapLegacyRequest("payout-4", {
    userId: "user-a", amountCentavos: 1000, status: "PENDING_ADMIN_APPROVAL"
  }, { userId: "user-a", amountCentavos: 2000 }), /amount conflicts/);
});

test("does not invent a provider payout for a completed request", () => {
  assert.throws(() => mapLegacyRequest("payout-5", {
    userId: "user-a", amountCentavos: 1000, status: "PAID"
  }, {}), /no provider payout record/);
});

test("rejects invalid centavo amounts", () => {
  for (const amountCentavos of [0, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
    assert.throws(() => mapLegacyRequest("payout-6", {
      userId: "user-a", amountCentavos, status: "PENDING_ADMIN_APPROVAL"
    }, {}), /positive integer number of centavos/);
  }
});
