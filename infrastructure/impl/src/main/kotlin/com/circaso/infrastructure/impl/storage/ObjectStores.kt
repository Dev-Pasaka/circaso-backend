package com.circaso.infrastructure.impl.storage

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.ObjectStore
import com.circaso.infrastructure.api.ObjectStores
import com.circaso.infrastructure.api.Secrets
import com.circaso.infrastructure.api.StoredObject
import com.circaso.shared.Resource
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Chosen by `storage.provider`: `local` (the default) keeps objects in memory and hands out fake upload URLs, so
 * local runs and tests need no R2; `r2` is the Cloudflare bucket, reached with the AWS SDK for Kotlin ([R2ObjectStore]).
 * R2 needs `storage.r2.account-id`, `storage.bucket`, the secrets `r2.access-key-id` and `r2.secret-access-key`, and
 * optionally `storage.prefix` (a folder every module's prefix goes under). The bucket is private: files are read and written
 * through pre-signed URLs only.
 * Anything missing stops startup, naming it.
 */
class ConfiguredObjectStores(config: Config, secrets: Secrets) :
    ObjectStores,
    AutoCloseable {
    private val storage = config.section("storage")
    private val provider = storage.string("provider", "local")
    private val folder = storage.string("prefix", "").trim('/')
    private val local = ConcurrentHashMap<String, StoredObject>()

    private val r2: R2Client? =
        if (provider == "r2") {
            R2Client(
                accountId = storage.requireString("r2.account-id"),
                bucket = storage.requireString("bucket"),
                accessKeyId = secrets.require("r2.access-key-id"),
                secretAccessKey = secrets.require("r2.secret-access-key"),
            )
        } else {
            null
        }

    init {
        require(provider in setOf("local", "r2")) { "storage.provider must be local or r2 (was '$provider')" }
    }

    /** Closes the R2 client's connections; Koin calls this when the app stops. */
    override fun close() {
        r2?.close()
    }

    private fun under(prefix: String) = listOf(folder, prefix).filter { it.isNotEmpty() }.joinToString("/")

    override fun scope(prefix: String): ObjectStore =
        r2?.let { R2ObjectStore(it, under(prefix)) } ?: LocalObjectStore(under(prefix), local)
}

/** In memory. [upload] stands in for the phone's PUT in tests. */
class LocalObjectStore(
    private val prefix: String,
    private val objects: MutableMap<String, StoredObject> = ConcurrentHashMap(),
) : ObjectStore {
    override suspend fun presignUpload(
        key: String,
        contentType: String,
        expiresIn: Duration,
    ): Resource<String> = Resource.success("http://localhost/local-upload/$prefix/$key?expires=${expiresIn.seconds}")

    override suspend fun describe(key: String): StoredObject? = objects["$prefix/$key"]

    override suspend fun delete(key: String) {
        objects.remove("$prefix/$key")
    }

    override suspend fun presignDownload(key: String, expiresIn: Duration): Resource<String> =
        Resource.success("http://localhost/local-download/$prefix/$key?expires=${expiresIn.seconds}")

    fun upload(
        key: String,
        sizeBytes: Long,
        contentType: String,
    ) {
        objects["$prefix/$key"] = StoredObject(sizeBytes, contentType)
    }
}
