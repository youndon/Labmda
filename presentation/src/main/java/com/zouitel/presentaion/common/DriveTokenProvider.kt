package com.zouitel.presentaion.common

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

/** Supplies a fresh Drive OAuth access token. May show Google's consent screen the first time. */
typealias DriveTokenProvider = suspend () -> String

/** A photo the user picked. Bytes are read lazily so I/O errors end up in the upload state. */
class PickedImage(val readBytes: suspend () -> ByteArray)

class DriveUploadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Uploads photos to the signed-in user's own Google Drive.
 *
 * Everything goes into one app folder ([FOLDER_NAME]) in that user's Drive. The scope used is
 * `drive.file`, so the app can only ever see files it created itself.
 *
 * Each file is shared as "anyone with the link can view" so other users (customers) can see it,
 * and [upload] returns the URL to store and show later.
 */
object DriveImageUploader {

    private const val FOLDER_NAME = "RideGo Media"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

    private val client by lazy {
        HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = 60_000
                requestTimeoutMillis = 180_000
            }
        }
    }

    // Prevents two simultaneous uploads from both creating the folder
    private val folderLock = Mutex()

    /**
     * Where an uploaded file is displayed from. Kept in one place on purpose: Drive's image
     * hot-linking is undocumented, so if Google changes it (or you move to another host)
     * this is the only line to touch.
     */
    fun displayUrl(fileId: String): String =
        "https://drive.google.com/thumbnail?id=$fileId&sz=w1000"

    /** @return the URL to store and display for the uploaded photo. */
    suspend fun upload(bytes: ByteArray, accessToken: String, label: String): String {
        val mimeType = sniffImageMimeType(bytes)
        val folderId = findOrCreateFolder(accessToken)
        val fileId = uploadFile(bytes, mimeType, accessToken, folderId, label)
        makePublic(fileId, accessToken)
        return displayUrl(fileId)
    }

    private suspend fun findOrCreateFolder(token: String): String = folderLock.withLock {
        val found = client.get(FILES_URL) {
            bearerAuth(token)
            parameter("q", "mimeType='$FOLDER_MIME' and name='$FOLDER_NAME' and trashed=false")
            parameter("fields", "files(id)")
            parameter("pageSize", 1)
        }.checked("folder lookup").asJson()

        found["files"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?.let { return@withLock it }

        val metadata = buildJsonObject {
            put("name", FOLDER_NAME)
            put("mimeType", FOLDER_MIME)
        }
        client.post(FILES_URL) {
            bearerAuth(token)
            parameter("fields", "id")
            contentType(ContentType.Application.Json)
            setBody(metadata.toString())
        }.checked("folder creation").asJson().fileId()
    }

    /** Resumable upload: no 5 MB cap, so full-size phone photos work as they are. */
    private suspend fun uploadFile(
        bytes: ByteArray,
        mimeType: String,
        token: String,
        folderId: String,
        label: String
    ): String {
        val metadata = buildJsonObject {
            put("name", "$label-${Random.nextLong().toULong().toString(16)}.${extensionFor(mimeType)}")
            put("mimeType", mimeType)
            put("parents", buildJsonArray { add(folderId) })
        }

        val session = client.post(UPLOAD_URL) {
            bearerAuth(token)
            parameter("uploadType", "resumable")
            parameter("fields", "id")
            header("X-Upload-Content-Type", mimeType)
            header("X-Upload-Content-Length", bytes.size.toString())
            contentType(ContentType.Application.Json)
            setBody(metadata.toString())
        }
        session.checked("upload start")
        val sessionUrl = session.headers[HttpHeaders.Location]
            ?: throw DriveUploadException("Google Drive didn't return an upload session.")

        return client.put(sessionUrl) {
            contentType(ContentType.parse(mimeType))
            setBody(bytes)
        }.checked("upload").asJson().fileId()
    }

    private suspend fun makePublic(fileId: String, token: String) {
        val permission = buildJsonObject {
            put("role", "reader")
            put("type", "anyone")
        }
        client.post("$FILES_URL/$fileId/permissions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(permission.toString())
        }.checked("sharing")
    }

    // ---- helpers -------------------------------------------------------------------------

    private suspend fun HttpResponse.checked(action: String): String {
        val text = bodyAsText()
        if (!status.isSuccess()) {
            // Drive rejected the (possibly cached) token: drop it so the next try asks Google again
            if (status.value == 401) DriveTokenCache.clear()
            throw DriveUploadException(friendlyMessage(status.value, text, action))
        }
        return text
    }

    private fun String.asJson(): JsonObject =
        if (isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(this).jsonObject

    private fun JsonObject.fileId(): String =
        this["id"]?.jsonPrimitive?.contentOrNull
            ?: throw DriveUploadException("Google Drive response had no file id.")

    private fun friendlyMessage(code: Int, body: String, action: String): String = when {
        code == 401 ->
            "Google Drive session expired. Please try again."
        code == 403 && "storageQuotaExceeded" in body ->
            "Your Google Drive storage is full."
        code == 403 && "accessNotConfigured" in body ->
            "The Google Drive API isn't enabled for this app's Google Cloud project."
        code == 403 ->
            "Google Drive refused the request (file sharing may be restricted for this account)."
        else ->
            "Google Drive $action failed (HTTP $code)."
    }

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic" -> "heic"
        else -> "jpg"
    }
}

/** Detects the image type from its first bytes (the picker doesn't give us a reliable MIME type). */
internal fun sniffImageMimeType(b: ByteArray): String = when {
    b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() ->
        "image/jpeg"
    b.size >= 4 && b[0] == 0x89.toByte() && b.decodeToString(1, 4) == "PNG" ->
        "image/png"
    b.size >= 12 && b.decodeToString(0, 4) == "RIFF" && b.decodeToString(8, 12) == "WEBP" ->
        "image/webp"
    b.size >= 3 && b.decodeToString(0, 3) == "GIF" ->
        "image/gif"
    b.size >= 12 && b.decodeToString(4, 8) == "ftyp" ->
        "image/heic"
    else -> "image/jpeg"
}