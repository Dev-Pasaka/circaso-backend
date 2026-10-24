package com.circaso.users.domain.usecases

import com.circaso.shared.EventBus
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
import com.circaso.users.domain.ports.ReachCache
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
import com.circaso.users.domain.support.UserNotifier
import java.time.Clock

/**
 * U11: connects through a typed @handle, a QR code or an invite link. A QR code or link was handed over on
 * purpose, so the contact is created both ways; a typed handle only lets the person who typed it reach the
 * handle's owner. Blocks win over everything, and the answer never says whether it was a block.
 */
class Connect(
    private val handles: HandleRepository,
    private val invites: InviteRepository,
    private val profiles: ProfileRepository,
    private val contacts: ContactRepository,
    private val blocks: BlockRepository,
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
        val (target, source, mutual) =
            when (via) {
                ConnectVia.HANDLE -> {
                    if (usersCache.countHandleLookup(viewer) > MAX_LOOKUPS_PER_HOUR) {
                        return Resource.error(
                            SharedError.RateLimited(usersCache.handleLookupRetryAfter(viewer)?.seconds ?: 3_600),
                        )
                    }
                    val handle = HandleRules.normalise(value) ?: return Resource.error(UsersError.HandleNotFound)
                    val claim =
                        handles.find(handle)?.takeIf { it.releasedAt == null }
                            ?: return Resource.error(UsersError.HandleNotFound)
                    if (profiles.find(claim.userId)?.privacy?.handleLookup !=
                        true
                    ) {
                        return Resource.error(UsersError.HandleNotFound)
                    }
                    Triple(claim.userId, ContactSource.HANDLE, false)
                }

                ConnectVia.QR, ConnectVia.INVITE -> {
                    val token = value.trim().substringAfterLast('/')
                    val invite = invites.find(token) ?: return Resource.error(UsersError.InviteInvalid)
                    Triple(invite.ownerId, if (via == ConnectVia.QR) ContactSource.QR else ContactSource.INVITE, true)
                }
            }
        if (target ==
            viewer
        ) {
            return Resource.error(
                if (via ==
                    ConnectVia.HANDLE
                ) {
                    UsersError.HandleNotFound
                } else {
                    UsersError.InviteInvalid
                },
            )
        }
        if (blocks.eitherWay(viewer, target)) {
            return Resource.error(
                if (via ==
                    ConnectVia.HANDLE
                ) {
                    UsersError.HandleNotFound
                } else {
                    UsersError.NotReachable
                },
            )
        }
        val targetProfile = profiles.find(target) ?: return Resource.error(SharedError.NotFound)

        val now = clock.instant()
        val forward = contacts.find(viewer, target)
        val back = if (mutual) contacts.find(target, viewer) else null
        val newForward = forward?.status != ContactStatus.ACTIVE
        val newBack = mutual && back?.status != ContactStatus.ACTIVE
        val created = newForward || newBack

        if (created) {
            store.commit(
                UsersChanges(
                    contacts =
                        listOfNotNull(
                            Contact(
                                viewer,
                                target,
                                source,
                                ContactStatus.ACTIVE,
                                null,
                                forward?.addedAt ?: now,
                            ).takeIf { newForward },
                            Contact(
                                target,
                                viewer,
                                source,
                                ContactStatus.ACTIVE,
                                null,
                                back?.addedAt ?: now,
                            ).takeIf { newBack },
                        ),
                    events = listOf(bus.envelope(ConnectionAdded(viewer, target, source.name, mutual))),
                ),
            )
            cache.invalidateTarget(target)
            cache.invalidateTarget(viewer)
            if (mutual) {
                profiles.find(viewer)?.let { notifier.connectionAdded(target, it) }
                notifier.connectionAdded(viewer, targetProfile)
            }
        }
        return Resource.success(Connection(views.forViewer(viewer, targetProfile), created))
    }

    private companion object {
        const val MAX_LOOKUPS_PER_HOUR = 30
    }
}
