package com.circaso.ops.domain.support

/** A page size within the API conventions: [OpsSettings.pageDefault] when unset, never above [OpsSettings.pageMax]. */
fun OpsSettings.limit(asked: Int?): Int = (asked ?: pageDefault).coerceIn(1, pageMax)
