package com.circaso.users.data

import io.kandra.runtime.repository.KandraSuspendRepository
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope

/**
 * A Kandra repository for entity [T], resolved from Koin by the name Kandra binds it under. It lives in `data`, the
 * only package that touches Kandra; `di` calls it to hand each data class its repositories.
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> Scope.rows(): KandraSuspendRepository<T> =
    get<KandraSuspendRepository<*>>(named("${T::class.simpleName}SuspendRepo")) as KandraSuspendRepository<T>
