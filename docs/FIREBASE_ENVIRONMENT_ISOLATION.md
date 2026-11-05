# Labmda Firebase and Maps environment isolation

## Android Firebase configuration

The Android app has two product flavors in `app/build.gradle.kts`:

- `development`
- `production`

Firebase configuration must be supplied by the matching Android source set:

- Development: `app/src/development/google-services.json`
- Production: `app/src/production/google-services.json`

There is deliberately no root `app/google-services.json`. A root-level file would be a fallback for a flavor missing its own configuration and could silently connect a production build to the wrong Firebase project. The root fallback has been removed. The production flavor must fail its Google Services processing/build until the real production file is added at the production path.

The current development candidate points to Firebase project `lateral-command-305601` and contains a `com.zouitel.labmda` Android client entry. Confirm in Firebase Console that this is the intended development project and replace the file with a freshly downloaded configuration for the dedicated Labmda development app. The existing file contains registrations for unrelated Android packages; use a clean app-specific download.

Before production release:

1. Create/confirm the separate production Firebase project.
2. Register Android package `com.zouitel.labmda` in that project.
3. Download its actual `google-services.json` into `app/src/production/`.
4. Verify the project ID and Android package in the file before building.
5. Build `assembleProductionRelease` and verify it resolves only the production project.

Do not copy the development file into the production source set. Do not commit placeholder or fabricated Firebase project configuration.

## Firebase backend environments

Keep development and production Firebase projects, Realtime Database instances, Functions deployments, secrets, App Check registrations and operational data separate. Configure GitHub Environments named `development` and `production` with their own project IDs and Workload Identity Federation settings. Protect production deployment with required reviewers.

The current payment integration is BDO Checkout for GCash collection using the manual checkout-link flow, with manual provider payouts. There is no Xendit integration. Do not add Xendit credentials or webhook configuration to either environment.

## Realtime Database deployment safety

There is not yet a complete, audited application RTDB ruleset. For that reason, `firebase.json` intentionally has no `database.rules` deployment mapping. A normal `firebase deploy` must not deploy the emulator's deny-all rules or either incomplete rules fragment.

The deny-all test rules are isolated in `testing/database.emulator.rules.json` and referenced only by `firebase.emulator.json` for explicit local emulator use. Those rules are a safe deny-all baseline, not a substitute for application rules and not evidence that application reads/writes pass.

Before enabling RTDB deployment, inventory all app reads, queries and writes; merge the order/ride and financial fragments into a complete least-privilege ruleset; account for profile creation/update and the full `deliveryServices` registration payload; then run emulator tests for allowed and denied operations. Do not add broad parent-level grants to make existing writes pass because RTDB grants cascade into protected child paths.

## Google Maps Android key

Supply the Android Maps key through the Gradle property or environment variable `LABMDA_MAPS_API_KEY`; do not commit it. Restrict the key in Google Cloud Console to package `com.zouitel.labmda`, the appropriate signing certificate SHA-1 fingerprints, and only the APIs required by the Android client.

The key previously committed in `presentation/src/main/AndroidManifest.xml` must be rotated/replaced in Google Cloud Console. Removing a key from the current source does not erase it from Git history or invalidate it.

## Release gate

Do not release until the production Firebase Android configuration is supplied and verified, production App Check/Play Integrity is configured, server credentials are stored in Secret Manager, RTDB rules have been completed and tested, and backups/monitoring are configured for the production project.
