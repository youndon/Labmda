const { after, before, beforeEach, test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const {
  equalTo,
  get,
  orderByChild,
  query,
  ref,
  set,
  update,
} = require("firebase/database");

const PROJECT_ID = "demo-labmda";
const RULES_PATH = path.resolve(__dirname, "../../database.emulator.rules.json");

let testEnv;

before(async () => {
  const rules = JSON.parse(fs.readFileSync(RULES_PATH, "utf8"));
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    database: {
      host: "127.0.0.1",
      port: 9000,
      rules: JSON.stringify(rules.rules),
    },
  });
});

beforeEach(async () => {
  await testEnv.clearDatabase();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.database();
    await set(ref(db), {
      users: {
        "client-a": { uid: "client-a", fullName: "Client A", role: "CLIENT" },
        "client-b": { uid: "client-b", fullName: "Client B", role: "CLIENT" },
        "merchant-a": { uid: "merchant-a", foodStatus: "APPROVED" },
      },
      orders: {
        "order-a": {
          clientId: "client-a",
          merchantId: "merchant-a",
          sourceId: "merchant-a",
          deliveryId: "",
          status: "PENDING",
        },
        "order-b": {
          clientId: "client-b",
          merchantId: "merchant-a",
          sourceId: "merchant-a",
          deliveryId: "",
          status: "PENDING",
        },
      },
      trips: {
        "trip-a": { passengerId: "client-a", driverId: "driver-a", status: "REQUESTED" },
        "trip-b": { passengerId: "client-b", driverId: "driver-b", status: "REQUESTED" },
      },
      payments: {
        "order-a": { clientId: "client-a", merchantId: "merchant-a", status: "PENDING" },
        "order-b": { clientId: "client-b", merchantId: "merchant-a", status: "PENDING" },
      },
      wallets: {
        "client-a": { available: 100, pending: 0 },
      },
    });
  });
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

test("unauthenticated users cannot read private user profiles", async () => {
  const db = testEnv.unauthenticatedContext().database();
  await assertFails(get(ref(db, "users/client-a")));
});

test("a user can read their own profile but not another user's profile", async () => {
  const ownDb = testEnv.authenticatedContext("client-a").database();
  const otherDb = testEnv.authenticatedContext("client-b").database();

  await assertSucceeds(get(ref(ownDb, "users/client-a")));
  await assertFails(get(ref(otherDb, "users/client-a")));
});

test("clients can query only their own orders", async () => {
  const db = testEnv.authenticatedContext("client-a").database();
  const ownOrders = query(ref(db, "orders"), orderByChild("clientId"), equalTo("client-a"));

  const snapshot = await assertSucceeds(get(ownOrders));
  assert.deepEqual(snapshot.exists() ? Object.keys(snapshot.val()) : [], ["order-a"]);
  await assertFails(get(ref(db, "orders/order-b")));
});

test("clients cannot write order or payment state directly", async () => {
  const db = testEnv.authenticatedContext("client-a").database();

  await assertFails(update(ref(db, "orders/order-a"), { status: "DELIVERED" }));
  await assertFails(set(ref(db, "payments/order-a/status"), "PAID"));
});

test("passengers can query only their own trips", async () => {
  const db = testEnv.authenticatedContext("client-a").database();
  const ownTrips = query(ref(db, "trips"), orderByChild("passengerId"), equalTo("client-a"));

  const snapshot = await assertSucceeds(get(ownTrips));
  assert.deepEqual(snapshot.exists() ? Object.keys(snapshot.val()) : [], ["trip-a"]);
  await assertFails(get(ref(db, "trips/trip-b")));
});

test("ordinary users cannot change wallet balances", async () => {
  const db = testEnv.authenticatedContext("client-a").database();
  await assertFails(set(ref(db, "wallets/client-a/available"), 999999));
});

test("administrator custom claims can read marketplace records", async () => {
  const db = testEnv.authenticatedContext("admin-a", { admin: true }).database();

  await assertSucceeds(get(ref(db, "users/client-a")));
  await assertSucceeds(get(ref(db, "orders/order-b")));
  await assertSucceeds(get(ref(db, "trips/trip-b")));
});
