package com.circaso.users

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountStatus
import com.circaso.shared.DomainEvent
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventSubscription
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.domain.models.Block
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.models.HandleClaim
import com.circaso.users.domain.models.Invite
import com.circaso.users.domain.models.Profile
import com.circaso.users.domain.models.WaitingContact
import com.circaso.users.domain.ports.AvatarStorage
import com.circaso.users.domain.ports.LoggedFrame
import com.circaso.users.domain.ports.PhoneHasher
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.ports.RealtimeFrames
import com.circaso.users.domain.ports.RealtimeLog
import com.circaso.users.domain.ports.StoredAvatar
import com.circaso.users.domain.ports.UploadedKey
import com.circaso.users.domain.ports.UsersCache
import com.circaso.users.domain.repositories.BlockRepository
import com.circaso.users.domain.repositories.ContactRepository
import com.circaso.users.domain.repositories.HandleRepository
import com.circaso.users.domain.repositories.InviteRepository
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import com.circaso.users.domain.repositories.WaitingContactRepository
import com.circaso.users.domain.support.ProfileProvisioner
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.ReachRule
import com.circaso.users.domain.support.UserNotifier
import com.circaso.users.domain.support.UsersConfig
import com.circaso.users.domain.support.WaitingContactMatcher
import com.circaso.users.domain.usecases.BlockUser
import com.circaso.users.domain.usecases.CheckHandle
import com.circaso.users.domain.usecases.ClaimHandle
import com.circaso.users.domain.usecases.Connect
import com.circaso.users.domain.usecases.CreateInvite
import com.circaso.users.domain.usecases.CreateProfileOnActivation
import com.circaso.users.domain.usecases.DeleteUserData
import com.circaso.users.domain.usecases.GetMyProfile
import com.circaso.users.domain.usecases.GetPublicProfile
import com.circaso.users.domain.usecases.ListBlocks
import com.circaso.users.domain.usecases.ListContacts
import com.circaso.users.domain.usecases.LookupHandle
import com.circaso.users.domain.usecases.PrepareAvatarUpload
import com.circaso.users.domain.usecases.RemoveContact
import com.circaso.users.domain.usecases.ReplayFrames
import com.circaso.users.domain.usecases.RevokeInvite
import com.circaso.users.domain.usecases.SearchContacts
import com.circaso.users.domain.usecases.SetAvatar
import com.circaso.users.domain.usecases.SyncContacts
import com.circaso.users.domain.usecases.UnblockUser
import com.circaso.users.domain.usecases.UpdateProfile
import kotlinx.serialization.KSerializer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** One in-memory stand-in for every repository port. */
class MemoryUsers :
    ProfileRepository,
    HandleRepository,
    ContactRepository,
    WaitingContactRepository,
    BlockRepository,
    UsersStore {
    val profiles = ConcurrentHashMap<UserId, Profile>()
    val handles = ConcurrentHashMap<String, HandleClaim>()
    val contacts = ConcurrentHashMap<Pair<UserId, UserId>, Contact>()
    val reverse = ConcurrentHashMap.newKeySet<Pair<UserId, UserId>>() // (contact, owner)
    val waiting = CopyOnWriteArrayList<WaitingContact>()
    val blocks = ConcurrentHashMap<Pair<UserId, UserId>, Block>()
    val invites = ConcurrentHashMap<String, Invite>()
    val events = CopyOnWriteArrayList<EventEnvelope>()

    override suspend fun find(userId: UserId) = profiles[userId]

    override suspend fun findAll(ids: Set<UserId>) = profiles.filterKeys { it in ids }

    override suspend fun update(old: Profile, new: Profile): Profile? {
        val stored = profiles[old.userId] ?: return null
        if (stored.version != old.version) return null
        return new.copy(version = stored.version + 1).also { profiles[it.userId] = it }
    }

    override suspend fun find(handle: String) = handles[handle]

    override suspend fun claim(handle: String, userId: UserId): Boolean {
        val existing =
            handles.putIfAbsent(handle, HandleClaim(handle, userId, Instant.now(), null)) ?: return true
        if (existing.userId != userId) return false
        if (existing.releasedAt != null) handles[handle] = existing.copy(releasedAt = null)
        return true
    }

    override suspend fun find(ownerId: UserId, contactId: UserId) = contacts[ownerId to contactId]

    override suspend fun list(
        ownerId: UserId,
        after: UUID?,
        limit: Int,
    ) = contacts.values
        .filter {
            it.ownerId == ownerId && (after == null || it.contactId.value > after)
        }.sortedBy { it.contactId.value }
        .take(limit)

    override suspend fun ownersOf(contactId: UserId, limit: Int) =
        reverse
            .filter {
                it.first == contactId
            }.map { it.second }
            .take(limit)

    override suspend fun waitingFor(phoneHmac: String) = waiting.filter { it.phoneHmac == phoneHmac }

    override suspend fun blocked(userId: UserId, blockedId: UserId) = blocks.containsKey(userId to blockedId)

    override suspend fun eitherWay(a: UserId, b: UserId) = blocked(a, b) || blocked(b, a)

    override suspend fun list(userId: UserId) = blocks.values.filter { it.userId == userId }

    override suspend fun blockedBy(userId: UserId) = blocks.values.filter { it.blockedId == userId }

    val inviteRepo =
        object : InviteRepository {
            override suspend fun find(token: String) = invites[token]
        }

    override suspend fun commit(changes: UsersChanges) {
        changes.profiles.forEach { profiles[it.userId] = it }
        changes.deletedProfiles.forEach { profiles.remove(it) }
        changes.contacts.forEach {
            contacts[it.ownerId to it.contactId] = it
            if (it.status ==
                ContactStatus.ACTIVE
            ) {
                reverse += it.contactId to it.ownerId
            } else {
                reverse -= it.contactId to it.ownerId
            }
        }
        changes.deletedContacts.forEach { (owner, contact) ->
            contacts.remove(owner to contact)
            reverse -=
                contact to owner
        }
        changes.blocks.forEach { blocks[it.userId to it.blockedId] = it }
        changes.unblocks.forEach { blocks.remove(it.userId to it.blockedId) }
        changes.invites.forEach { invites[it.token] = it }
        changes.revokedInvites.forEach { invites.remove(it) }
        waiting += changes.waiting
        waiting.removeAll(changes.clearedWaiting.toSet())
        changes.releasedHandles.forEach { handles[it.handle] = it }
        events += changes.events
    }

    fun eventTypes() = events.map { it.type }
}

class MemoryReachCache : ReachCache {
    private val decisions = ConcurrentHashMap<Pair<UserId, UserId>, Boolean>()

    override suspend fun get(from: UserId, to: UserId) = decisions[from to to]

    override suspend fun put(
        from: UserId,
        to: UserId,
        reachable: Boolean,
    ) {
        decisions[from to to] = reachable
    }

    override suspend fun invalidateTarget(target: UserId) {
        decisions.keys.removeIf { it.second == target }
    }
}

class MemoryUsersCache : UsersCache {
    private val lookups = ConcurrentHashMap<UserId, Long>()

    override suspend fun countHandleLookup(userId: UserId) = lookups.merge(userId, 1L, Long::plus)!!

    override suspend fun handleLookupRetryAfter(userId: UserId): Duration? = Duration.ofMinutes(30)

    override suspend fun recordSyncBatch(
        userId: UserId,
        syncId: UUID,
        final: Boolean,
    ) = Unit
}

class FakeDirectory : AccountDirectory {
    val numbers = ConcurrentHashMap<PhoneNumber, UserId>()
    val statuses = ConcurrentHashMap<UserId, AccountStatus>()

    override suspend fun status(userId: UserId) = statuses[userId]

    override suspend fun resolvePhones(phones: Set<PhoneNumber>) =
        phones
            .mapNotNull { p ->
                numbers[p]
                    ?.takeIf {
                        statuses[it] ==
                            AccountStatus.ACTIVE
                    }?.let { p to it }
            }.toMap()
}

class FakeAvatars : AvatarStorage {
    val objects = ConcurrentHashMap<String, StoredAvatar>()
    val deleted = CopyOnWriteArrayList<String>()

    override suspend fun prepareUpload(
        userId: UserId,
        contentType: String,
        expiresIn: Duration,
    ) = Resource.success(UploadedKey("https://upload.test/x", "avatars/$userId/${UUID.randomUUID()}.jpg"))

    override suspend fun describe(key: String) = objects[key]

    override suspend fun delete(key: String) {
        deleted += key
        objects.remove(key)
    }

    override suspend fun urlOf(key: String): String? = "https://cdn.test/$key"
}

class RecordingFrames :
    RealtimeFrames,
    RealtimeLog {
    data class Sent(val to: UserId, val type: String, val data: Map<String, String>)

    val sent = CopyOnWriteArrayList<Sent>()
    var logged = listOf<LoggedFrame>()
    var latest: Long? = null

    override suspend fun send(
        userId: UserId,
        type: String,
        data: Map<String, String>,
    ) {
        sent +=
            Sent(userId, type, data)
    }

    override suspend fun since(
        userId: UserId,
        seq: Long,
        limit: Int,
    ) = logged.filter { it.seq > seq }

    override suspend fun latest(userId: UserId) = latest
}

class MemoryBus : EventBus {
    override fun <E : DomainEvent> envelope(event: E, eventSerializer: KSerializer<E>) =
        EventEnvelope.of(event, eventSerializer)

    override suspend fun publish(envelope: EventEnvelope) = Unit

    override fun subscribe(subscription: EventSubscription) = Unit
}

class Harness {
    val users = MemoryUsers()
    val directory = FakeDirectory()
    val avatars = FakeAvatars()
    val frames = RecordingFrames()
    val reachCache = MemoryReachCache()
    val usersCache = MemoryUsersCache()
    val bus = MemoryBus()
    private val clock = Clock.systemUTC()
    private val config = UsersConfig()
    private val hasher =
        object : PhoneHasher {
            override fun hmac(phone: PhoneNumber) = "h:${phone.e164}"
        }

    val reach = ReachRule(directory, users, users, reachCache)
    private val views = ProfileViews(avatars, users)
    private val notifier = UserNotifier(frames, users)
    private val provisioner = ProfileProvisioner(users, users, clock)
    private val matcher = WaitingContactMatcher(users, users, users, reachCache, clock)

    val getMyProfile = GetMyProfile(provisioner, views)
    val updateProfile = UpdateProfile(provisioner, users, users, bus, notifier, views)
    val prepareAvatar = PrepareAvatarUpload(avatars, config)
    val setAvatar = SetAvatar(provisioner, users, avatars, users, bus, notifier, views, config)
    val checkHandle = CheckHandle(users)
    val claimHandle = ClaimHandle(provisioner, users, users, users, bus, notifier, views, clock)
    val syncContacts = SyncContacts(directory, users, users, views, hasher, users, reachCache, usersCache, clock)
    val listContacts = ListContacts(users, users, views)
    val lookupHandle = LookupHandle(users, users, users, views, usersCache)
    val getPublicProfile = GetPublicProfile(reach, users, views)
    val connect =
        Connect(
            users,
            users.inviteRepo,
            users,
            users,
            users,
            users,
            bus,
            reachCache,
            usersCache,
            views,
            notifier,
            clock,
        )
    val createInvite = CreateInvite(users, config, clock)
    val revokeInvite = RevokeInvite(users.inviteRepo, users)
    val listBlocks = ListBlocks(users, users, views)
    val blockUser = BlockUser(users, users, bus, reachCache, clock)
    val unblockUser = UnblockUser(users, users, bus, reachCache, clock)
    val removeContact = RemoveContact(users, users, bus, reachCache, clock)
    val searchContacts = SearchContacts(users, users, views)
    val onActivated = CreateProfileOnActivation(provisioner, matcher, notifier)
    val deleteData = DeleteUserData(users, users, users, avatars, users, reachCache, clock)
    val replay = ReplayFrames(frames)

    /** An active account with a profile named [name] and the number [phone]. */
    suspend fun person(name: String, phone: String? = null): UserId {
        val id = UserId.new()
        directory.statuses[id] = AccountStatus.ACTIVE
        phone?.let { directory.numbers[PhoneNumber.parse(it)!!] = id }
        provisioner.ensure(id, name)
        return id
    }

    fun hmacOf(phone: String) = hasher.hmac(PhoneNumber.parse(phone)!!)
}
