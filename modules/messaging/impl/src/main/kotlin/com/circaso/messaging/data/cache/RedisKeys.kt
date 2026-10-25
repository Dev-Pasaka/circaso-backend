package com.circaso.messaging.data.cache

/**
 * Messaging's Redis prefix: every key it writes is `msg:<purpose>:<id>` (`msg:members:{chatId}`,
 * `msg:pending:{messageId}`, `msg:seq:{userId}` …, Messaging → Data → Redis). The keys arrive with their slices.
 */
const val REDIS_PREFIX = "msg"
