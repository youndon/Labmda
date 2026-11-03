"use strict";

const { isDeepStrictEqual } = require("node:util");

/**
 * Create a migration document once, or accept an identical document from a prior
 * interrupted run. Never overwrite a target document with different financial data.
 */
async function writeDocument(firestore, ref, value) {
  await firestore.runTransaction(async (transaction) => {
    const existing = await transaction.get(ref);
    if (existing.exists) {
      if (!isDeepStrictEqual(existing.data(), value)) {
        throw new Error(`Migration recovery conflict at ${ref.path}; existing target data differs from the frozen RTDB snapshot.`);
      }
      return;
    }
    transaction.create(ref, value);
  });
}

async function writeDocuments(firestore, collection, values) {
  for (let index = 0; index < values.length; index += 20) {
    const group = values.slice(index, index + 20);
    await Promise.all(group.map(([id, value]) =>
      writeDocument(firestore, firestore.collection(collection).doc(id), value)));
  }
}

module.exports = { writeDocument, writeDocuments };
