package com.circaso.infrastructure.impl.storage

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.HeadObjectRequest
import aws.sdk.kotlin.services.s3.model.NotFound
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import aws.smithy.kotlin.runtime.client.config.RequestHttpChecksumConfig
import aws.smithy.kotlin.runtime.client.config.ResponseHttpChecksumConfig
import aws.smithy.kotlin.runtime.net.url.Url
import com.circaso.infrastructure.api.ObjectStore
import com.circaso.infrastructure.api.StoredObject
import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * One S3 client for the Cloudflare R2 bucket, built with the AWS SDK for Kotlin. R2 speaks the S3 API at
 * `https://<account id>.r2.cloudflarestorage.com` with the region `auto`. The SDK's default of adding a CRC checksum to
 * every request is switched to "only when the operation requires it", because R2 does not accept the checksum trailers
 * the newer SDK versions add by default. The keys are never logged.
 */
class R2Client(
    accountId: String,
    val bucket: String,
    accessKeyId: String,
    secretAccessKey: String,
) : AutoCloseable {
    val s3: S3Client =
        S3Client {
            region = "auto"
            endpointUrl = Url.parse("https://$accountId.r2.cloudflarestorage.com")
            credentialsProvider =
                StaticCredentialsProvider {
                    this.accessKeyId = accessKeyId
                    this.secretAccessKey = secretAccessKey
                }
            requestChecksumCalculation = RequestHttpChecksumConfig.WHEN_REQUIRED
            responseChecksumValidation = ResponseHttpChecksumConfig.WHEN_REQUIRED
        }

    override fun close() = s3.close()
}

/** A module's folder in the private R2 bucket: [folder] is `backend/users`, and every key is under it. */
class R2ObjectStore(private val client: R2Client, private val folder: String) : ObjectStore {
    private val log = LoggerFactory.getLogger(R2ObjectStore::class.java)

    private object Unavailable : DomainError {
        override val code = "storage.unavailable"
        override val message = "File storage is unavailable. Try again shortly."
        override val kind = ErrorKind.UPSTREAM
    }

    private fun full(key: String) = "$folder/${key.trimStart('/')}"

    override suspend fun presignUpload(
        key: String,
        contentType: String,
        expiresIn: Duration,
    ): Resource<String> =
        try {
            val request =
                PutObjectRequest {
                    bucket = client.bucket
                    this.key = full(key)
                    this.contentType = contentType
                }
            Resource.success(
                client.s3
                    .presignPutObject(request, expiresIn.toKotlinDuration())
                    .url
                    .toString(),
            )
        } catch (e: Exception) {
            log.warn("could not presign an upload", e)
            Resource.error(Unavailable, e)
        }

    override suspend fun describe(key: String): StoredObject? =
        try {
            val head =
                client.s3.headObject(
                    HeadObjectRequest {
                        bucket = client.bucket
                        this.key = full(key)
                    },
                )
            StoredObject(head.contentLength ?: 0L, head.contentType ?: "application/octet-stream")
        } catch (_: NotFound) {
            null
        }

    override suspend fun delete(key: String) {
        client.s3.deleteObject(
            DeleteObjectRequest {
                bucket = client.bucket
                this.key = full(key)
            },
        )
    }

    override suspend fun presignDownload(key: String, expiresIn: Duration): Resource<String> =
        try {
            val request =
                GetObjectRequest {
                    bucket = client.bucket
                    this.key = full(key)
                }
            Resource.success(
                client.s3
                    .presignGetObject(request, expiresIn.toKotlinDuration())
                    .url
                    .toString(),
            )
        } catch (e: Exception) {
            log.warn("could not presign a download", e)
            Resource.error(Unavailable, e)
        }
}
