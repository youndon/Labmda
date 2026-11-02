package com.zouitel.presentaion.screens

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions

/** Thin GitLive adapter for server-authoritative order and ride mutations. */
internal suspend fun trustedTransactionCall(
    name: String,
    payload: Map<String, String>
) {
    Firebase.functions.httpsCallable(name).invoke(payload)
}

/** Returns the typed payload from a callable that creates a quote-backed resource. */
internal suspend fun trustedTransactionCallData(
    name: String,
    payload: Map<String, Any?>
): Map<String, Any?> {
    return Firebase.functions.httpsCallable(name).invoke(payload).data()
}
