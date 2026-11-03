import { randomUUID } from "node:crypto";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { getDatabase } from "firebase-admin/database";

export async function createRtdbEmulatorContext(seed: Record<string, unknown>) {
  const host = process.env.FIREBASE_DATABASE_EMULATOR_HOST;
  if (!host) throw new Error("FIREBASE_DATABASE_EMULATOR_HOST is required.");
  const projectId = process.env.GCLOUD_PROJECT || "demo-labmda";
  const databaseURL = `http://${host}?ns=${projectId}-default-rtdb`;
  const suffix = randomUUID();
  const seedApp = initializeApp({ projectId, databaseURL }, `seed-${suffix}`);
  const transactionApp = initializeApp({ projectId, databaseURL }, `transaction-${suffix}`);
  const verificationApp = initializeApp({ projectId, databaseURL }, `verification-${suffix}`);
  const seedDatabase = getDatabase(seedApp);
  const transactionDatabase = getDatabase(transactionApp);
  const verificationDatabase = getDatabase(verificationApp);
  await seedDatabase.ref().set(seed);
  const verifiedSeed = await verificationDatabase.ref().get();
  if (!verifiedSeed.exists()) throw new Error("The RTDB emulator seed was not visible to an independent app.");

  const observed: unknown[] = [];
  const instrumentReference = (reference: any): any => new Proxy(reference, {
    get(target, property) {
      if (property === "transaction") {
        return (update: (value: any) => any, ...args: any[]) => target.transaction(
          (value: any) => {
            observed.push(value === undefined ? undefined : structuredClone(value));
            return update(value);
          },
          ...args,
        );
      }
      const value = Reflect.get(target, property, target);
      return typeof value === "function" ? value.bind(target) : value;
    },
  });
  const instrumentedDatabase = {
    ref: (...args: any[]) => instrumentReference(transactionDatabase.ref(...args)),
  } as any;

  return {
    database: instrumentedDatabase,
    instrumentReference,
    observed,
    cleanup: async () => {
      await seedDatabase.ref().remove();
      await Promise.all([deleteApp(seedApp), deleteApp(transactionApp), deleteApp(verificationApp)]);
    },
  };
}
