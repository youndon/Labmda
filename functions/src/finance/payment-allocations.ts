import type { PaymentAllocation, PaymentTransactionType } from "../models/payment.js";
import type { MoneyBreakdown, ProviderFeePayer } from "../pricing/engine.js";

export interface OrderAllocationInput {
  paymentId: string;
  orderId: string;
  merchantOwnerId: string;
  courierId: string;
  breakdown: MoneyBreakdown;
  providerFeePayer: ProviderFeePayer;
  createdAt: number;
  status?: PaymentAllocation["status"];
}

/**
 * Derives the exact order allocation split from the trusted server quote.
 * Merchant-funded discounts reduce merchant proceeds; platform-funded
 * discounts reduce platform proceeds. The customer-paid provider fee is
 * allocated to the platform so allocations reconcile to the charged total.
 */
export function buildOrderPaymentAllocations(input: OrderAllocationInput): PaymentAllocation[] {
  const { breakdown } = input;
  const amounts = [
    breakdown.customerTotalMinor,
    breakdown.merchantEarningsMinor,
    breakdown.courierEarningsMinor,
    breakdown.platformCommissionMinor,
    breakdown.platformDiscountMinor,
    breakdown.providerFeeMinor,
  ];
  if (!amounts.every((amount) => Number.isSafeInteger(amount) && amount >= 0)) {
    throw new Error("Order allocation amounts must be non-negative PHP-centavo integers.");
  }
  if (!input.paymentId || !input.orderId || !input.merchantOwnerId || !input.courierId
    || !Number.isSafeInteger(input.createdAt) || input.createdAt <= 0) {
    throw new Error("Order allocation identity and timestamp are required.");
  }
  const platformMinor = breakdown.platformCommissionMinor - breakdown.platformDiscountMinor
    + (input.providerFeePayer === "CUSTOMER" ? breakdown.providerFeeMinor : 0);
  if (platformMinor < 0 || !Number.isSafeInteger(platformMinor)) {
    throw new Error("Platform allocation cannot be negative.");
  }
  const rows = [
    { beneficiaryId: input.merchantOwnerId, beneficiaryType: "MERCHANT" as const, amountMinor: breakdown.merchantEarningsMinor },
    { beneficiaryId: input.courierId, beneficiaryType: "DELIVERY" as const, amountMinor: breakdown.courierEarningsMinor },
    { beneficiaryId: "platform", beneficiaryType: "PLATFORM" as const, amountMinor: platformMinor },
  ].filter((row) => row.amountMinor > 0);
  const total = rows.reduce((sum, row) => sum + BigInt(row.amountMinor), 0n);
  if (total !== BigInt(breakdown.customerTotalMinor)) {
    throw new Error("Order allocations do not reconcile to the quoted customer total.");
  }
  const status = input.status ?? "PENDING";
  return rows.map((row, index) => ({
    id: `alloc_${input.paymentId}_${index + 1}`,
    paymentId: input.paymentId,
    transactionType: "ORDER" as PaymentTransactionType,
    orderId: input.orderId,
    tripId: "",
    deliveryId: input.courierId,
    beneficiaryId: row.beneficiaryId,
    beneficiaryType: row.beneficiaryType,
    amountMinor: row.amountMinor,
    currency: "PHP",
    status,
    createdAt: input.createdAt,
    updatedAt: input.createdAt,
  }));
}

export interface RideAllocationInput {
  paymentId: string;
  tripId: string;
  driverId: string;
  fareMinor: number;
  driverEarningsMinor: number;
  platformCommissionMinor: number;
  providerFeeMinor: number;
  providerFeePayer: ProviderFeePayer;
  customerTotalMinor: number;
  createdAt: number;
  status?: PaymentAllocation["status"];
}

/** Builds balanced ride allocations from a trusted server-generated fare quote. */
export function buildRidePaymentAllocations(input: RideAllocationInput): PaymentAllocation[] {
  const values = [input.fareMinor, input.driverEarningsMinor, input.platformCommissionMinor,
    input.providerFeeMinor, input.customerTotalMinor];
  if (!values.every((amount) => Number.isSafeInteger(amount) && amount >= 0)
    || !input.paymentId || !input.tripId || !input.driverId
    || !Number.isSafeInteger(input.createdAt) || input.createdAt <= 0) {
    throw new Error("Ride allocation identity and amounts are invalid.");
  }
  const platformMinor = input.platformCommissionMinor
    + (input.providerFeePayer === "CUSTOMER" ? input.providerFeeMinor : 0);
  const total = BigInt(input.driverEarningsMinor) + BigInt(platformMinor);
  if (total !== BigInt(input.customerTotalMinor)
    || input.driverEarningsMinor + input.platformCommissionMinor !== input.fareMinor) {
    throw new Error("Ride allocations do not reconcile to the quoted fare and customer total.");
  }
  const rows = [
    { beneficiaryId: input.driverId, beneficiaryType: "DRIVER" as const, amountMinor: input.driverEarningsMinor },
    { beneficiaryId: "platform", beneficiaryType: "PLATFORM" as const, amountMinor: platformMinor },
  ].filter((row) => row.amountMinor > 0);
  return rows.map((row, index) => ({
    id: `alloc_${input.paymentId}_${index + 1}`,
    paymentId: input.paymentId,
    transactionType: "RIDE" as PaymentTransactionType,
    orderId: "",
    tripId: input.tripId,
    deliveryId: "",
    beneficiaryId: row.beneficiaryId,
    beneficiaryType: row.beneficiaryType,
    amountMinor: row.amountMinor,
    currency: "PHP",
    status: input.status ?? "PENDING",
    createdAt: input.createdAt,
    updatedAt: input.createdAt,
  }));
}


export interface StandaloneDeliveryAllocationInput {
  paymentId: string;
  orderId: string;
  courierId: string;
  deliveryFeeMinor: number;
  courierEarningsMinor: number;
  platformCommissionMinor: number;
  providerFeeMinor: number;
  providerFeePayer: ProviderFeePayer;
  customerTotalMinor: number;
  createdAt: number;
}

/** Builds courier/platform splits for a standalone delivery with no merchant. */
export function buildStandaloneDeliveryAllocations(input: StandaloneDeliveryAllocationInput): PaymentAllocation[] {
  const values = [input.deliveryFeeMinor, input.courierEarningsMinor, input.platformCommissionMinor,
    input.providerFeeMinor, input.customerTotalMinor];
  if (!values.every((amount) => Number.isSafeInteger(amount) && amount >= 0)
    || !input.paymentId || !input.orderId || !input.courierId
    || !Number.isSafeInteger(input.createdAt) || input.createdAt <= 0) {
    throw new Error("Standalone delivery allocation identity and amounts are invalid.");
  }
  const platformMinor = input.platformCommissionMinor
    + (input.providerFeePayer === "CUSTOMER" ? input.providerFeeMinor : 0);
  if (input.courierEarningsMinor + input.platformCommissionMinor !== input.deliveryFeeMinor
    || input.courierEarningsMinor + platformMinor !== input.customerTotalMinor) {
    throw new Error("Standalone delivery allocations do not reconcile to the quoted fee and customer total.");
  }
  return [
    { beneficiaryId: input.courierId, beneficiaryType: "DELIVERY" as const, amountMinor: input.courierEarningsMinor },
    { beneficiaryId: "platform", beneficiaryType: "PLATFORM" as const, amountMinor: platformMinor },
  ].filter((row) => row.amountMinor > 0).map((row, index) => ({
    id: `alloc_${input.paymentId}_${index + 1}`,
    paymentId: input.paymentId, transactionType: "ORDER" as const,
    orderId: input.orderId, tripId: "", deliveryId: input.courierId,
    beneficiaryId: row.beneficiaryId, beneficiaryType: row.beneficiaryType,
    amountMinor: row.amountMinor, currency: "PHP" as const, status: "PENDING" as const,
    createdAt: input.createdAt, updatedAt: input.createdAt,
  }));
}
