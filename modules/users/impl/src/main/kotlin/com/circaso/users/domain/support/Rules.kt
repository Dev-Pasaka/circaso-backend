package com.circaso.users.domain.support

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountStatus
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.models.AboutVisibility
import com.circaso.users.domain.models.AvatarVisibility
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.models.Profile
import com.circaso.users.domain.ports.AvatarStorage
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.ports.RealtimeFrames
import com.circaso.users.domain.ports.UsersFrame
import com.circaso.users.domain.repositories.BlockRepository
import com.circaso.users.domain.repositories.ContactRepository
import com.circaso.users.domain.repositories.ProfileRepository

/** Handle rules (Users → U5–U6): 3 to 20 characters, lowercase letters, digits and underscore, starting with a letter. */
object HandleRules {
    private val shape = Regex("^[a-z][a-z0-9_]{2,19}$")
    private val reserved =
        setOf(
            "circaso",
            "support",
            "admin",
            "asha",
            "help",
            "official",
            "staff",
            "team",
            "security",
            "root",
            "system",
            "moderator",
            "billing",
            "wallet",
            "payments",
            "mpesa",
            "circle",
            "coinx",
            "api",
            "www",
            "null",
            "undefined",
            "me",
            "everyone",
        )

    /** The handle in its stored (lowercase) form, or null if it breaks the rules or is reserved. */
    fun normalise(raw: String): String? =
        raw.trim().removePrefix("@").lowercase().takeIf {
            shape.matches(it) &&
                it !in reserved
        }

    /** Some alternatives to try when [handle] is taken. */
    fun suggestions(handle: String): Sequence<String> =
        sequence {
            val base = handle.take(17)
            yield(base + "_o")
            for (n in 1..9) yield("${base}_$n")
            for (n in 10..99) yield("${base.take(16)}$n")
        }.filter { it.length in 3..20 }
}

const val MAX_DISPLAY_NAME = 40
const val MAX_ABOUT = 140

/**
 * "May A message, call or pay B?" B's account is active; neither has blocked the other; A has an active
 * connection to B. Three single-partition reads, cached in Redis. It does not need B to have A saved.
 */
class ReachRule(
    private val directory: AccountDirectory,
    private val blocks: BlockRepository,
    private val contacts: ContactRepository,
    private val cache: ReachCache,
) {
    suspend fun canReach(from: UserId, to: UserId): Boolean {
        if (from == to) return false
        cache.get(from, to)?.let { return it }
        val reachable =
            directory.status(to) == AccountStatus.ACTIVE &&
                !blocks.eitherWay(from, to) &&
                contacts.find(from, to)?.status == ContactStatus.ACTIVE
        cache.put(from, to, reachable)
        return reachable
    }

    suspend fun reachable(from: UserId, to: Set<UserId>): Set<UserId> =
        to.filterTo(
            mutableSetOf(),
        ) { canReach(from, it) }
}

/** What each viewer may see of a profile: the avatar and the about line follow the owner's privacy settings. */
class ProfileViews(private val avatars: AvatarStorage, private val contacts: ContactRepository) {
    /** The public profile of [profile] as [viewer] sees it, the viewer being someone who can reach the owner. */
    suspend fun forViewer(viewer: UserId, profile: Profile): PublicProfile {
        val owner = profile.userId
        val savedByOwner = viewer == owner || contacts.find(owner, viewer)?.status == ContactStatus.ACTIVE
        val avatarShown =
            when (profile.privacy.avatar) {
                AvatarVisibility.EVERYONE_WHO_CAN_REACH -> true
                AvatarVisibility.CONTACTS -> savedByOwner
                AvatarVisibility.NOBODY -> viewer == owner
            }
        val aboutShown =
            when (profile.privacy.about) {
                AboutVisibility.EVERYONE_WHO_CAN_REACH -> true
                AboutVisibility.CONTACTS -> savedByOwner
                AboutVisibility.NOBODY -> viewer == owner
            }
        return PublicProfile(
            userId = owner,
            displayName = profile.displayName,
            handle = profile.handle,
            avatarUrl = profile.avatarKey?.takeIf { avatarShown }?.let { avatars.urlOf(it) },
            about = profile.about.takeIf { aboutShown },
        )
    }

    /** For other modules, which only ask once the reach rule has passed: never the about line. */
    suspend fun forModules(profile: Profile) =
        PublicProfile(
            profile.userId,
            profile.displayName,
            profile.handle,
            profile.avatarKey
                ?.takeIf {
                    profile.privacy.avatar == AvatarVisibility.EVERYONE_WHO_CAN_REACH
                }?.let { avatars.urlOf(it) },
            null,
        )

    suspend fun avatarUrl(profile: Profile) = profile.avatarKey?.let { avatars.urlOf(it) }
}

/** Realtime frames `users` sends: a thin helper so use cases name what happened, not how it travels. */
class UserNotifier(private val frames: RealtimeFrames, private val contacts: ContactRepository) {
    suspend fun contactJoined(owner: UserId, joined: Profile) =
        frames.send(owner, UsersFrame.ContactJoined(joined.userId, joined.displayName))

    suspend fun connectionAdded(to: UserId, other: Profile) =
        frames.send(to, UsersFrame.ConnectionAdded(other.userId, other.displayName))

    /** Tells the people who have [userId] saved that the profile changed. */
    suspend fun profileUpdated(userId: UserId, version: Long) {
        for (owner in contacts.ownersOf(userId, FAN_OUT_LIMIT)) {
            frames.send(owner, UsersFrame.ProfileUpdated(userId, version))
        }
    }

    private companion object {
        const val FAN_OUT_LIMIT = 500
    }
}
