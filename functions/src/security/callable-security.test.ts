import { readdirSync, readFileSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { describe, expect, it } from "vitest";

const sourceRoot = resolve(process.cwd(), "src");

function typescriptSources(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return typescriptSources(path);
    if (!entry.isFile() || !entry.name.endsWith(".ts") || entry.name.endsWith(".test.ts")) return [];
    return [path];
  });
}

describe("callable security baseline", () => {
  it("requires App Check on every Firebase callable declaration", () => {
    const missing: string[] = [];

    for (const path of typescriptSources(sourceRoot)) {
      const source = readFileSync(path, "utf8");
      const declarations = [...source.matchAll(/\bonCall\s*\(\s*\{([\s\S]*?)\}\s*,/g)];
      for (const declaration of declarations) {
        const options = declaration[1] ?? "";
        if (!/\benforceAppCheck\s*:\s*true\b/.test(options)) {
          missing.push(relative(sourceRoot, path));
        }
      }
    }

    expect(missing, "Callable declarations missing enforceAppCheck: true").toEqual([]);
  });

  it("keeps privileged financial/payment modules behind the admin custom claim", () => {
    const guardedModules = [
      "payments/payment-config.ts",
      "payments/bdo-payment-operations.ts",
      "finance/admin-financial.ts",
      "finance/admin-pricing.ts",
      "finance/admin-overview.ts",
      "finance/bdo-settlement.ts",
      "finance/cash-operations.ts",
      "finance/payouts.ts",
      "operations/provider-approvals.ts",
    ];

    for (const modulePath of guardedModules) {
      const source = readFileSync(join(sourceRoot, modulePath), "utf8");
      expect(
        /token\?\.admin\s*!==\s*true|admin\(call\)|requireAdmin\(/.test(source),
        modulePath,
      ).toBe(true);
    }
  });
});