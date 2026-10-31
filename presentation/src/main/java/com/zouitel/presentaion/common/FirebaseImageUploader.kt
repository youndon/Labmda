package com.zouitel.presentaion.common

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.storage.Data
import dev.gitlive.firebase.storage.storageMetadata
import kotlin.random.Random

/** A photo selected by the user. Bytes are read lazily so picker I/O errors reach upload state. */
class PickedImage(val readBytes: suspend () -> ByteArray)

/**
 * Uploads public catalogue imagery to Firebase Storage under the authenticated merchant UID.
 * Storage Rules independently enforce ownership, image MIME type and the 10 MiB size limit.
 */
object FirebaseImageUploader {
    private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024

    suspend fun upload(bytes: ByteArray, ownerUid: String, category: String): String {
        require(ownerUid.isNotBlank()) { "Sign in to upload an image." }
        require(category in setOf("restaurant", "meal", "shop", "product")) {
            "Unsupported image category."
        }
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) {
            "Choose an image smaller than 10 MB."
        }

        val mimeType = sniffImageMimeType(bytes)
        require(mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) {
            "Choose a JPEG, PNG, WEBP or GIF image."
        }
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val fileName = "${Random.nextLong().toULong().toString(16)}.$extension"
        val reference = Firebase.storage.reference
            .child("merchant-media")
            .child(ownerUid)
            .child(category)
            .child(fileName)

        reference.putData(
            Data(bytes),
            storageMetadata {
                contentType = mimeType
                cacheControl = "public,max-age=31536000"
            }
        )
        return reference.getDownloadUrl()
    }

    private fun sniffImageMimeType(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 4 && bytes[0] == 0x89.toByte() &&
            bytes.decodeToString(1, 4) == "PNG" -> "image/png"
        bytes.size >= 12 && bytes.decodeToString(0, 4) == "RIFF" &&
            bytes.decodeToString(8, 12) == "WEBP" -> "image/webp"
        bytes.size >= 3 && bytes.decodeToString(0, 3) == "GIF" -> "image/gif"
        else -> "application/octet-stream"
    }
}
