export type DiscountFunder = "PLATFORM" | "MERCHANT";
export type ProviderFeePayer = "PLATFORM" | "CUSTOMER";

export interface DeliveryRate {
  baseFeeMinor: number;
  perKmMinor: number;
  minimumFeeMinor: number;
  platformCommissionBps: number;
}
export interface PricingConfig {
  version: number;
  currency: "PHP";
  ride: {
    baseFareMinor: number;
    perKmMinor: number;
    perMinuteMinor: number;
    minimumFareMinor: number;
    platformCommissionBps: number;
  };
  delivery: DeliveryRate;
  food: { commissionBps: number };
  shop: { commissionBps: number };
  providerFees: {
    GCASH: { rateBps: number; fixedFeeMinor: number; payer: ProviderFeePayer };
  };
}
export interface DiscountRule {
  active: boolean;
  kind: "FIXED" | "PERCENT";
  valueMinor?: number;
  valueBps?: number;
  maxDiscountMinor?: number;
  minimumSubtotalMinor?: number;
  funder: DiscountFunder;
  startsAt?: number;
  endsAt?: number;
}
export interface MoneyBreakdown {
  currency: "PHP";
  subtotalMinor: number;
  deliveryFeeMinor: number;
  discountMinor: number;
  merchantDiscountMinor: number;
  platformDiscountMinor: number;
  providerFeeMinor: number;
  customerTotalMinor: number;
  merchantCommissionMinor: number;
  deliveryCommissionMinor: number;
  platformCommissionMinor: number;
  merchantEarningsMinor: number;
  courierEarningsMinor: number;
  platformNetBeforeOtherCostsMinor: number;
}
export interface RouteMetrics {
  distanceMeters: number;
  durationSeconds: number;
}
export type PaymentMethod = "GCASH" | "CASH";

function assertMinor(value: number, field: string): void {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error(`${field} must be a non-negative safe integer in PHP centavos.`);
}
function assertBps(value: number, field: string): void {
  if (!Number.isSafeInteger(value) || value < 0 || value > 10000) throw new Error(`${field} must be between 0 and 10000 basis points.`);
}
function roundBps(amountMinor: number, bps: number): number {
  assertMinor(amountMinor, "amountMinor");
  assertBps(bps, "basisPoints");
  return Number((BigInt(amountMinor) * BigInt(bps) + 5000n) / 10000n);
}
function roundUpPerKm(distanceMeters: number, perKmMinor: number): number {
  if (!Number.isSafeInteger(distanceMeters) || distanceMeters < 0) throw new Error("distanceMeters must be a non-negative integer.");
  assertMinor(perKmMinor, "perKmMinor");
  const wholeOrPartialKm = Math.ceil(distanceMeters / 1000);
  const amount = BigInt(wholeOrPartialKm) * BigInt(perKmMinor);
  const result = Number(amount);
  assertMinor(result, "distance charge");
  return result;
}
function providerFee(config: PricingConfig, method: PaymentMethod, amountMinor: number): { amount: number; payer: ProviderFeePayer } {
  if (method === "CASH") return { amount: 0, payer: "PLATFORM" };
  const fee = config.providerFees.GCASH;
  assertBps(fee.rateBps, "providerFees.GCASH.rateBps");
  assertMinor(fee.fixedFeeMinor, "providerFees.GCASH.fixedFeeMinor");
  return { amount: roundBps(amountMinor, fee.rateBps) + fee.fixedFeeMinor, payer: fee.payer };
}
export function applyDiscount(subtotalMinor: number, rule?: DiscountRule, now = Date.now()): { total: number; merchant: number; platform: number } {
  assertMinor(subtotalMinor, "subtotalMinor");
  if (!rule) return { total: 0, merchant: 0, platform: 0 };
  if (!rule.active) throw new Error("Discount is not active.");
  if (rule.startsAt !== undefined && now < rule.startsAt) throw new Error("Discount is not active yet.");
  if (rule.endsAt !== undefined && now > rule.endsAt) throw new Error("Discount has expired.");
  if (subtotalMinor < (rule.minimumSubtotalMinor ?? 0)) throw new Error("Order does not meet the discount minimum.");
  let discount = rule.kind === "FIXED"
    ? (rule.valueMinor ?? 0)
    : roundBps(subtotalMinor, rule.valueBps ?? 0);
  assertMinor(discount, "discount");
  if (rule.maxDiscountMinor !== undefined) discount = Math.min(discount, rule.maxDiscountMinor);
  discount = Math.min(discount, subtotalMinor);
  return { total: discount, merchant: rule.funder === "MERCHANT" ? discount : 0, platform: rule.funder === "PLATFORM" ? discount : 0 };
}
export function calculateDeliveryFee(config: DeliveryRate, route: RouteMetrics): { feeMinor: number; platformCommissionMinor: number; courierEarningsMinor: number } {
  assertMinor(config.baseFeeMinor, "delivery.baseFeeMinor");
  assertMinor(config.minimumFeeMinor, "delivery.minimumFeeMinor");
  assertBps(config.platformCommissionBps, "delivery.platformCommissionBps");
  const variable = roundUpPerKm(route.distanceMeters, config.perKmMinor);
  const feeMinor = Math.max(config.minimumFeeMinor, config.baseFeeMinor + variable);
  const platformCommissionMinor = roundBps(feeMinor, config.platformCommissionBps);
  return { feeMinor, platformCommissionMinor, courierEarningsMinor: feeMinor - platformCommissionMinor };
}
export function calculateRideFare(config: PricingConfig["ride"], route: RouteMetrics): { fareMinor: number; platformCommissionMinor: number; driverEarningsMinor: number } {
  assertMinor(config.baseFareMinor, "ride.baseFareMinor");
  assertMinor(config.minimumFareMinor, "ride.minimumFareMinor");
  assertMinor(config.perKmMinor, "ride.perKmMinor");
  assertMinor(config.perMinuteMinor, "ride.perMinuteMinor");
  assertBps(config.platformCommissionBps, "ride.platformCommissionBps");
  if (!Number.isSafeInteger(route.durationSeconds) || route.durationSeconds < 0) throw new Error("durationSeconds must be a non-negative integer.");
  const distanceCharge = roundUpPerKm(route.distanceMeters, config.perKmMinor);
  const minutes = Math.ceil(route.durationSeconds / 60);
  const timeCharge = minutes * config.perMinuteMinor;
  assertMinor(timeCharge, "ride.timeCharge");
  const fareMinor = Math.max(config.minimumFareMinor, config.baseFareMinor + distanceCharge + timeCharge);
  const platformCommissionMinor = roundBps(fareMinor, config.platformCommissionBps);
  return { fareMinor, platformCommissionMinor, driverEarningsMinor: fareMinor - platformCommissionMinor };
}
export function calculateOrderBreakdown(args: {
  config: PricingConfig;
  service: "FOOD" | "SHOP";
  subtotalMinor: number;
  route: RouteMetrics;
  paymentMethod: PaymentMethod;
  discount?: DiscountRule;
  now?: number;
}): MoneyBreakdown {
  const { config, service, subtotalMinor, route, paymentMethod } = args;
  assertMinor(subtotalMinor, "subtotalMinor");
  const delivery = calculateDeliveryFee(config.delivery, route);
  const discount = applyDiscount(subtotalMinor, args.discount, args.now);
  const commissionRate = service === "FOOD" ? config.food.commissionBps : config.shop.commissionBps;
  assertBps(commissionRate, `${service.toLowerCase()}.commissionBps`);
  const commissionBase = subtotalMinor - discount.merchant;
  const merchantCommissionMinor = roundBps(commissionBase, commissionRate);
  const beforeProvider = subtotalMinor + delivery.feeMinor - discount.total;
  const fee = providerFee(config, paymentMethod, beforeProvider);
  const customerTotalMinor = beforeProvider + (fee.payer === "CUSTOMER" ? fee.amount : 0);
  const platformNet = merchantCommissionMinor + delivery.platformCommissionMinor
    - discount.platform - (fee.payer === "PLATFORM" ? fee.amount : 0);
  return {
    currency: "PHP",
    subtotalMinor,
    deliveryFeeMinor: delivery.feeMinor,
    discountMinor: discount.total,
    merchantDiscountMinor: discount.merchant,
    platformDiscountMinor: discount.platform,
    providerFeeMinor: fee.amount,
    customerTotalMinor,
    merchantCommissionMinor,
    deliveryCommissionMinor: delivery.platformCommissionMinor,
    platformCommissionMinor: merchantCommissionMinor + delivery.platformCommissionMinor,
    merchantEarningsMinor: commissionBase - merchantCommissionMinor,
    courierEarningsMinor: delivery.courierEarningsMinor,
    platformNetBeforeOtherCostsMinor: platformNet,
  };
}
export function calculateDeliveryOnlyBreakdown(args: {
  config: PricingConfig;
  route: RouteMetrics;
  paymentMethod: PaymentMethod;
}): { currency: "PHP"; deliveryFeeMinor: number; providerFeeMinor: number; customerTotalMinor: number; platformCommissionMinor: number; courierEarningsMinor: number; platformNetBeforeOtherCostsMinor: number } {
  const delivery = calculateDeliveryFee(args.config.delivery, args.route);
  const fee = providerFee(args.config, args.paymentMethod, delivery.feeMinor);
  return {
    currency: "PHP",
    deliveryFeeMinor: delivery.feeMinor,
    providerFeeMinor: fee.amount,
    customerTotalMinor: delivery.feeMinor + (fee.payer === "CUSTOMER" ? fee.amount : 0),
    platformCommissionMinor: delivery.platformCommissionMinor,
    courierEarningsMinor: delivery.courierEarningsMinor,
    platformNetBeforeOtherCostsMinor: delivery.platformCommissionMinor - (fee.payer === "PLATFORM" ? fee.amount : 0),
  };
}
export function decimalPesosToMinor(value: unknown, field: string): number {
  const text = typeof value === "number" && Number.isFinite(value) ? value.toFixed(2) : String(value ?? "");
  if (!/^\d+(?:\.\d{1,2})?$/.test(text)) throw new Error(`${field} is not a valid PHP amount.`);
  const [pesos, centavos = ""] = text.split(".");
  const result = Number(BigInt(pesos) * 100n + BigInt((centavos + "00").slice(0, 2)));
  assertMinor(result, field);
  return result;
}
