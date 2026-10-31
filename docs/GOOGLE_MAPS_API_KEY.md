# Google Maps API key setup

The Android manifest reads the Maps SDK key from the `MAPS_API_KEY` Gradle
property. Do not commit the key to the repository.

## Local development

Add the rotated key to your user-level Gradle properties file
(`~/.gradle/gradle.properties` on Linux/macOS or
`%USERPROFILE%\.gradle\gradle.properties` on Windows):

```properties
MAPS_API_KEY=YOUR_RESTRICTED_ANDROID_MAPS_KEY
```

Alternatively, provide `MAPS_API_KEY` as an environment variable when running
Gradle. In GitHub Actions, store it as a repository/environment secret and
expose it to the Gradle step as `MAPS_API_KEY`; never print it in workflow logs.

## Google Cloud Console restrictions

The previously committed key must be rotated/replaced; removing it from the
current source tree does not invalidate the exposed key or remove it from Git
history.

For the replacement key:

1. Enable **Maps SDK for Android** in the Google Cloud project.
2. Under **Application restrictions**, choose **Android apps** and add
   `com.zouitel.labmda`.
3. Add the SHA-1 signing-certificate fingerprint(s) used by each build:
   debug, locally signed release, and Google Play App Signing where applicable.
4. Under **API restrictions**, restrict the key to **Maps SDK for Android**.
5. Monitor usage and quota, and disable/delete the exposed old key after the
   replacement is verified.

The API key is necessarily packaged in the Android application. Treat it as a
public client identifier protected by Android application and API restrictions,
not as a server-side secret. Use a separate restricted key for each app/environment
where practical.
