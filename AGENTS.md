# AGENTS.md

## Project overview

Labmda is an Android application written in Kotlin. The UI uses Jetpack Compose and Material 3; navigation uses Voyager; dependency injection uses Koin; Firebase is accessed through the GitLive Kotlin SDK. Build configuration is Gradle Kotlin DSL with a version catalog in `gradle/libs.versions.toml`.

## Module map

- `:app` — Android application entry points and platform configuration. Start at `app/src/main/java/com/zouitel/labmda/MainActivity.kt` and `LambdaApplication.kt`.
- `:presentation` — Compose screens, Voyager ScreenModels, UI models, shared UI utilities, and presentation DI. The existing package/namespace is spelled `com.zouitel.presentaion`; preserve it in changes unless a deliberate package migration is requested.
- `:domain` — domain models, repository contracts, and use cases.
- `:core:repository` — repository implementations, data-source contracts, mapping, and repository DI.
- `:core:datastore` — DataStore-backed settings implementation, settings mapping, and related DI.
- `gradle/libs.versions.toml` — central dependency and plugin version catalog.

Check `settings.gradle.kts` and the relevant module `build.gradle.kts` before adding dependencies or changing module boundaries.

## Build and test

Use the Gradle wrapper from the repository root:

- Debug APK build: `./gradlew assembleDebug`
- Unit tests: `./gradlew testDebugUnitTest`
- Full Gradle build: `./gradlew build`

The CI workflow uses JDK 17 and runs `assembleDebug` followed by `testDebugUnitTest`. Run the narrowest relevant tests during iteration, then the CI commands when practical. If a command cannot run because the environment lacks the Android SDK, required secrets, or network access, report that limitation rather than claiming it passed.

## Implementation guidance

- Follow existing Kotlin style, package names, file organization, and nearby patterns; avoid unrelated reformatting.
- Keep Compose UI declarative. Put non-trivial screen state and UI actions in the existing Voyager ScreenModel pattern where appropriate, and keep reusable UI pieces in the established shared/common locations.
- Follow the current separation between presentation, domain contracts/use cases, and core data implementations. Avoid adding Firebase or platform-specific details to domain code.
- Use Koin modules for dependency registration, following the existing DI setup. Check existing module wiring before introducing another registration path.
- Add or update tests for behavior changes. Do not rely on the placeholder example tests as meaningful coverage.
- Add dependencies through `gradle/libs.versions.toml` and the appropriate module build file when possible; avoid hard-coding versions elsewhere if the catalog can represent them.
- Preserve existing Android SDK and Java compatibility settings unless the task requires changing them. Current CI expects JDK 17.
- Keep changes scoped to the requested task. Do not rename modules, package namespaces, or public APIs as incidental cleanup.

## Configuration and security

- Treat Firebase configuration, OAuth client identifiers, tokens, signing files, and other credentials as sensitive configuration. Do not introduce new secrets, print credentials in logs, or copy secrets into documentation, tests, or commits.
- Do not change `app/google-services.json` or authentication setup unless the task explicitly concerns that configuration.
- Never report a build, test, or runtime check as successful unless it was actually executed and passed.
