export function hasActiveBdoCheckoutRequest(payment: {
  method?: unknown; provider?: unknown; status?: unknown; checkoutUrl?: unknown;
} | null | undefined): boolean {
  if (!payment || payment.method !== "GCASH" || payment.provider !== "BDO_CHECKOUT"
    || (payment.status !== "PENDING" && payment.status !== "PROCESSING")
    || typeof payment.checkoutUrl !== "string") return false;
  try {
    const url = new URL(payment.checkoutUrl);
    return url.protocol === "https:" && url.hostname === "checkout.bdo.com.ph" && url.pathname.length > 1;
  } catch {
    return false;
  }
}
