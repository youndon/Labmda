import { createRequire } from "node:module";
import { describe, expect, it } from "vitest";

const require = createRequire(import.meta.url);
const { writeDocument, writeDocuments } = require("./financial-migration-writer.cjs") as {
  writeDocument: (firestore: FakeFirestore, ref: FakeRef, value: unknown) => Promise<void>;
  writeDocuments: (firestore: FakeFirestore, collection: string, values: Array<[string, unknown]>) => Promise<void>;
};

type FakeRef = { path: string; id: string };
type FakeFirestore = {
  documents: Map<string, unknown>;
  runTransaction: (work: (transaction: {
    get: (ref: FakeRef) => Promise<{ exists: boolean; data: () => unknown }>;
    create: (ref: FakeRef, value: unknown) => void;
  }) => Promise<void>) => Promise<void>;
  collection: (name: string) => { doc: (id: string) => FakeRef };
};

function fakeFirestore(): FakeFirestore {
  const documents = new Map<string, unknown>();
  return {
    documents,
    collection: (name) => ({ doc: (id) => ({ id, path: `${name}/${id}` }) }),
    runTransaction: async (work) => work({
      get: async (ref) => ({
        exists: documents.has(ref.path),
        data: () => documents.get(ref.path),
      }),
      create: (ref, value) => {
        if (documents.has(ref.path)) throw new Error(`Already exists: ${ref.path}`);
        documents.set(ref.path, value);
      },
    }),
  };
}

describe("financial migration recovery writer", () => {
  it("creates missing documents and safely skips identical documents on retry", async () => {
    const firestore = fakeFirestore();
    const value = { operationId: "op_1", postings: [{ accountId: "wallet_1", deltaMinor: 500 }] };
    const ref = firestore.collection("financialOperations").doc("op_1");
    await writeDocument(firestore, ref, value);
    await expect(writeDocument(firestore, ref, value)).resolves.toBeUndefined();
    expect(firestore.documents.size).toBe(1);
    expect(firestore.documents.get("financialOperations/op_1")).toEqual(value);
  });

  it("stops rather than overwriting a conflicting financial document", async () => {
    const firestore = fakeFirestore();
    const ref = firestore.collection("financialAccounts").doc("wallet_1");
    await writeDocument(firestore, ref, { availableMinor: 500 });
    await expect(writeDocument(firestore, ref, { availableMinor: 400 }))
      .rejects.toThrow(/Migration recovery conflict/);
    expect(firestore.documents.get("financialAccounts/wallet_1")).toEqual({ availableMinor: 500 });
  });

  it("resumes a partially imported collection without duplicating matching records", async () => {
    const firestore = fakeFirestore();
    const records: Array<[string, unknown]> = [
      ["operation_1", { createdAt: 100, amountMinor: 500 }],
      ["operation_2", { createdAt: 200, amountMinor: 700 }],
    ];
    await writeDocument(firestore, firestore.collection("financialOperations").doc("operation_1"), records[0]![1]);
    await writeDocuments(firestore, "financialOperations", records);
    expect(firestore.documents.size).toBe(2);
    expect(firestore.documents.get("financialOperations/operation_1")).toEqual(records[0]![1]);
    expect(firestore.documents.get("financialOperations/operation_2")).toEqual(records[1]![1]);
  });
});
