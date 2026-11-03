import { getDatabase } from "firebase-admin/database";
import { onValueWritten } from "firebase-functions/v2/database";

const db = () => getDatabase();
const activeRideStatuses = new Set(["REQUESTED", "ACCEPTED", "IN_PROGRESS"]);

async function publishDriverPresence(uid: string): Promise<void> {
  const [profile, locationSnapshot] = await Promise.all([
    db().ref(`users/${uid}`).get(),
    db().ref(`driverLocations/${uid}`).get(),
  ]);
  const driver = profile.child("driverService");
  const location = locationSnapshot.val() as Record<string, unknown> | null;
  const presenceRef = db().ref(`driverPresence/${uid}`);
  if (!profile.exists() || driver.child("status").val() !== "APPROVED"
    || driver.child("isOnline").val() !== true || !location) {
    await presenceRef.remove();
    return;
  }

  const latitude = Number(location.latitude);
  const longitude = Number(location.longitude);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)
    || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
    await presenceRef.remove();
    return;
  }

  const vehicle = driver.child("vehicleInfo");
  await presenceRef.set({
    uid,
    fullName: String(profile.child("fullName").val() ?? profile.child("name").val() ?? "Driver"),
    latitude,
    longitude,
    heading: Number.isFinite(Number(location.heading)) ? Number(location.heading) : 0,
    vehicleType: String(vehicle.child("type").val() ?? "CAR"),
    updatedAt: Date.now(),
  });
}

/** GPS writes are isolated from the profile tree and only update this driver's public projection. */
export const syncPublicDriverPresence = onValueWritten(
  { ref: "/driverLocations/{uid}" },
  async (event) => publishDriverPresence(event.params.uid),
);

/** Status/online changes refresh presence without listening to every profile write. */
export const syncDriverPresenceOnServiceChange = onValueWritten(
  { ref: "/users/{uid}/driverService" },
  async (event) => publishDriverPresence(event.params.uid),
);

/** Only passengers with a live ride request may read the driver-presence projection. */
export const syncActiveRideRequest = onValueWritten(
  { ref: "/trips/{tripId}" },
  async (event) => {
    const before = event.data.before.val() as Record<string, unknown> | null;
    const after = event.data.after.val() as Record<string, unknown> | null;
    const beforeUid = typeof before?.passengerId === "string" ? before.passengerId : "";
    const afterUid = typeof after?.passengerId === "string" ? after.passengerId : "";
    const tripId = event.params.tripId;
    const updates: Record<string, unknown> = {};

    if (beforeUid && (beforeUid !== afterUid || !activeRideStatuses.has(String(after?.status ?? "")))) {
      updates[`activeRideRequests/${beforeUid}/${tripId}`] = null;
    }
    if (afterUid && activeRideStatuses.has(String(after?.status ?? ""))) {
      updates[`activeRideRequests/${afterUid}/${tripId}`] = {
        status: String(after.status),
        updatedAt: Date.now(),
      };
    }
    if (Object.keys(updates).length) await db().ref().update(updates);
  },
);
