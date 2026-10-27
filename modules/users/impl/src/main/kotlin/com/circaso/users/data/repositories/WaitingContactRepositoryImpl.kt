package com.circaso.users.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.users.data.tables.WaitingContactsTable
import com.circaso.users.domain.models.WaitingContact
import com.circaso.users.domain.repositories.WaitingContactRepository
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.r2dbc.selectAll
import java.time.Clock
import java.time.Duration

/** How long a waiting number is kept after its last sync (users.md → Data). */
internal val WAITING_TTL: Duration = Duration.ofDays(180)

class WaitingContactRepositoryImpl(private val database: Database, private val clock: Clock) :
    WaitingContactRepository {
    override suspend fun waitingFor(phoneHmac: String): List<WaitingContact> =
        database.tx("waiting-contacts.find") {
            WaitingContactsTable
                .selectAll()
                .where {
                    (WaitingContactsTable.phoneHmac eq phoneHmac) and
                        (WaitingContactsTable.expiresAt greater clock.instant())
                }.toList()
                .map {
                    WaitingContact(
                        it[WaitingContactsTable.phoneHmac],
                        UserId(it[WaitingContactsTable.ownerId]),
                        it[WaitingContactsTable.addedAt],
                    )
                }
        }
}
