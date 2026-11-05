import { createHash } from "node:crypto";

/**
 * Stable, case-insensitive key for external payment/refund/closure references.
 * Keep the original reference separately for audit/display; use this key only
 * for uniqueness indexes.
 */
export function providerReferenceIndexKey(reference: string): string {
  return createHash("sha256")
    .update(reference.trim().toUpperCase())
    .digest("hex");
}
