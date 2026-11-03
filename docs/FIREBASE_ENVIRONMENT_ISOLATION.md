# Labmda Firebase and Maps environment isolation

## Current repository state

The current `app/google-services.json` points to the shared Firebase project
`lateral-command-305601`. It registers `com.zouitel.labmda` alongside unrelated
Android applications. This means Labmda currently shares the Firebase project's
project-level resources, quotas and configuration boundaries. App Check,
Authentication, Realtime Database and Functions are not isolated from those
other apps by having a distinct Android package registration.

The repository does not yet contain separate Labmda development and production
Firebase projects. Do not copy the existing JSON into two environment folders:
that would still connect both builds to the same Firebase project.

## Required Firebase setup (manual Google Cloud/Firebase Console work)

Create two new Firebase projects:

- `labmda-dev` — development and testing only.
- `labmda-prod` — production only.

For **each** project:

1. Register an Android app with package name `com.zouitel.labmda`.
2. Enable only the Firebase products Labmda actually uses: Authentication,
   Realtime Database, Cloud Functions and any other required product.
3. Configure Authentication providers and authorized domains independently.
4. Create the Realtime Database in the intended region and deploy the reviewed
   production rules to the production project. Do not copy permissive test rules.
5. Deploy Functions independently and configure that environment's secrets.
   Use separate Xendit credentials/webhook configuration for sandbox and live
   environments; never put server credentials in the Android app.
6. Register App Check separately. Use the debug provider only for development
   builds and Play Integrity for production. Do not register debug tokens as
   production devices.
7. Download each project's Android configuration file.

Once both projects exist, the intended Android source layout is:

- `app/src/dev/google-services.json`
- `app/src/prod/google-services.json`

Then add `dev` and `prod` product flavors and verify the Google Services
plugin selects the matching file for each variant. Until both real files are
available, the flavors are intentionally not enabled: enabling them now would
either break CI or silently keep one environment pointed at the shared project.

## Google Maps Android key

The Maps key has been removed from the checked-in manifest. Supply it locally
through a Gradle property or environment variable named
`LABMDA_MAPS_API_KEY`.

For local development, add this to the user's untracked
`~/.gradle/gradle.properties`:

```properties
LABMDA_MAPS_API_KEY=YOUR_RESTRICTED_ANDROID_MAPS_KEY
```

For CI, store the value as a protected repository/environment secret and expose
it to Gradle as the `LABMDA_MAPS_API_KEY` environment variable. Do not commit
the key in `gradle.properties`, `local.properties`, manifests, or source.

In Google Cloud Console, restrict the Android key as follows:

- Application restriction: **Android apps**.
- Package: `com.zouitel.labmda`.
- SHA-1: add the debug signing certificate for development and the actual
  Play App Signing/release certificate SHA-1 used by production.
- API restriction: **Maps SDK for Android** only, unless a separately required
  Google API needs its own dedicated key.

The key previously committed in `presentation/src/main/AndroidManifest.xml`
must be rotated/replaced in Google Cloud Console. Removing it from the current
manifest does not erase it from Git history or invalidate the old key.

## Release gate

Do not release Labmda against `lateral-command-305601`. Before production
release, verify that the production build resolves only `labmda-prod`, the
development build resolves only `labmda-dev`, and the Maps key is restricted
to the production signing certificate and required APIs.
