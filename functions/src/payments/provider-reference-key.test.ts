import { describe, expect, it } from "vitest";
import { providerReferenceIndexKey } from "./provider-reference-key.js";

describe("providerReferenceIndexKey", () => {
  it("normalizes case and surrounding whitespace before hashing", () => {
    expect(providerReferenceIndexKey(" BDO-Ref-1234 "))
      .toBe(providerReferenceIndexKey("bdo-ref-1234"));
    expect(providerReferenceIndexKey("bdo-ref-1234"))
      .toBe(providerReferenceIndexKey("BDO-REF-1234"));
  });

  it("keeps different references distinct", () => {
    expect(providerReferenceIndexKey("BDO-REF-1234"))
      .not.toBe(providerReferenceIndexKey("BDO-REF-1235"));
  });
});
