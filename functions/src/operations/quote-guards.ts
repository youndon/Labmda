export function isQuoteClaimable(
  quote: Record<string, unknown> | null | undefined,
  uid: string,
  kind: string,
  now: number,
): boolean {
  return !!quote
    && quote.uid === uid
    && quote.kind === kind
    && quote.status === "QUOTED"
    && Number.isSafeInteger(quote.expiresAt)
    && Number(quote.expiresAt) > now;
}
