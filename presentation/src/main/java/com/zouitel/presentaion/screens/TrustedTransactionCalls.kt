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
