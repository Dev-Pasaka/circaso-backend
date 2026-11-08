package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.WarmUp
import io.r2dbc.spi.Connection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * The warm-up's first step: every connection of the app pool runs a statement, all of them held at once so none is
 * handed out twice. r2dbc-pool hands out the most recently used connection first, so with fewer warm-up workers than
 * connections the rest would wait, untouched, for the first busy minute after a deploy.
 */
class PoolTouch(private val pools: PostgresPools) : WarmUp {
    override val name = "database.pool"
    override val once = true

    override suspend fun run() {
        val pool = pools.app
        coroutineScope {
            val held =
                List(pool.settings.maxSize) {
                    async { runCatching { Mono.from(pool.connections.create()).awaitSingle() } }
                }.awaitAll()
            try {
                held.mapNotNull { it.getOrNull() }.map { async { touch(it) } }.awaitAll()
            } finally {
                held.mapNotNull { it.getOrNull() }.forEach { Mono.from(it.close()).awaitFirstOrNull() }
            }
            held.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        }
    }

    private suspend fun touch(connection: Connection) {
        Flux
            .from(connection.createStatement("SELECT 1").execute())
            .flatMap { result -> result.map { row, _ -> row.get(0) } }
            .collectList()
            .awaitSingle()
    }
}
