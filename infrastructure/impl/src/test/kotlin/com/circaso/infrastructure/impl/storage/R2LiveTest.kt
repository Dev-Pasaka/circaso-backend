package com.circaso.infrastructure.impl.storage

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Environment
import com.circaso.infrastructure.api.Secrets
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Talks to the real bucket, so it runs only when `STORAGE_R2_ACCOUNT_ID`, `STORAGE_BUCKET`, `R2_ACCESS_KEY_ID` and
 * `R2_SECRET_ACCESS_KEY` are in the environment (from `.env`). Writes one small file under `backend/livetest/` and removes it.
 */
class R2LiveTest {
    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

    @Test
    fun `an upload, a read and a delete through signed links against the real bucket`() = runBlocking {
        assumeTrue(listOf("STORAGE_R2_ACCOUNT_ID", "STORAGE_BUCKET", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY").all { env(it) != null }, "R2 settings are not in the environment")

        val config = object : Config {
            override val environment = Environment.LOCAL
            val values = mapOf("storage.provider" to "r2", "storage.r2.account-id" to env("STORAGE_R2_ACCOUNT_ID")!!, "storage.bucket" to env("STORAGE_BUCKET")!!, "storage.prefix" to "backend")
            override fun string(key: String): String? = values[key]
            override fun section(prefix: String): Config = object : Config {
                override val environment = Environment.LOCAL
                override fun string(key: String): String? = values["$prefix.$key"]
                override fun section(prefix: String): Config = throw UnsupportedOperationException()
            }
        }
        val secrets = object : Secrets {
            override fun get(name: String): String? = when (name) {
                "r2.access-key-id" -> env("R2_ACCESS_KEY_ID")
                "r2.secret-access-key" -> env("R2_SECRET_ACCESS_KEY")
                else -> null
            }
        }
        val stores = ConfiguredObjectStores(config, secrets)
        val store = stores.scope("livetest")
        val http = HttpClient.newHttpClient()
        val key = "check/${UUID.randomUUID()}.txt"
        val body = "circaso r2 check"
        try {
            val put = (store.presignUpload(key, "text/plain", Duration.ofMinutes(2)) as Resource.Success).data
            val uploaded = http.send(HttpRequest.newBuilder(URI.create(put)).header("Content-Type", "text/plain").PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, uploaded.statusCode(), "the signed upload: ${uploaded.body()}")

            val described = store.describe(key)
            assertEquals(body.length.toLong(), described?.sizeBytes)
            assertEquals("text/plain", described?.contentType)

            val get = (store.presignDownload(key, Duration.ofMinutes(2)) as Resource.Success).data
            val read = http.send(HttpRequest.newBuilder(URI.create(get)).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, read.statusCode())
            assertEquals(body, read.body())

            val anonymous = http.send(HttpRequest.newBuilder(URI.create(get.substringBefore('?'))).GET().build(), HttpResponse.BodyHandlers.discarding())
            assertTrue(anonymous.statusCode() in 400..403, "the bucket is private: no signature, no file (was ${anonymous.statusCode()})")
        } finally {
            store.delete(key)
            stores.close()
        }
    }
}
