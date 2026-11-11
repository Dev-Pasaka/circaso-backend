package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.models.GroupKeyPage
import com.circaso.identity.domain.models.GroupKeyPosition
import com.circaso.identity.domain.repositories.KeyBackupRepository
import com.circaso.identity.domain.support.GroupKeyCursors
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId

/** The most group keys one page holds. */
private const val MAX_PAGE = 500

/** KB5 `GET /key-backup/group-keys`: every backed-up group key, in `(chatId, metaVersion)` order, by opaque cursor. */
class ListGroupKeys(private val keys: KeyBackupRepository) {
    suspend operator fun invoke(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<GroupKeyPage> {
        val after =
            cursor?.let {
                GroupKeyCursors.decode(it) ?: return Resource.error(SharedError.Malformed("cursor", "invalid"))
            }
        val page = limit.coerceIn(1, MAX_PAGE)
        val found = keys.listGroupKeys(userId, after, page + 1)
        val shown = found.take(page)
        val next =
            if (found.size > page) {
                GroupKeyCursors.encode(shown.last().let { GroupKeyPosition(it.chatId, it.metaVersion) })
            } else {
                null
            }
        return Resource.success(GroupKeyPage(shown, next))
    }
}
