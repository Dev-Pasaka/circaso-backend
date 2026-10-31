package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.WebResult
import com.circaso.integrations.api.WebSearch
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Local runs and tests without a key: two made-up results that repeat the query, so a turn that searches still has
 * something to cite. Records every query in [asked]; [isDown] answers unavailable instead.
 */
class FakeWebSearch : WebSearch {
    val asked: MutableList<String> = CopyOnWriteArrayList()
    var isDown = false

    override suspend fun search(query: String, limit: Int): Resource<List<WebResult>> {
        asked += query
        if (isDown) return Resource.error(IntegrationError.Unavailable("Fake web search"))
        val slug = URLEncoder.encode(query.trim(), Charsets.UTF_8)
        val results =
            listOf(
                WebResult("About $query", "https://example.com/search?q=$slug", "What the web says about $query."),
                WebResult("$query explained", "https://example.org/guide?q=$slug", "A short guide to $query."),
            )
        return Resource.success(results.take(limit.coerceAtLeast(1)))
    }
}

/** Says a search happened (its length, never the words) and finds nothing. */
class LogWebSearch : WebSearch {
    private val log = LoggerFactory.getLogger(LogWebSearch::class.java)

    override suspend fun search(query: String, limit: Int): Resource<List<WebResult>> {
        log.info("web search asked for: {} characters, limit {}", query.length, limit)
        return Resource.success(emptyList())
    }
}
