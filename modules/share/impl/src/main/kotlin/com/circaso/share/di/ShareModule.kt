package com.circaso.share.di

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.share.data.cache.RedisViewThrottle
import com.circaso.share.data.repositories.LinkRepositoryImpl
import com.circaso.share.data.repositories.ShareExpiry
import com.circaso.share.data.tables.LinksTable
import com.circaso.share.domain.ports.ViewThrottle
import com.circaso.share.domain.repositories.LinkRepository
import com.circaso.share.domain.support.ShareConfig
import com.circaso.share.domain.support.Snapshots
import com.circaso.share.domain.usecases.CreateShareLink
import com.circaso.share.domain.usecases.ListShareLinks
import com.circaso.share.domain.usecases.OpenShareLink
import com.circaso.share.domain.usecases.RemoveAccountLinks
import com.circaso.share.domain.usecases.RevokeShareLink
import com.circaso.share.domain.usecases.ViewSharePage
import com.circaso.share.presentation.controllers.shareController
import com.circaso.share.presentation.controllers.sharePageController
import com.circaso.share.presentation.events.IdentityEventHandler
import com.circaso.share.presentation.services.IdentityEventsService
import com.circaso.share.presentation.services.SharePageService
import com.circaso.share.presentation.services.ShareService
import com.circaso.shared.EventSubscription
import com.circaso.web.PageContributor
import com.circaso.web.RouteContributor
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.time.Clock
import java.time.Duration

/** Schema `share`: one table and its Flyway scripts. It publishes no events, so it has no outbox. */
internal val shareDatabaseSpec =
    DatabaseModuleSpec(
        schema = "share",
        tables = listOf(LinksTable),
        migrationsLocation = "classpath:db/share",
    )

/** Redis prefix `shr:` (`shr:views:{hash of the caller's address}`). */
private const val REDIS_PREFIX = "shr"

/** `share.purge-expired`: links 30 days past their end or their revoking. */
private val PURGE_INTERVAL: Duration = Duration.ofMinutes(30)

/** Share's settings from `SHARE_*` (`.env.example` → share links). */
fun shareConfig(config: Config): ShareConfig {
    val defaults = ShareConfig()
    return ShareConfig(
        baseUrl = config.string("SHARE_BASE_URL", defaults.baseUrl).trim().trimEnd('/'),
        androidPackage = config.string("SHARE_ANDROID_PACKAGE", defaults.androidPackage).trim(),
        androidCertFingerprints = config.list("SHARE_ANDROID_CERT_SHA256").map { it.uppercase() },
        iosAppIds = config.list("SHARE_IOS_APP_IDS").ifEmpty { defaults.iosAppIds },
        androidStoreUrl = config.string("SHARE_ANDROID_STORE_URL", defaults.androidStoreUrl).trim(),
        iosStoreUrl = config.string("SHARE_IOS_STORE_URL", defaults.iosStoreUrl).trim(),
        websiteUrl = config.string("SHARE_WEBSITE_URL", defaults.websiteUrl).trim(),
        previewImageUrl = config.string("SHARE_PREVIEW_IMAGE_URL", defaults.previewImageUrl).trim(),
        viewsPerMinute = config.int("SHARE_VIEWS_PER_MINUTE", defaults.viewsPerMinute.toInt()).toLong(),
    )
}

private fun Config.list(key: String): List<String> =
    string(key)
        .orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/**
 * Share's Koin bindings: its database spec and purge job, the app's routes (SH1-SH4), the public page and the app
 * association files ([PageContributor]) and its `events:identity` subscription.
 */
fun shareModule(config: Config): Module =
    module {
        val clock = Clock.systemUTC()
        val share = shareConfig(config)

        single { share }

        // data
        single(named("share.spec")) { shareDatabaseSpec }
        single(named("share.database")) { get<Databases>().scope(shareDatabaseSpec.schema) }
        single<LinkRepository> { LinkRepositoryImpl(get(named("share.database"))) }
        single<ViewThrottle> { RedisViewThrottle(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single(named("share.purge-expired")) {
            val expiry = ShareExpiry(get(named("share.database")))
            ScheduledJob("share.purge-expired", PURGE_INTERVAL) { expiry.run() }
        }

        // domain
        single { Snapshots(get(), get(), get(), get(), get(), clock) }
        single { CreateShareLink(get(), get(), share, clock) }
        single { ListShareLinks(get(), clock) }
        single { RevokeShareLink(get(), share, clock) }
        single { OpenShareLink(get(), get(), get(), get(), clock) }
        single { ViewSharePage(get(), get(), get(), share, clock) }
        single { RemoveAccountLinks(get()) }

        // presentation
        single { ShareService(get(), get(), get(), get()) }
        single { SharePageService(get()) }
        single { IdentityEventsService(get()) }
        single<RouteContributor>(named("share.routes")) {
            val shares = get<ShareService>()
            RouteContributor { route -> route.shareController(shares, share) }
        }
        single<PageContributor>(named("share.pages")) {
            val pages = get<SharePageService>()
            PageContributor { route -> route.sharePageController(pages, share) }
        }
        single<EventSubscription>(named("share.identity-events")) { IdentityEventHandler(get()).subscription() }
    }
