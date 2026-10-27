package com.circaso.users.domain.usecases

import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.ConnectionAdded
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.models.ConnectVia
import com.circaso.users.domain.models.Connection
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.ContactSource
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.models.Profile
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.ports.Transactor
import com.circaso.users.domain.ports.UsersCache
import com.circaso.users.domain.repositories.BlockRepository
import com.circaso.users.domain.repositories.ContactRepository
import com.circaso.users.domain.repositories.HandleRepository
import com.circaso.users.domain.repositories.InviteRepository
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import com.circaso.users.domain.support.HandleRules
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.Rollback
import com.circaso.users.domain.support.UserNotifier
import java.time.Clock
import java.time.Instant

/**
 * U11: connects through a typed @handle, a QR code or an invite link. A QR code or link was handed over on
 * purpose, so the contact is created both ways; a typed handle only lets the person who typed it reach the
 * handle's owner. Blocks win over everything, and the answer never says whether it was a block. The block check,
 * the contact reads and the writes they decide are one transaction.
 */
class Connect(
    private val handles: HandleRepository,
    private val invites: InviteRepository,
    private val profiles: ProfileRepository,
    private val contacts: ContactRepository,
    private val blocks: BlockRepository,
    private val transactor: Transactor,
    private val store: UsersStore,
    private val bus: EventBus,
    private val cache: ReachCache,
    private val usersCache: UsersCache,
    private val views: ProfileViews,
    private val notifier: UserNotifier,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        viewer: UserId,
        via: ConnectVia,
        value: String,
    ): Resource<Connection> {
        if (via == ConnectVia.HANDLE && usersCache.countHandleLookup(viewer) > MAX_LOOKUPS_PER_HOUR) {
            return Resource.error(
                SharedError.RateLimited(usersCache.handleLookupRetryAfter(viewer)?.seconds ?: 3_600),
            )
        }
        val (target, source, mutual) =
            when (val found = resolve(via, value)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        if (target == viewer) {
            return Resource.error(if (via == ConnectVia.HANDLE) UsersError.HandleNotFound else UsersError.InviteInvalid)
        }

        val event = bus.envelope(ConnectionAdded(viewer, target, source.name, mutual))
        val (targetProfile, created) =
            try {
                transactor.run("contacts.connect") { connect(viewer, via, Triple(target, source, mutual), event) }
            } catch (e: Rollback) {
                return Resource.error(e.error)
            }

        if (created) {
            bus.publish(event)
            cache.invalidateTarget(target)
            cache.invalidateTarget(viewer)
            if (mutual) {
                profiles.find(viewer)?.let { notifier.connectionAdded(target, it) }
                notifier.connectionAdded(viewer, targetProfile)
            }
        }
        return Resource.success(Connection(views.forViewer(viewer, targetProfile), created))
    }

    /**
     * Who [value] names, how, and whether both ways: the live owner of a handle that allows lookup, or the owner of a
     * valid invite.
     */
    private suspend fun resolve(via: ConnectVia, value: String): Resource<Triple<UserId, ContactSource, Boolean>> =
        when (via) {
            ConnectVia.HANDLE -> {
                val owner =
                    HandleRules
                        .normalise(value)
                        ?.let { handles.find(it) }
                        ?.takeIf { it.releasedAt == null }
                        ?.userId
                        ?.takeIf { profiles.find(it)?.privacy?.handleLookup == true }
                if (owner == null) {
                    Resource.error(UsersError.HandleNotFound)
                } else {
                    Resource.success(Triple(owner, ContactSource.HANDLE, false))
                }
            }

            ConnectVia.QR, ConnectVia.INVITE -> {
                val invite = invites.find(value.trim().substringAfterLast('/'))
                if (invite == null) {
                    Resource.error(UsersError.InviteInvalid)
                } else {
                    val source = if (via == ConnectVia.QR) ContactSource.QR else ContactSource.INVITE
                    Resource.success(Triple(invite.ownerId, source, true))
                }
            }
        }

    /**
     * Inside the transaction: refuses a block either way, then writes the contacts that aren't active yet. Answers the
     * target's profile and whether anything was created.
     */
    private suspend fun connect(
        viewer: UserId,
        via: ConnectVia,
        resolved: Triple<UserId, ContactSource, Boolean>,
        event: EventEnvelope,
    ): Pair<Profile, Boolean> {
        val (target, source, mutual) = resolved
        if (blocks.eitherWay(viewer, target)) {
            throw Rollback(if (via == ConnectVia.HANDLE) UsersError.HandleNotFound else UsersError.NotReachable)
        }
        val profile = profiles.find(target) ?: throw Rollback(SharedError.NotFound)
        val now = clock.instant()
        val forward = contacts.find(viewer, target)
        val back = if (mutual) contacts.find(target, viewer) else null
        val newForward = forward?.status != ContactStatus.ACTIVE
        val newBack = mutual && back?.status != ContactStatus.ACTIVE
        if (!newForward && !newBack) return profile to false

        store.commit(
            UsersChanges(
                connections =
                    listOfNotNull(
                        contact(viewer, target, source, forward?.addedAt ?: now).takeIf { newForward },
                        contact(target, viewer, source, back?.addedAt ?: now).takeIf { newBack },
                    ),
                events = listOf(event),
            ),
        )
        return profile to true
    }

    private fun contact(
        owner: UserId,
        other: UserId,
        source: ContactSource,
        addedAt: Instant,
    ) = Contact(owner, other, source, ContactStatus.ACTIVE, null, addedAt)

    private companion object {
        const val MAX_LOOKUPS_PER_HOUR = 30
    }
}
