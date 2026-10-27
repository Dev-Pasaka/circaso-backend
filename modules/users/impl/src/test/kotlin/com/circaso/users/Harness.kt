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
import com.circaso.users.data.cache.toEnvelope
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
import com.circaso.users.domain.ports.Transactor
import com.circaso.users.domain.ports.UploadedKey
import com.circaso.users.domain.ports.UsersCache
import com.circaso.users.domain.ports.UsersFrame
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
import com.circaso.users.domain.usecases.CheckBlockedEitherWay
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

/**
 * One in-memory stand-in for every repository port and the transaction boundary. A `run` that throws puts every map
 * back as it was, as a rolled-back transaction would.
 */
class MemoryUsers :
    ProfileRepository,
    HandleRepository,
    ContactRepository,
    WaitingContactRepository,
    BlockRepository,
    UsersStore,
    Transactor {
    val profiles = ConcurrentHashMap<UserId, Profile>()
    val handles = ConcurrentHashMap<String, HandleClaim>()
    val contacts = ConcurrentHashMap<Pair<UserId, UserId>, Contact>()
    val waiting = CopyOnWriteArrayList<WaitingContact>()
    val blocks = ConcurrentHashMap<Pair<UserId, UserId>, Block>()
    val invites = ConcurrentHashMap<String, Invite>()
    val events = CopyOnWriteArrayList<EventEnvelope>()

    override suspend fun <T> run(operation: String, block: suspend () -> T): T {
        val saved = listOf(profiles.toMap(), handles.toMap(), contacts.toMap(), blocks.toMap(), invites.toMap())
        val savedWaiting = waiting.toList()
        val savedEvents = events.toList()
        try {
            return block()
        } catch (e: Exception) {
            restore(profiles, saved[0])
            restore(handles, saved[1])
            restore(contacts, saved[2])
            restore(blocks, saved[3])
            restore(invites, saved[4])
            waiting.clear()
            waiting += savedWaiting
            events.clear()
            events += savedEvents
            throw e
        }
    }

    override suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T> =
        try {
            run(operation) { block().also { if (it is Resource.Error) throw Failed(it) } }
        } catch (e: Failed) {
            e.error
        }

    /** Carries an `attempt` block's error out of [run], so [run] puts everything back. */
    private class Failed(val error: Resource.Error) : RuntimeException()

    @Suppress("UNCHECKED_CAST")
    private fun <K, V> restore(map: ConcurrentHashMap<K, V>, saved: Map<*, *>) {
        map.clear()
        map.putAll(saved as Map<K, V>)
    }

    override suspend fun find(userId: UserId) = profiles[userId]

    override suspend fun findAll(ids: Set<UserId>) = profiles.filterKeys { it in ids }

    override suspend fun create(profile: Profile) = profiles.putIfAbsent(profile.userId, profile) == null

    override suspend fun update(old: Profile, new: Profile): Profile? {
        val stored = profiles[old.userId] ?: return null
        if (stored.version != old.version) return null
        return new.copy(version = stored.version + 1).also { profiles[it.userId] = it }
    }

    override suspend fun lock(userId: UserId) = profiles[userId]

    private val statusTimes = ConcurrentHashMap<UserId, Instant>()

    override suspend fun recordAccountStatus(
        userId: UserId,
        status: AccountStatus,
        at: Instant,
    ): Boolean {
        val profile = profiles[userId] ?: return false
        if (statusTimes[userId]?.let { !it.isBefore(at) } == true) return false
        statusTimes[userId] = at
        profiles[userId] = profile.copy(hidden = status != AccountStatus.ACTIVE)
        return true
    }

    override suspend fun find(handle: String) = handles[handle]

    override suspend fun claim(
        handle: String,
        userId: UserId,
        at: Instant,
    ): Boolean {
        val existing = handles.putIfAbsent(handle, HandleClaim(handle, userId, at, null)) ?: return true
        if (existing.userId != userId) return false
        handles[handle] = HandleClaim(handle, userId, at, null)
        return true
    }

    override suspend fun find(ownerId: UserId, contactId: UserId) = contacts[ownerId to contactId]

    override suspend fun findAll(ownerId: UserId, contactIds: Set<UserId>) =
        contactIds.mapNotNull { id -> contacts[ownerId to id]?.let { id to it } }.toMap()

    override suspend fun list(
        ownerId: UserId,
        after: UUID?,
        limit: Int,
        activeOnly: Boolean,
    ) = contacts.values
        .filter { it.ownerId == ownerId && (after == null || it.contactId.value > after) }
        .filter { !activeOnly || (it.status == ContactStatus.ACTIVE && profiles[it.contactId]?.hidden != true) }
        .sortedBy { it.contactId.value }
        .take(limit)

    override suspend fun ownersOf(contactId: UserId, limit: Int) =
        contacts.values
            .filter { it.contactId == contactId && it.status == ContactStatus.ACTIVE }
            .map { it.ownerId }
            .take(limit)

    override suspend fun connect(contact: Contact): Boolean {
        val key = contact.ownerId to contact.contactId
        val existing = contacts[key]
        if (existing?.status == ContactStatus.ACTIVE) return false
        contacts[key] = contact.copy(addedAt = existing?.addedAt ?: contact.addedAt)
        return true
    }

    override suspend fun remove(
        ownerId: UserId,
        contactId: UserId,
        at: Instant,
    ): Boolean {
        val contact = contacts[ownerId to contactId]?.takeIf { it.status == ContactStatus.ACTIVE } ?: return false
        contacts[ownerId to contactId] = contact.copy(status = ContactStatus.REMOVED, removedAt = at)
        return true
    }

    override suspend fun waitingFor(phoneHmac: String) = waiting.filter { it.phoneHmac == phoneHmac }

    override suspend fun blocked(userId: UserId, blockedId: UserId) = blocks.containsKey(userId to blockedId)

    override suspend fun eitherWay(a: UserId, b: UserId) = blocked(a, b) || blocked(b, a)

    override suspend fun list(userId: UserId) = blocks.values.filter { it.userId == userId }

    override suspend fun add(block: Block) = blocks.putIfAbsent(block.userId to block.blockedId, block) == null

    override suspend fun remove(userId: UserId, blockedId: UserId) = blocks.remove(userId to blockedId) != null

    val inviteRepo =
        object : InviteRepository {
            override suspend fun find(token: String) = invites[token]

            override suspend fun revoke(token: String, ownerId: UserId) =
                invites[token]?.takeIf { it.ownerId == ownerId }?.let { invites.remove(token) } != null
        }

    override suspend fun commit(changes: UsersChanges) {
        changes.contacts.forEach { contacts.putIfAbsent(it.ownerId to it.contactId, it) }
        changes.invites.forEach { invites[it.token] = it }
        waiting.removeIf { row -> changes.waiting.any { it.phoneHmac == row.phoneHmac && it.ownerId == row.ownerId } }
        waiting += changes.waiting
        waiting.removeAll(changes.clearedWaiting.toSet())
        changes.releasedHandles.forEach { released ->
            handles.computeIfPresent(released.handle) { _, held ->
                held.takeIf { it.userId == released.userId }?.copy(releasedAt = released.releasedAt) ?: held
            }
        }
        changes.erasedUsers.forEach { erased ->
            profiles.remove(erased)
            contacts.keys.removeIf { (owner, contact) -> owner == erased || contact == erased }
            blocks.keys.removeIf { (user, blocked) -> user == erased || blocked == erased }
            invites.values.removeIf { it.ownerId == erased }
        }
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
    /** [type] is the wire type, read from the envelope the real sender builds, so tests check that mapping too. */
    data class Sent(val to: UserId, val type: String, val frame: UsersFrame)

    val sent = CopyOnWriteArrayList<Sent>()
    var logged = listOf<LoggedFrame>()
    var latest: Long? = null

    override suspend fun send(userId: UserId, frame: UsersFrame) {
        sent += Sent(userId, frame.toEnvelope(0, Instant.EPOCH).type, frame)
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
    private val matcher = WaitingContactMatcher(users, users, users, users, reachCache, clock)

    val getMyProfile = GetMyProfile(provisioner, views)
    val updateProfile = UpdateProfile(provisioner, users, users, users, bus, notifier, views)
    val prepareAvatar = PrepareAvatarUpload(avatars, config)
    val setAvatar = SetAvatar(provisioner, users, avatars, users, users, bus, notifier, views, config)
    val checkHandle = CheckHandle(users, usersCache)
    val claimHandle = ClaimHandle(provisioner, users, users, users, users, bus, notifier, views, clock)
    val syncContacts = SyncContacts(directory, users, users, views, hasher, users, users, reachCache, usersCache, clock)
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
            users,
            bus,
            reachCache,
            usersCache,
            views,
            notifier,
            clock,
        )
    val createInvite = CreateInvite(users, config, clock)
    val revokeInvite = RevokeInvite(users.inviteRepo)
    val listBlocks = ListBlocks(users, users, views)
    val blockUser = BlockUser(directory, users, users, users, bus, reachCache, clock)
    val unblockUser = UnblockUser(users, users, users, bus, reachCache)
    val removeContact = RemoveContact(users, users, users, bus, reachCache, clock)
    val searchContacts = SearchContacts(users, users, views)
    val onActivated = CreateProfileOnActivation(provisioner, matcher, notifier)
    val deleteData = DeleteUserData(users, avatars, users, users, reachCache, clock)
    val replay = ReplayFrames(frames)
    val blockedEitherWay = CheckBlockedEitherWay(users)

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
