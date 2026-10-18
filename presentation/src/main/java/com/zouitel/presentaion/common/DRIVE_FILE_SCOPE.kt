package com.zouitel.presentaion.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.mmk.kmpauth.google.GoogleUser
import com.mmk.kmpauth.google.rememberGoogleSignInState
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Only files created by this app are visible to it. Non-sensitive scope, no Google review needed. */
private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/**
 * Keeps the Drive access token for a while so the user isn't sent through Google's sign-in
 * sheet on every photo. Google access tokens live about an hour; we reuse one for 50 minutes.
 * Tied to the signed-in Firebase user, so switching accounts never reuses someone else's token.
 */
internal object DriveTokenCache {
    private class Entry(val owner: String?, val token: String, val issued: TimeMark)

    @Volatile
    private var entry: Entry? = null

    fun get(owner: String?): String? {
        val e = entry ?: return null
        return e.token.takeIf { e.owner == owner && e.issued.elapsedNow() < 50.minutes }
    }

    fun put(owner: String?, token: String) {
        entry = Entry(owner, token, TimeSource.Monotonic.markNow())
    }

    fun clear() {
        entry = null
    }
}

/**
 * Gets a Google Drive access token through KMPAuth's Google sign-in (kmpauth-google).
 *
 * - First time: Google shows its "allow this app to create files in your Drive" screen.
 * - Later: the cached token is reused, then refreshed through the same (usually silent) flow.
 *
 * Create it with [rememberDriveAuthorizer]. It only fetches a credential; it does not touch your
 * Firebase session.
 */
class DriveAuthorizer internal constructor() {

    internal var launchSignIn: (() -> Unit)? = null

    @Volatile
    private var pending: CompletableDeferred<String>? = null

    suspend fun getAccessToken(): String {
        val owner = Firebase.auth.currentUser?.uid
        DriveTokenCache.get(owner)?.let { return it }

        val deferred = CompletableDeferred<String>()
        pending?.cancel()
        pending = deferred

        withContext(Dispatchers.Main.immediate) {
            val launch = launchSignIn
                ?: throw DriveUploadException(
                    "The screen is no longer active. Please try again."
                )
            launch()
        }

        val token = deferred.await()
        DriveTokenCache.put(owner, token)
        return token
    }

    internal fun onResult(result: Result<GoogleUser>) {
        val deferred = pending ?: return
        pending = null

        result
            .onSuccess { user ->
                val token = user.accessToken
                if (token.isNullOrBlank()) {
                    deferred.completeExceptionally(
                        DriveUploadException(
                            "Google didn't return a Drive access token."
                        )
                    )
                } else {
                    deferred.complete(token)
                }
            }
            .onFailure { error ->
                deferred.completeExceptionally(
                    DriveUploadException(
                        friendlyMessage(error),
                        error
                    )
                )
            }
    }

    internal fun cancelPending() {
        pending?.cancel()
        pending = null
    }
}

@Composable
fun rememberDriveAuthorizer(): DriveAuthorizer {
    val authorizer = remember { DriveAuthorizer() }

    val signIn = rememberGoogleSignInState(
        requestAccessToken = true,
        scopes = listOf("email", "profile", DRIVE_FILE_SCOPE),
        // Skip the account chooser when the user already signed in to this app with one account
        filterByAuthorizedAccounts = true,
        isAutoSelectEnabled = true,
        onResult = { result -> authorizer.onResult(result) }
    )

    DisposableEffect(authorizer, signIn) {
        authorizer.launchSignIn = { signIn.launch() }
        onDispose {
            authorizer.launchSignIn = null
            authorizer.cancelPending()
        }
    }

    return authorizer
}

/**
 * KMPAuth reports typed failures; they're matched by name here so this file doesn't depend on
 * their package. Swap in `is` checks if you prefer.
 */
private fun friendlyMessage(error: Throwable): String {
    val raw = error.message.orEmpty()
    return when {
        error::class.simpleName == "KMPAuthUserCancelledException" ->
            "Google Drive access was not granted."
        error::class.simpleName == "KMPAuthNetworkException" ->
            "No internet connection. Please try again."
        error::class.simpleName == "KMPAuthNoAccountAvailableException" ->
            "Add a Google account to this device to upload photos."
        error::class.simpleName == "KMPAuthProviderUnavailableException" ->
            "Google Play services is missing or out of date on this device."
        "Developer console is not set up correctly" in raw || "DEVELOPER_ERROR" in raw ->
            "Google isn't configured for this build yet (check the Android OAuth client's package name and SHA-1)."
        raw.isNotBlank() -> raw
        else -> "Couldn't get Google Drive access."
    }
}