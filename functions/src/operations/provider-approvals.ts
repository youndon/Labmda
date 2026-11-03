import { getDatabase } from "firebase-admin/database";
import { randomUUID } from "node:crypto";
import { HttpsError, onCall } from "firebase-functions/v2/https";

const db = () => getDatabase();
type ProviderService = "RESTAURANT" | "SHOP" | "DRIVER" | "DELIVERY" | "FREIGHT";
type ProviderStatus = "APPROVED" | "REJECTED" | "SUSPENDED";

function requireSignedIn(uid?: string): string {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to continue.");
  return uid;
}

function requireAdmin(token: Record<string, unknown> | undefined): void {
  if (token?.admin !== true) {
    throw new HttpsError("permission-denied", "Administrator access is required.");
  }
}

function validId(value: unknown): value is string {
  return typeof value === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(value);
}

function parseStatus(value: unknown): ProviderStatus {
  if (value !== "APPROVED" && value !== "REJECTED" && value !== "SUSPENDED") {
    throw new HttpsError("invalid-argument", "Status must be APPROVED, REJECTED or SUSPENDED.");
  }
  return value;
}

export const submitRestaurantApplication = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireSignedIn(call.auth?.uid);
  const data = call.data as Record<string, unknown>;
  const restaurantName = typeof data.restaurantName === "string" ? data.restaurantName.trim() : "";
  const imageUrl = typeof data.imageUrl === "string" ? data.imageUrl.trim() : "";
  const managerPhone = typeof data.managerPhone === "string" ? data.managerPhone.trim() : "";
  const address = typeof data.address === "string" ? data.address.trim() : "";
  const deliveryFee = Number(data.deliveryFee);
  const estimatedTimeMinutes = Number(data.estimatedTimeMinutes);
  const latitude = Number(data.latitude);
  const longitude = Number(data.longitude);

  if (restaurantName.length < 2 || restaurantName.length > 100
    || managerPhone.length < 5 || managerPhone.length > 30
    || address.length < 5 || address.length > 300
    || !Number.isFinite(deliveryFee) || deliveryFee < 0 || deliveryFee > 10000
    || !Number.isSafeInteger(estimatedTimeMinutes) || estimatedTimeMinutes < 1 || estimatedTimeMinutes > 600
    || !Number.isFinite(latitude) || latitude < -90 || latitude > 90
    || !Number.isFinite(longitude) || longitude < -180 || longitude > 180
    || (imageUrl && !/^https:\/\//i.test(imageUrl))) {
    throw new HttpsError("invalid-argument", "Restaurant application details are invalid.");
  }

  const userRef = db().ref(`users/${uid}`);
  const existing = await userRef.get();
  const currentStatus = existing.child("foodStatus").val();
  if (currentStatus === "APPROVED" || currentStatus === "PENDING") {
    throw new HttpsError("failed-precondition", "A restaurant application is already active.");
  }

  await userRef.update({
    sourceName: restaurantName,
    restaurantImageUrl: imageUrl,
    deliveryFee,
    estimatedTimeMinutes,
    managerPhone,
    restaurantAddress: address,
    restaurantLatitude: latitude,
    restaurantLongitude: longitude,
    foodStatus: "PENDING",
    foodMode: "CLIENT",
    foodApplicationSubmittedAt: Date.now(),
  });
  return { submitted: true, status: "PENDING" };
});

 
export const submitShopApplication = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireSignedIn(call.auth?.uid);
  const data = call.data as Record<string, unknown>;
  const name = typeof data.name === "string" ? data.name.trim() : "";
  const description = typeof data.description === "string" ? data.description.trim() : "";
  const category = typeof data.category === "string" ? data.category.trim() : "";
  const phone = typeof data.phone === "string" ? data.phone.trim() : "";
  const address = typeof data.address === "string" ? data.address.trim() : "";
  const imageUrl = typeof data.imageUrl === "string" ? data.imageUrl.trim() : "";
  const latitude = data.latitude;
  const longitude = data.longitude;
  if (name.length < 2 || name.length > 100 || description.length > 1000
    || category.length < 2 || category.length > 60 || phone.length < 5 || phone.length > 30
    || address.length < 5 || address.length > 300
    || typeof latitude !== "number" || !Number.isFinite(latitude) || latitude < -90 || latitude > 90
    || typeof longitude !== "number" || !Number.isFinite(longitude) || longitude < -180 || longitude > 180
    || (imageUrl && !/^https:\/\//i.test(imageUrl))) {
    throw new HttpsError("invalid-argument", "Shop application details are invalid.");
  }
  const existing = await db().ref("shops").orderByChild("ownerUid").equalTo(uid).get();
  if (existing.exists()) {
    let activeApplication = false;
    existing.forEach((child) => {
      const status = child.child("status").val();
      if (status === "PENDING" || status === "APPROVED") activeApplication = true;
    });
    if (activeApplication) throw new HttpsError("failed-precondition", "You already have an active shop application.");
  }
  const shopId = randomUUID();
  await db().ref(`shops/${shopId}`).set({
    id: shopId, ownerUid: uid, name, description, category, phone, address, imageUrl,
    latitude, longitude,
    status: "PENDING", createdAtTimestamp: Date.now(), isOnline: false,
  });
  await db().ref(`users/${uid}`).update({ shopStatus: "PENDING", shopMode: "CLIENT" });
  return { shopId, status: "PENDING" };
});

export const setShopMode = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireSignedIn(call.auth?.uid);
  const mode = (call.data as { mode?: unknown }).mode;
  if (mode !== "CLIENT" && mode !== "MANAGER") {
    throw new HttpsError("invalid-argument", "Shop mode must be CLIENT or MANAGER.");
  }
  const userRef = db().ref(`users/${uid}`);
  if (mode === "MANAGER") {
    const status = (await userRef.child("shopStatus").get()).val();
    if (status !== "APPROVED") throw new HttpsError("permission-denied", "Shop manager access requires an approved application.");
  }
  await userRef.child("shopMode").set(mode);
  return { mode };
});

export const setFoodMode = onCall({ enforceAppCheck: true }, async (call) => {
  const uid = requireSignedIn(call.auth?.uid);
  const mode = (call.data as { mode?: unknown }).mode;
  if (mode !== "CLIENT" && mode !== "MANAGER") {
    throw new HttpsError("invalid-argument", "Food mode must be CLIENT or MANAGER.");
  }
  const userRef = db().ref(`users/${uid}`);
  if (mode === "MANAGER") {
    const status = (await userRef.child("foodStatus").get()).val();
    if (status !== "APPROVED") {
      throw new HttpsError("permission-denied", "Restaurant manager access requires an approved application.");
    }
  }
  await userRef.child("foodMode").set(mode);
  return { mode };
});

export const adminUpdateProviderStatus = onCall({ enforceAppCheck: true }, async (call) => {
  requireSignedIn(call.auth?.uid);
  requireAdmin(call.auth?.token as Record<string, unknown> | undefined);

  const data = call.data as Record<string, unknown>;
  const service = data.service as ProviderService;
  const uid = data.uid;
  const status = parseStatus(data.status);
  if (!validId(uid)) throw new HttpsError("invalid-argument", "Provider user ID is invalid.");
  if (!["RESTAURANT", "SHOP", "DRIVER", "DELIVERY", "FREIGHT"].includes(service)) {
    throw new HttpsError("invalid-argument", "Provider service is invalid.");
  }

  const userRef = db().ref(`users/${uid}`);
  const user = await userRef.get();
  if (!user.exists()) throw new HttpsError("not-found", "Provider profile was not found.");

  const updates: Record<string, unknown> = {};
  const now = Date.now();
  const statusValue = status;

  if (service === "RESTAURANT") {
    updates[`users/${uid}/foodStatus`] = statusValue;
    updates[`users/${uid}/foodMode`] = status === "APPROVED" ? "MANAGER" : "CLIENT";
    updates[`users/${uid}/foodReviewedAt`] = now;
    updates[`users/${uid}/foodReviewedBy`] = call.auth!.uid;
    if (status === "APPROVED") {
      updates[`restaurants/${uid}`] = {
        id: uid,
        name: String(user.child("sourceName").val() ?? "Restaurant"),
        imageUrl: String(user.child("restaurantImageUrl").val() ?? ""),
        rating: 5.0,
        deliveryFee: Number(user.child("deliveryFee").val() ?? 0),
        estimatedTimeMinutes: Number(user.child("estimatedTimeMinutes").val() ?? 20),
        isOpen: false,
        managers: [String(user.child("managerPhone").val() ?? "")].filter(Boolean),
        paymentMethods: ["Cash on Delivery", "Card on Delivery"],
        address: String(user.child("restaurantAddress").val() ?? ""),
        latitude: Number(user.child("restaurantLatitude").val() ?? 0),
        longitude: Number(user.child("restaurantLongitude").val() ?? 0),
        status: "APPROVED",
      };
    } else if (status === "REJECTED") {
      updates[`restaurants/${uid}`] = null;
    } else {
      updates[`restaurants/${uid}/status`] = "SUSPENDED";
      updates[`restaurants/${uid}/isOpen`] = false;
    }
  } else if (service === "SHOP") {
    if (!validId(data.shopId)) throw new HttpsError("invalid-argument", "Shop ID is invalid.");
    const shopRef = db().ref(`shops/${data.shopId}`);
    const shop = await shopRef.get();
    if (!shop.exists() || shop.child("ownerUid").val() !== uid) {
      throw new HttpsError("failed-precondition", "The shop does not belong to this provider.");
    }
    updates[`shops/${data.shopId}/status`] = statusValue;
    if (status !== "APPROVED") updates[`shops/${data.shopId}/isOnline`] = false;
    updates[`users/${uid}/shopStatus`] = statusValue;
    updates[`users/${uid}/shopMode`] = status === "APPROVED" ? "MANAGER" : "CLIENT";
  } else if (service === "DRIVER") {
    if (!user.child("driverService").exists()) throw new HttpsError("failed-precondition", "Driver application was not found.");
    updates[`users/${uid}/driverService/status`] = statusValue;
  } else if (service === "DELIVERY") {
    if (!user.child("deliveryServices").exists()) throw new HttpsError("failed-precondition", "Delivery application was not found.");
    updates[`users/${uid}/deliveryServices/status`] = statusValue;
  } else {
    updates[`users/${uid}/freightStatus`] = statusValue;
  }

  updates[`admin_audit_logs/provider_approvals/${now}_${uid}`] = {
    actorUid: call.auth!.uid, targetUid: uid, service, status, createdAt: now,
  };
  await db().ref().update(updates);
  return { uid, service, status };
});
