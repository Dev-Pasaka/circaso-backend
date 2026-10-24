package com.circaso.infrastructure.impl.storage

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Environment
import com.circaso.infrastructure.api.Secrets
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class R2ObjectStoreTest {
    private class MapConfig(private val values: Map<String, String>, private val prefix: String = "") : Config {
        override val environment = Environment.LOCAL
        override fun string(key: String): String? = values[prefix + key]
        override fun section(prefix: String): Config = MapConfig(values, this.prefix + prefix + ".")
    }

    private class MapSecrets(private val values: Map<String, String>) : Secrets {
        override fun get(name: String): String? = values[name]
    }

    private val r2 = mapOf(
        "storage.provider" to "r2",
        "storage.r2.account-id" to "acct123",
        "storage.bucket" to "circaso-backend-dev",
        "storage.prefix" to "backend",
    )
    private val keys = mapOf("r2.access-key-id" to "AKIATESTKEY", "r2.secret-access-key" to "test-secret")

    @Test
    fun `an upload is signed locally for the account's R2 endpoint, the bucket and the module's folder`() = runBlocking<Unit> {
        val stores = ConfiguredObjectStores(MapConfig(r2), MapSecrets(keys))
        val store = stores.scope("users")

        val url = (store.presignUpload("avatars/a.png", "image/png", Duration.ofMinutes(10)) as Resource.Success).data

        assertTrue(url.startsWith("https://"), url)
        assertTrue("acct123.r2.cloudflarestorage.com" in url, "the account's R2 endpoint: $url")
        assertTrue("circaso-backend-dev" in url && "backend/users/avatars/a.png" in url, "bucket and folder: $url")
        assertTrue("X-Amz-Signature=" in url && "X-Amz-Expires=600" in url, "signed, for ten minutes: $url")
        assertTrue("AKIATESTKEY" in url, "the access key id (not the secret) is in the credential scope")
        assertTrue("test-secret" !in url, "the secret never appears")
        stores.close()
    }

    @Test
    fun `a download is signed too, so a private bucket can serve a picture for an hour`() = runBlocking<Unit> {
        val stores = ConfiguredObjectStores(MapConfig(r2), MapSecrets(keys))
        val url = (stores.scope("users").presignDownload("avatars/a.png", Duration.ofHours(1)) as Resource.Success).data
        assertTrue("backend/users/avatars/a.png" in url && "X-Amz-Signature=" in url && "X-Amz-Expires=3600" in url, url)
        assertTrue("test-secret" !in url)
        stores.close()
    }

    @Test
    fun `r2 refuses to start without its account, bucket or keys`() {
        assertFailsWith<IllegalStateException> { ConfiguredObjectStores(MapConfig(r2 - "storage.bucket"), MapSecrets(keys)) }
        assertFailsWith<IllegalStateException> { ConfiguredObjectStores(MapConfig(r2), MapSecrets(emptyMap())) }
    }

    @Test
    fun `local storage still needs nothing`() = runBlocking<Unit> {
        val store = ConfiguredObjectStores(MapConfig(mapOf("storage.prefix" to "backend")), MapSecrets(emptyMap())).scope("users")
        assertIs<Resource.Success<String>>(store.presignUpload("a.png", "image/png", Duration.ofMinutes(1)))
        assertTrue((store.presignDownload("a.png", Duration.ofMinutes(1)) as Resource.Success).data.contains("backend/users/a.png"))
    }
}
