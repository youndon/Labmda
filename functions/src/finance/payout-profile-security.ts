export function hasRecentAuthentication(
  authTimeSeconds: unknown,
  nowMs = Date.now(),
  maxAgeMs = 10 * 60 * 1000,
): boolean {
  if (!Number.isSafeInteger(authTimeSeconds) || (authTimeSeconds as number) <= 0) return false;
  const authenticatedAtMs = (authTimeSeconds as number) * 1000;
  const ageMs = nowMs - authenticatedAtMs;
  return ageMs >= 0 && ageMs <= maxAgeMs;
}
