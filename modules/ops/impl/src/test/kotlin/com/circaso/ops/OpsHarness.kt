package com.circaso.ops

import com.circaso.assistant.api.AssistantReports
import com.circaso.calls.api.CallsAdmin
import com.circaso.support.api.DiagnosticLogDesk
import com.circaso.identity.api.AccountAdmin
import com.circaso.identity.api.SupportAccounts
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Environment
import com.circaso.integrations.api.AiBilling
import com.circaso.messaging.api.GroupReports
import com.circaso.ops.di.opsModule
import com.circaso.ops.domain.repositories.AuditRepository
import com.circaso.ops.domain.repositories.StaffRepository
import com.circaso.shared.StaffRole
import com.circaso.support.api.SupportDesk
import com.circaso.users.api.ProfileAdmin
import com.circaso.wallet.api.FeeAdmin
import com.circaso.wallet.api.WalletReports
import com.circaso.web.OpsRouteContributor
import com.circaso.web.StaffPrincipal
import com.circaso.web.StaffPrincipalKey
import com.circaso.web.StaffSignIn
import com.circaso.web.installJson
import com.circaso.web.installRequestId
import com.circaso.web.installStatusPages
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.UUID

/** The whole ops module over fakes of every contract it reaches, the way `app` wires it, minus the database. */
class OpsHarness(env: Map<String, String> = mapOf("OPS_BOOTSTRAP_ADMINS" to BOOTSTRAP)) {
    val accounts = FakeAccounts()
    val profiles = FakeProfiles()
    val groups = FakeGroups()
    val wallets = FakeWallets()
    val desk = FakeDesk()
    val supportAccounts = FakeSupportAccounts()
    val staff = MemoryStaff()
    val audit = MemoryAudit()
    val fees = FakeFeeAdmin()
    val usage = FakeAssistantReports()
    val billing = FakeAiBilling()
    val calls = FakeCallsAdmin()
    val logs = FakeLogDesk()

    val koin: Koin =
        koinApplication {
            allowOverride(true)
            modules(
                opsModule(MapConfig(env)),
                module {
                    single<AccountAdmin> { accounts }
                    single<ProfileAdmin> { profiles }
                    single<GroupReports> { groups }
                    single<WalletReports> { wallets }
                    single<SupportDesk> { desk }
                    single<SupportAccounts> { supportAccounts }
                    single<StaffRepository> { staff }
                    single<AuditRepository> { audit }
                    single<FeeAdmin> { fees }
                    single<AssistantReports> { usage }
                    single<AiBilling> { billing }
                    single<CallsAdmin> { calls }
                    single<DiagnosticLogDesk> { logs }
                },
            )
        }.koin

    /** The same staff id per role, so a test can tell who acted. */
    val ids = StaffRole.entries.associateWith { UUID.randomUUID() }

    /** The staff routes behind a stand-in for the ops gateway: `X-Test-Role` says who is signed in. */
    fun install(application: Application) {
        application.installRequestId()
        application.installJson()
        application.installStatusPages()
        application.intercept(ApplicationCallPipeline.Plugins) {
            val role = call.request.headers[ROLE_HEADER]?.let(StaffRole::valueOf) ?: return@intercept
            call.attributes.put(
                StaffPrincipalKey,
                StaffPrincipal(ids.getValue(role), "${role.name.lowercase()}@circaso.com", role, StaffSignIn.ACCESS),
            )
        }
        val routes = koin.get<OpsRouteContributor>(named("ops.routes"))
        application.routing { route("/v1") { routes.install(this) } }
    }

    companion object {
        const val ROLE_HEADER = "X-Test-Role"
        const val BOOTSTRAP = "founder@circaso.com"
    }
}

/** Settings from a map, as the environment-backed config reads them. */
class MapConfig(private val values: Map<String, String>, private val prefix: String = "") : Config {
    override val environment: Environment = Environment.LOCAL

    override fun string(key: String): String? = values[(prefix + key).uppercase().replace('.', '_').replace('-', '_')]

    override fun section(prefix: String): Config = MapConfig(values, "${this.prefix}$prefix.")
}
