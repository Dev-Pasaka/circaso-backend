package com.circaso.messaging.domain.support

import com.circaso.messaging.domain.models.LinkPreview

/**
 * The shape a join link's published preview must have (M29): a name, a description of at most 500 characters, and a
 * photo that is a small JPEG or PNG. The server can't check them against the group's sealed name; it only keeps what
 * an admin's phone chose to publish within these limits.
 */
object LinkPreviews {
    fun isValid(preview: LinkPreview, config: MessagingConfig): Boolean {
        val name = preview.name
        if (name.isBlank() || name.codePointCount(0, name.length) > config.maxLinkNameChars) return false
        val description = preview.description
        if (description != null &&
            description.codePointCount(0, description.length) > config.maxLinkDescriptionChars
        ) {
            return false
        }
        val photo = preview.photo ?: return true
        return photo.size <= config.maxLinkPhotoBytes && contentTypeOf(photo) != null
    }

    /** `image/jpeg` or `image/png` by the file's first bytes; null for anything else. */
    fun contentTypeOf(photo: ByteArray): String? =
        when {
            photo.startsWith(JPEG) -> "image/jpeg"
            photo.startsWith(PNG) -> "image/png"
            else -> null
        }

    private fun ByteArray.startsWith(magic: IntArray): Boolean =
        size > magic.size && magic.indices.all { this[it].toInt() and BYTE == magic[it] }

    private const val BYTE = 0xFF
    private val JPEG = intArrayOf(0xFF, 0xD8, 0xFF)
    private val PNG = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
}
