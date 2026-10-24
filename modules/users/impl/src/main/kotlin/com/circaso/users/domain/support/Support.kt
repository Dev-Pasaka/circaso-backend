package com.circaso.users.domain.support

import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.ContactSource
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.models.Privacy
import com.circaso.users.domain.models.Profile
import com.circaso.users.domain.models.WaitingContact
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.repositories.ContactRepository
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import com.circaso.users.domain.repositories.WaitingContactRepository
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64

const val DEFAULT_DISPLAY_NAME = "Circaso user"

val HANDLE_COOLDOWN: Duration = Duration.ofDays(30)

data class UsersConfig(
    val inviteBaseUrl: String = "https://circaso.com/i/",
    val inviteTtl: Duration = Duration.ofDays(7),
    val maxAvatarBytes: Long = 5L * 1024 * 1024,
    val avatarUploadTtl: Duration = Duration.ofMinutes(5),
)

fun fallbackProfile(userId: UserId) = PublicProfile(userId, DEFAULT_DISPLAY_NAME, null, null, null)

/** A profile that is always there: if the activation event is late or lost, the first read creates an empty one. */
class ProfileProvisioner(
    private val profiles: ProfileRepository,
    private val store: UsersStore,
    private val clock: Clock,
) {
    suspend fun ensure(userId: UserId, displayName: String? = null): Profile {
        profiles.find(userId)?.let { return it }
        val created =
            Profile(
                userId,
                displayName?.trim()?.take(MAX_DISPLAY_NAME)?.takeIf { it.isNotEmpty() } ?: DEFAULT_DISPLAY_NAME,
                null,
                null,
                null,
                null,
                Privacy(),
                clock.instant(),
            )
        store.commit(UsersChanges(profiles = listOf(created)))
        return profiles.find(userId) ?: created
    }
}

/**
 * Turns numbers people were waiting for into contacts when the owner of that number joins (a new account, or a
 * number moved to a new one), unless the waiting person had removed that contact. Returns the owners to tell.
 */
class WaitingContactMatcher(
    private val waiting: WaitingContactRepository,
    private val contacts: ContactRepository,
    private val store: UsersStore,
    private val cache: ReachCache,
    private val clock: Clock,
) {
    suspend fun match(phoneHmac: String, joined: UserId): List<UserId> {
        val rows = waiting.waitingFor(phoneHmac).filter { it.ownerId != joined }
        if (rows.isEmpty()) return emptyList()
        val now = clock.instant()
        val fresh = rows.filter { contacts.find(it.ownerId, joined) == null }
        store.commit(
            UsersChanges(
                contacts =
                    fresh.map {
                        Contact(
                            it.ownerId,
                            joined,
                            ContactSource.PHONEBOOK,
                            ContactStatus.ACTIVE,
                            null,
                            now,
                        )
                    },
                clearedWaiting = rows,
            ),
        )
        cache.invalidateTarget(joined)
        return fresh.map(WaitingContact::ownerId)
    }
}

internal fun randomInviteToken(): String {
    val bytes = ByteArray(24).also(SecureRandom()::nextBytes)
    return "inv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
