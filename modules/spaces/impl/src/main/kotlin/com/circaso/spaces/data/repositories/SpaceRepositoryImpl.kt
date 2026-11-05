package com.circaso.spaces.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.data.tables.MembersTable
import com.circaso.spaces.data.tables.SpacesTable
import com.circaso.spaces.domain.models.PaidState
import com.circaso.spaces.domain.models.Period
import com.circaso.spaces.domain.models.PostingMode
import com.circaso.spaces.domain.models.Price
import com.circaso.spaces.domain.models.PrivateMessages
import com.circaso.spaces.domain.models.Space
import com.circaso.spaces.domain.models.SpaceState
import com.circaso.spaces.domain.models.Topic
import com.circaso.spaces.domain.models.Visibility
import com.circaso.spaces.domain.repositories.SpaceRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ComplexExpression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Instant

/** `spaces.spaces`: the insert is the claim behind both the handle (`spaces_handle_key`) and SP2's create key. */
class SpaceRepositoryImpl(private val database: Database) : SpaceRepository {
    override suspend fun insert(space: Space): Boolean =
        database.tx("spaces.insert") {
            val statement =
                SpacesTable.insertIgnore {
                    it[spaceId] = space.spaceId.value
                    it[ownerId] = space.ownerId.value
                    it[createdAt] = space.createdAt
                    it[createKey] = space.createKey
                    it[country] = space.country
                    it[version] = space.version
                    write(it, space)
                }
            statement.insertedCount > 0
        }

    override suspend fun find(spaceId: SpaceId): Space? =
        database.tx("spaces.find") {
            SpacesTable
                .selectAll()
                .where { SpacesTable.spaceId eq spaceId.value }
                .map { it.toSpace() }
                .singleOrNull()
        }

    override suspend fun findAll(spaceIds: Collection<SpaceId>): List<Space> {
        if (spaceIds.isEmpty()) return emptyList()
        return database.tx("spaces.find-all") {
            SpacesTable
                .selectAll()
                .where { SpacesTable.spaceId inList spaceIds.map { it.value }.distinct() }
                .map { it.toSpace() }
                .toList()
        }
    }

    override suspend fun byHandle(handle: String): Space? =
        database.tx("spaces.by-handle") {
            SpacesTable
                .selectAll()
                .where { (SpacesTable.handle eq handle) and (SpacesTable.state neq SpaceState.DELETED.name) }
                .map { it.toSpace() }
                .singleOrNull()
        }

    override suspend fun byCreateKey(ownerId: UserId, createKey: String): Space? =
        database.tx("spaces.by-create-key") {
            SpacesTable
                .selectAll()
                .where { (SpacesTable.ownerId eq ownerId.value) and (SpacesTable.createKey eq createKey) }
                .map { it.toSpace() }
                .singleOrNull()
        }

    override suspend fun takenHandles(handles: Collection<String>): Set<String> {
        if (handles.isEmpty()) return emptySet()
        return database.tx("spaces.taken-handles") {
            SpacesTable
                .select(SpacesTable.handle)
                .where {
                    (SpacesTable.handle inList handles.distinct()) and
                        (SpacesTable.state neq SpaceState.DELETED.name)
                }.map { it[SpacesTable.handle] }
                .toList()
                .toSet()
        }
    }

    override suspend fun update(old: Space, new: Space): Space? =
        database.tx("spaces.update") {
            val changed =
                SpacesTable.update(where = {
                    (SpacesTable.spaceId eq old.spaceId.value) and (SpacesTable.version eq old.version)
                }) {
                    it[version] = old.version + 1
                    write(it, new)
                }
            if (changed == 0) null else new.copy(version = old.version + 1)
        }

    override suspend fun createdSince(ownerId: UserId, since: Instant): List<Instant> =
        database.tx("spaces.created-since") {
            SpacesTable
                .select(SpacesTable.createdAt)
                .where { (SpacesTable.ownerId eq ownerId.value) and (SpacesTable.createdAt greaterEq since) }
                .map { it[SpacesTable.createdAt] }
                .toList()
        }

    override suspend fun ownedBy(ownerId: UserId): List<Space> =
        database.tx("spaces.owned-by") {
            SpacesTable
                .selectAll()
                .where { SpacesTable.ownerId eq ownerId.value }
                .map { it.toSpace() }
                .toList()
        }

    override suspend fun search(
        text: String,
        topic: Topic?,
        offset: Int,
        limit: Int,
    ): List<Space> {
        val words =
            text
                .lowercase()
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotEmpty() }
                .take(MAX_WORDS)
        if (words.isEmpty()) return emptyList()
        val tsQuery = words.joinToString(" & ") { "$it:*" }
        val phrase = "%" + escapeLike(text.trim().lowercase()) + "%"
        return database.tx("spaces.search") {
            SpacesTable
                .selectAll()
                .where {
                    discoverable() and (FullText(tsQuery) or (SpacesTable.name.lowerCase() like phrase)) and
                        topicIs(topic)
                }.orderBy(SpacesTable.memberCount to SortOrder.DESC, SpacesTable.spaceId to SortOrder.ASC)
                .limit(limit)
                .offset(offset.toLong())
                .map { it.toSpace() }
                .toList()
        }
    }

    override suspend fun handlesStartingWith(
        prefix: String,
        offset: Int,
        limit: Int,
    ): List<Space> =
        database.tx("spaces.handles-starting-with") {
            SpacesTable
                .selectAll()
                .where {
                    discoverable() and (SpacesTable.handle like (escapeLike(prefix) + "%")) and
                        (SpacesTable.handle neq prefix)
                }.orderBy(SpacesTable.memberCount to SortOrder.DESC, SpacesTable.handle to SortOrder.ASC)
                .limit(limit)
                .offset(offset.toLong())
                .map { it.toSpace() }
                .toList()
        }

    override suspend fun popular(
        country: String?,
        topic: Topic?,
        exclude: Set<SpaceId>,
        offset: Int,
        limit: Int,
    ): List<Space> =
        database.tx("spaces.popular") {
            SpacesTable
                .selectAll()
                .where {
                    var condition: Op<Boolean> = discoverable() and topicIs(topic)
                    if (country != null) condition = condition and (SpacesTable.country eq country)
                    if (exclude.isNotEmpty()) {
                        condition =
                            condition and (SpacesTable.spaceId notInList exclude.map { it.value })
                    }
                    condition
                }.orderBy(SpacesTable.memberCount to SortOrder.DESC, SpacesTable.spaceId to SortOrder.ASC)
                .limit(limit)
                .offset(offset.toLong())
                .map { it.toSpace() }
                .toList()
        }

    override suspend fun refreshMemberCount(spaceId: SpaceId): Int =
        database.tx("spaces.refresh-member-count") {
            val count =
                MembersTable
                    .selectAll()
                    .where {
                        (MembersTable.spaceId eq spaceId.value) and
                            (MembersTable.paidState.isNull() or (MembersTable.paidState neq PaidState.ENDED.name))
                    }.count()
                    .toInt()
            SpacesTable.update(where = { SpacesTable.spaceId eq spaceId.value }) { it[memberCount] = count }
            count
        }

    override suspend fun touchActivity(spaceId: SpaceId, at: Instant) {
        database.tx("spaces.touch-activity") {
            SpacesTable.update(where = { SpacesTable.spaceId eq spaceId.value }) { it[lastPostAt] = at }
        }
    }

    private fun discoverable(): Op<Boolean> =
        (SpacesTable.state eq SpaceState.LIVE.name) and (SpacesTable.visibility neq Visibility.INVITE_ONLY.name)

    private fun topicIs(topic: Topic?): Op<Boolean> = if (topic == null) Op.TRUE else SpacesTable.topic eq topic.name

    private companion object {
        const val MAX_WORDS = 8
    }
}

/** `%`, `_` and `\` in what a person typed match themselves in a LIKE pattern. */
internal fun escapeLike(text: String): String = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/**
 * `search @@ to_tsquery('simple', ?)` over the generated tsvector the migration declares. The query is built from
 * letters and digits only (`word:* & word:*`) and bound as a parameter, so nothing typed reaches the SQL text.
 */
private class FullText(private val query: String) :
    Op<Boolean>(),
    ComplexExpression {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("spaces.spaces.search @@ to_tsquery('simple', ")
        queryBuilder.registerArgument(TextColumnType(), query)
        queryBuilder.append(")")
    }
}

/** The columns an insert and an update both set. */
private fun SpacesTable.write(row: UpdateBuilder<*>, space: Space) {
    row[handle] = space.handle
    row[name] = space.name
    row[about] = space.about
    row[topic] = space.topic.name
    row[photoMediaId] = space.photoMediaId
    row[coverMediaId] = space.coverMediaId
    row[visibility] = space.visibility.name
    row[priceMinor] = space.price?.amountMinor
    row[pricePeriod] = space.price?.period?.name
    row[postingMode] = space.postingMode.name
    row[privateMessages] = space.privateMessages.name
    row[state] = space.state.name
    row[memberCount] = space.memberCount
    row[lastPostAt] = space.lastPostAt
    row[updatedAt] = space.updatedAt
    row[removedReason] = space.removedReason
}

private fun ResultRow.toSpace(): Space {
    val minor = this[SpacesTable.priceMinor]
    val period = this[SpacesTable.pricePeriod]
    return Space(
        spaceId = SpaceId(this[SpacesTable.spaceId]),
        handle = this[SpacesTable.handle],
        name = this[SpacesTable.name],
        about = this[SpacesTable.about],
        topic = Topic.valueOf(this[SpacesTable.topic]),
        photoMediaId = this[SpacesTable.photoMediaId],
        coverMediaId = this[SpacesTable.coverMediaId],
        visibility = Visibility.valueOf(this[SpacesTable.visibility]),
        price = if (minor != null && period != null) Price(minor, Period.valueOf(period)) else null,
        postingMode = PostingMode.valueOf(this[SpacesTable.postingMode]),
        privateMessages = PrivateMessages.valueOf(this[SpacesTable.privateMessages]),
        state = SpaceState.valueOf(this[SpacesTable.state]),
        ownerId = UserId(this[SpacesTable.ownerId]),
        country = this[SpacesTable.country],
        memberCount = this[SpacesTable.memberCount],
        lastPostAt = this[SpacesTable.lastPostAt],
        createdAt = this[SpacesTable.createdAt],
        updatedAt = this[SpacesTable.updatedAt],
        version = this[SpacesTable.version],
        createKey = this[SpacesTable.createKey],
        removedReason = this[SpacesTable.removedReason],
    )
}
