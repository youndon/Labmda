import { onValueWritten } from "firebase-functions/v2/database";
import { getDatabase } from "firebase-admin/database";

/**
 * Publishes only the minimum approved/online driver data needed by the
 * passenger map. Private profile fields and phone numbers are never copied.
 */
export const syncPublicDriverPresence = onValueWritten(
  { ref: "/users/{uid}" },
  async (event) => {
    const uid = event.params.uid;
    const user = event.data.after.val() as Record<string, any> | null;
    const driver = user?.driverService as Record<string, any> | undefined;
    const online = driver?.isOnline === true && driver?.status === "APPROVED";
    const presenceRef = getDatabase().ref(`driverPresence/${uid}`);
    if (!user || !online) {
      await presenceRef.remove();
      return;
    }

    const location = (user.currentLocation ?? driver.currentLocation ?? {}) as Record<string, any>;
    const latitude = Number(location.latitude);
    const longitude = Number(location.longitude);
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude)
      || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
      await presenceRef.remove();
      return;
    }
    const vehicle = (driver.vehicleInfo ?? user.vehicleInfo ?? {}) as Record<string, any>;
    await presenceRef.set({
      uid,
      fullName: String(user.fullName ?? user.name ?? "Driver"),
      latitude, longitude,
      heading: Number.isFinite(Number(location.heading)) ? Number(location.heading) : 0,
      vehicleType: String(vehicle.type ?? "CAR"),
      updatedAt: Date.now(),
    });
  },
);
