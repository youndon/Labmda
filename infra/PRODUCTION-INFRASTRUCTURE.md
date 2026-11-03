# Phase 06 — Production infrastructure

This repository now includes guarded Firebase deployment workflows, but infrastructure is **not production-ready until the operator completes the project-specific Console and IAM setup below**. No Firebase project IDs, Google Cloud identities, API keys, or bank credentials are fabricated or committed.

## 1. Separate Firebase projects

Create two distinct Firebase projects:

- Development: used by local development, emulator-backed tests, and development deployment.
- Production: used only by approved production releases and real customer data.

Do not reuse the same Realtime Database, Firestore database, Storage bucket, or Functions deployment between environments.

Copy `.firebaserc.example` to `.firebaserc` and replace both placeholders with the real project IDs. The local `.firebaserc` is ignored by Git. The deploy workflows use GitHub Environment variables rather than relying on a developer's active Firebase CLI alias.

In Firebase Console, register the Android application ID `com.zouitel.labmda` separately in each Firebase project and download the matching `google-services.json`. The checked-in `app/google-services.json` currently represents one Firebase project; it is not an environment selector. Before building/releasing an Android binary for a target environment, supply that environment's matching configuration. Do not point a production release at the development Firebase project.

Restrict each Android Maps API key to the correct Android package and signing certificate and to only the APIs the app needs. A Firebase Android API key is client configuration, not a server secret; restriction is still required.

## 2. GitHub deployment environments

Create GitHub Environments named exactly:

- `development`
- `production`

For each environment, add these **environment variables**:

| Variable | Value |
|---|---|
| `FIREBASE_PROJECT_ID` | The Firebase project ID for this environment |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Full Google Workload Identity Provider resource name |
| `GCP_SERVICE_ACCOUNT` | Dedicated deployment service-account email |

Configure Workload Identity Federation so that only this repository's intended GitHub workflow identity can impersonate the dedicated deployment service account. Grant the service account only the IAM permissions needed to deploy Cloud Functions, Cloud Run/2nd-gen Functions resources, build artifacts, read the required Secret Manager versions, and deploy Firestore rules. Do not create or store a long-lived service-account JSON key.

Protect the `production` GitHub Environment with required reviewers and restrict production deployment to the protected `main` or `master` branch. The production workflow is manual-only. The development workflow deploys on pushes to `develop` and can also be run manually.

Workflows added:

- `.github/workflows/deploy-firebase-development.yml`
- `.github/workflows/deploy-firebase-production.yml`

Both workflows authenticate using GitHub OIDC / Google Workload Identity Federation, verify that the environment's Maps secret has an enabled version, install Functions dependencies, and deploy Functions plus Firestore rules. They deliberately do **not** deploy Realtime Database rules: this repository does not yet contain a complete, audited application RTDB ruleset.

## 3. Secrets

Set the Maps Routes API key independently in both Firebase projects using Secret Manager through the Firebase CLI:

```sh
firebase functions:secrets:set GOOGLE_MAPS_ROUTES_API_KEY --project <development-project-id>
firebase functions:secrets:set GOOGLE_MAPS_ROUTES_API_KEY --project <production-project-id>
```

Use separate restricted Google Routes API keys for development and production. Do not put secret values in source files, Gradle properties committed to Git, workflow YAML, or GitHub logs. Rotate a key by adding a new Secret Manager version, deploying, verifying traffic, then disabling the old version.

The BDO checkout process currently uses manually created checkout links and administrator verification. No BDO API secret, webhook signing secret, or disbursement credential is required or should be invented until BDO supplies the approved merchant contract.

## 4. App Check and Firebase Authentication

The Android app already installs Play Integrity for release builds and the Firebase App Check debug provider only in debug builds. Cloud Functions callables explicitly require App Check, and the Functions test suite includes a regression check for this boundary.

Before production launch:

1. Register the production Android app in Firebase App Check.
2. Add the SHA-256 signing certificate fingerprints for the actual Play App Signing certificate (and any other distribution certificate that will be used).
3. Confirm Play Integrity configuration for the chosen distribution channel.
4. Install a release-signed build from the intended distribution path and verify that authenticated callable requests succeed.
5. Review App Check request metrics for each Firebase product before enabling enforcement.
6. Enable App Check enforcement separately for Realtime Database, Cloud Firestore, and Cloud Storage if those products are used by the app. Keep Authentication and App Check enforcement settings aligned with the supported client flows.

App Check supplements Firebase Authentication and Security Rules; it does not replace either one. Do not enable product enforcement blindly against a live user base: unverified clients will be rejected.

## 5. Realtime Database rules — hard production blocker

The repository currently contains only:

- `database.order-ride.rules.fragment.json`
- `database.financial.rules.fragment.json`

There is no complete `database.rules.json`. The fragments must not be deployed by themselves: uncovered application paths would lose access, while broad parent grants could override protected child paths.

Before production RTDB deployment, inventory all app reads, queries, and writes; merge the fragments into one complete least-privilege ruleset; remove cascading ancestor grants that weaken protected paths; and run Realtime Database Rules Emulator tests for both allowed and denied access. Only then add the complete rules file to `firebase.json` and the deployment workflow.

The current Firestore rules deny all client access. Keep that server-only boundary unless a separately reviewed client read use case is introduced.

## 6. Backups and restore drills

Configure backups separately in both Firebase projects. Backups are not enabled by a repository commit alone.

### Realtime Database

On the Blaze plan, enable Automated Backups in Firebase Console → Databases & Storage → Realtime Database → Backups. Confirm daily backups include both database data and security rules. Configure the Cloud Storage bucket lifecycle to retain the chosen recovery window (30 days is a practical initial target), and restrict bucket access to the smallest operations group.

### Cloud Firestore

Create a daily scheduled backup for the default database and choose a retention period based on recovery requirements and cost. Firebase scheduled backups support daily or weekly schedules and retention up to 14 weeks. Consider Point-in-Time Recovery as an additional recovery option where its retention/cost trade-offs are acceptable.

### Restore drill

At least quarterly, restore a recent backup into a separate non-production project/database, verify key collections and indexes, run financial-ledger reconciliation, and record the restore duration and data-loss window. Never test restoration by overwriting the production database.

## 7. Monitoring and incident readiness

Configure Cloud Monitoring and Firebase Console alerts for both environments. At minimum, alert on:

- Cloud Functions error rate, failed invocations, elevated latency, and repeated scheduled-task failures.
- Realtime Database connection/usage limits and unexpected traffic growth.
- Firestore read/write/delete usage, quota pressure, and permission-denied spikes.
- App Check unverified/outdated request spikes.
- Cloud Billing budget thresholds and unusual spend growth.
- Failed BDO payment verification, unresolved refund cases, payout requests stuck in `RESERVING`, `REJECTING`, or `PAYING`, and unreconciled BDO statement lines.

Route production alerts to an actively monitored operations channel. Define an owner and response procedure for payment discrepancies, suspected credential exposure, payout mistakes, and database corruption. Keep Cloud Audit Logs and financial admin audit records available for investigations.

## 8. Production go-live checklist

- [ ] Separate development and production Firebase project IDs are provisioned.
- [ ] Android production Firebase app is registered for `com.zouitel.labmda` and the correct `google-services.json` is supplied to release builds.
- [ ] Maps API keys are restricted and the required Secret Manager version exists in each project.
- [ ] GitHub OIDC / Workload Identity Federation is configured with least privilege.
- [ ] Production GitHub Environment requires reviewer approval.
- [ ] Release Play Integrity attestation works; App Check metrics reviewed and product enforcement enabled.
- [ ] Complete application RTDB ruleset is audited, tested, and deployed (currently blocked).
- [ ] RTDB and Firestore backups are enabled and a restore drill has succeeded.
- [ ] Monitoring alerts and incident ownership are tested.
- [ ] BDO collection/refund/disbursement boundaries are documented; manual transfer evidence and reconciliation remain enabled until BDO supplies approved APIs.

## References

- Firebase Realtime Database automated backups: https://firebase.google.com/docs/database/backups
- Cloud Firestore scheduled backups: https://firebase.google.com/docs/firestore/backups
- Cloud Firestore point-in-time recovery: https://firebase.google.com/docs/firestore/use-pitr
- App Check request metrics: https://firebase.google.com/docs/app-check/monitor-metrics
- App Check enforcement: https://firebase.google.com/docs/app-check/enable-enforcement
- Google Cloud Workload Identity Federation for deployment pipelines: https://cloud.google.com/iam/docs/workload-identity-federation-with-deployment-pipelines
