package com.circaso.spaces.api

import com.circaso.shared.Uuid7
import com.circaso.shared.UuidIdSerializer
import kotlinx.serialization.Serializable
import java.util.UUID

/** One space: a community or a small business with a chat, members and its own wallet (Spaces phase 3). */
@Serializable(with = SpaceId.Serializer::class)
@JvmInline
value class SpaceId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): SpaceId = SpaceId(Uuid7.generate())

        fun parse(raw: String): SpaceId? = Uuid7.parse(raw)?.let(::SpaceId)
    }

    object Serializer : UuidIdSerializer<SpaceId>("SpaceId", ::SpaceId, SpaceId::value)
}

/** One post in a space chat (stored on the server: space chats are not end-to-end encrypted). */
@Serializable(with = PostId.Serializer::class)
@JvmInline
value class PostId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): PostId = PostId(Uuid7.generate())

        fun parse(raw: String): PostId? = Uuid7.parse(raw)?.let(::PostId)
    }

    object Serializer : UuidIdSerializer<PostId>("PostId", ::PostId, PostId::value)
}
