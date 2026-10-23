package com.circaso.infrastructure.impl.logging

import net.logstash.logback.argument.StructuredArgument
import net.logstash.logback.argument.StructuredArguments

/** A structured log argument: a field in JSON, `key=value` on the console. */
internal fun kv(name: String, value: Any?): StructuredArgument = StructuredArguments.kv(name, value)

private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** Redis keys carry ids; the log line keeps the first 8 characters of each (Logging → Service interactions). */
internal fun shortenIds(key: String): String = uuid.replace(key) { it.value.take(8) + "…" }

internal fun Long.millisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000
