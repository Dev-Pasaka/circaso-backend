package com.circaso.users.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.CircasoJson
import com.circaso.shared.UserId
import com.circaso.users.data.tables.ProfilesTable
import com.circaso.users.domain.models.AboutVisibility
import com.circaso.users.domain.models.AvatarVisibility
import com.circaso.users.domain.models.Privacy
import com.circaso.users.domain.models.Profile
import com.circaso.users.domain.repositories.ProfileRepository
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import java.time.Clock

class ProfileRepositoryImpl(private val database: Database, private val clock: Clock) : ProfileRepository {
    override suspend fun find(userId: UserId): Profile? =
        database.tx("profiles.find") {
            ProfilesTable
                .selectAll()
                .where { ProfilesTable.userId eq userId.value }
                .singleOrNull()
                ?.toProfile()
        }

    override suspend fun findAll(ids: Set<UserId>): Map<UserId, Profile> {
        if (ids.isEmpty()) return emptyMap()
        return database.tx("profiles.find-all") {
            ProfilesTable
                .selectAll()
                .where { ProfilesTable.userId inList ids.map { it.value } }
                .toList()
                .map { it.toProfile() }
                .associateBy { it.userId }
        }
    }

    override suspend fun create(profile: Profile): Boolean =
        database.tx("profiles.create") {
            val statement =
                ProfilesTable.insertIgnore {
                    it[userId] = profile.userId.value
                    it[createdAt] = profile.createdAt
                    it[version] = profile.version
                    it.write(profile)
                }
            statement.insertedCount == 1
        }

    override suspend fun update(old: Profile, new: Profile): Profile? =
        database.tx("profiles.update") {
            ProfilesTable
                .updateReturning(
                    where = { (ProfilesTable.userId eq old.userId.value) and (ProfilesTable.version eq old.version) },
                ) {
                    it[version] = version + 1
                    it.write(new)
                }.singleOrNull()
                ?.toProfile()
        }

    override suspend fun lock(userId: UserId): Profile? =
        database.tx("profiles.lock") {
            ProfilesTable
                .selectAll()
                .where { ProfilesTable.userId eq userId.value }
                .forUpdate(ForUpdateOption.PostgreSQL.ForKeyShare)
                .singleOrNull()
                ?.toProfile()
        }

    /** The columns a profile change can touch; the key, `created_at` and `version` are set by the caller. */
    private fun UpdateBuilder<*>.write(profile: Profile) {
        this[ProfilesTable.displayName] = profile.displayName
        this[ProfilesTable.about] = profile.about
        this[ProfilesTable.handle] = profile.handle
        this[ProfilesTable.handleChangedAt] = profile.handleChangedAt
        this[ProfilesTable.avatarKey] = profile.avatarKey
        this[ProfilesTable.privacy] = profile.privacy.toJson()
        this[ProfilesTable.updatedAt] = clock.instant()
    }
}

/** How the privacy settings are stored in the `privacy` column. */
@Serializable
private data class PrivacyJson(val avatar: String, val about: String, val handleLookup: Boolean)

private fun Privacy.toJson() =
    CircasoJson.encodeToString(PrivacyJson.serializer(), PrivacyJson(avatar.name, about.name, handleLookup))

/** An unknown or unreadable value falls back to the default for that setting, so a profile always loads. */
private fun privacyOf(json: String): Privacy {
    val stored = runCatching { CircasoJson.decodeFromString(PrivacyJson.serializer(), json) }.getOrNull()
    if (stored == null) return Privacy()
    return Privacy(
        avatar = AvatarVisibility.entries.firstOrNull { it.name == stored.avatar } ?: Privacy().avatar,
        about = AboutVisibility.entries.firstOrNull { it.name == stored.about } ?: Privacy().about,
        handleLookup = stored.handleLookup,
    )
}

private fun ResultRow.toProfile() =
    Profile(
        userId = UserId(this[ProfilesTable.userId]),
        displayName = this[ProfilesTable.displayName],
        about = this[ProfilesTable.about],
        handle = this[ProfilesTable.handle],
        handleChangedAt = this[ProfilesTable.handleChangedAt],
        avatarKey = this[ProfilesTable.avatarKey],
        privacy = privacyOf(this[ProfilesTable.privacy]),
        createdAt = this[ProfilesTable.createdAt],
        version = this[ProfilesTable.version],
    )
