package com.circaso.users.presentation.api

import com.circaso.identity.api.SupportProfile
import com.circaso.identity.api.SupportProfiles
import com.circaso.shared.CircasoJson
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.RealtimeReplay
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull
import com.circaso.users.api.Profiles
import com.circaso.users.api.PublicProfile
import com.circaso.users.api.ReachPolicy
import com.circaso.users.presentation.services.DirectoryService
import kotlin.coroutines.cancellation.CancellationException

/** users:api for messaging, wallet and calls. Every call goes through a service, like any other way in. */
class ReachPolicyImpl(private val directory: DirectoryService) : ReachPolicy {
    override suspend fun canReach(from: UserId, to: UserId): Boolean = directory.canReach(from, to).getOrNull() == true

    override suspend fun reachable(from: UserId, to: Set<UserId>): Set<UserId> =
        directory
            .filterReachable(
                from,
                to,
            ).getOrNull()
            .orEmpty()

    /**
     * Fails closed: when users can't answer (an error, or the database down), the two count as blocked, so a shared
     * group never stands in for reach on a guess.
     */
    override suspend fun blockedEitherWay(a: UserId, b: UserId): Boolean =
        try {
            directory.blockedEitherWay(a, b).getOrNull() ?: true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true
        }
}

class ProfilesImpl(private val directory: DirectoryService) : Profiles {
    override suspend fun get(ids: Set<UserId>): Map<UserId, PublicProfile> =
        directory.getProfiles(ids).getOrNull().orEmpty()
}

/** Identity's `SupportProfiles`: what its ops routes need to know of a support account's profile and handle. */
class SupportProfilesImpl(private val directory: DirectoryService) : SupportProfiles {
    override suspend fun checkHandle(handle: String): Resource<String> = directory.checkSupportHandle(handle)

    override suspend fun describe(ids: Set<UserId>): Map<UserId, SupportProfile> =
        directory
            .getProfiles(ids)
            .getOrNull()
            .orEmpty()
            .mapValues { (_, profile) -> SupportProfile(profile.displayName, profile.handle) }
}

/**
 * What the gateway calls after a reconnect with the app's last `seq`: the frames it missed, or a single `resync`
 * frame when the gap is older than the 7 days they are kept, after which the app reloads over HTTP.
 */
class UsersRealtimeReplay(private val directory: DirectoryService) : RealtimeReplay {
    override suspend fun since(userId: UserId, seq: Long): Resource<List<RealtimeEnvelope>> {
        val frames =
            directory.getMissedFrames(userId, seq).getOrNull()
                ?: return Resource.success(listOf(RealtimeEnvelope.resync("users", seq)))
        return Resource.success(frames.map { CircasoJson.decodeFromString(RealtimeEnvelope.serializer(), it.json) })
    }
}
