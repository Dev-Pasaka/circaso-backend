package com.circaso.users.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.users.data.tables.ProfilesTable
import com.circaso.users.domain.models.ProfileKey
import com.circaso.users.domain.repositories.ProfileSearchRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select

/**
 * Staff search over `users.profiles` (decision 2026-11-08), on the two `COLLATE "C"` indexes of V4__admin_search.
 *
 * Each search key is compared in the C collation (byte order): there a prefix is exactly the range `[prefix, prefix +
 * U+10FFFF)`, so the index serves the filter, the keyset and the order at once, whatever the prefix and however the
 * statement was planned (a `LIKE` on a bound parameter alone can't use an index in a generic plan). The `LIKE` stays
 * on top, with `%`, `_` and `\` escaped, so the answer is exact; the range only makes it cheap.
 */
class ProfileSearchRepositoryImpl(private val database: Database) : ProfileSearchRepository {
    override suspend fun byHandlePrefix(
        prefix: String,
        after: ProfileKey?,
        limit: Int,
    ): List<ProfileKey> =
        database.tx("profiles.search-handle") {
            val key = CollateC(ProfilesTable.handle)
            ProfilesTable
                .select(ProfilesTable.userId, key)
                .where {
                    // Handles are unique among profiles, so the handle alone is the keyset.
                    val page = after?.let { key greater it.sortKey } ?: Op.TRUE
                    startsWith(key, prefix) and page
                }.orderBy(key to SortOrder.ASC)
                .limit(limit)
                .map { ProfileKey(it[key], UserId(it[ProfilesTable.userId])) }
                .toList()
        }

    override suspend fun byNamePrefix(
        prefix: String,
        after: ProfileKey?,
        limit: Int,
    ): List<ProfileKey> =
        database.tx("profiles.search-name") {
            val key = CollateC(ProfilesTable.displayName.lowerCase())
            ProfilesTable
                .select(ProfilesTable.userId, key)
                .where {
                    val page = after?.let { after(key, it) } ?: Op.TRUE
                    startsWith(key, prefix) and page
                }.orderBy(key to SortOrder.ASC, ProfilesTable.userId to SortOrder.ASC)
                .limit(limit)
                .map { ProfileKey(it[key], UserId(it[ProfilesTable.userId])) }
                .toList()
        }

    /** The index range of [prefix], then the exact match. */
    private fun startsWith(key: CollateC, prefix: String): Op<Boolean> =
        (key greaterEq prefix) and
            (key less prefix + LAST_CHARACTER) and
            (key like LikePattern(escapeLike(prefix) + "%", '\\'))

    /** Rows after [last] in `(key, user_id)` order; the leading `>=` lets the planner read it as an index range. */
    private fun after(key: CollateC, last: ProfileKey): Op<Boolean> =
        (key greaterEq last.sortKey) and
            (
                (key greater last.sortKey) or
                    ((key eq last.sortKey) and (ProfilesTable.userId greater last.userId.value))
            )

    private companion object {
        /** U+10FFFF, the highest code point: in byte order every text that starts with the prefix sorts below it. */
        const val LAST_CHARACTER = "􏿿"
    }
}

/** `%`, `_` and `\` in a staff search match themselves in a LIKE pattern. */
internal fun escapeLike(text: String): String = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/**
 * `(expr COLLATE "C")`, the expression V4's indexes are built on, so the planner matches them. The DSL has no
 * collation; what it wraps is the DSL's own column or function, so nothing is spliced in.
 */
private class CollateC(private val expr: Expression<*>) : ExpressionWithColumnType<String>() {
    override val columnType: IColumnType<String> = TextColumnType()

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("(")
        queryBuilder.append(expr)
        queryBuilder.append(" COLLATE \"C\")")
    }
}
