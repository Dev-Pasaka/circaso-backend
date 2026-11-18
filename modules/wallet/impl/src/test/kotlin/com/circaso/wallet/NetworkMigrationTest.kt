package com.circaso.wallet

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.wallet.di.walletDatabaseSpec
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * V12 (networks, decision 2026-11-18) applied to a database that already holds testnet data, as the live one does:
 * the scripts up to V11 are run in a schema of their own, rows are written as the server before networks wrote them,
 * and then V12 is applied on top. Nothing is lost, every row becomes testnet, everyone with a wallet keeps the
 * testnet, and a server from before the script can still write while it is being rolled out.
 */
class NetworkMigrationTest {
    private val schema = "wallet_before_networks"

    private fun script(version: Int): String {
        val name =
            checkNotNull(SCRIPTS.firstOrNull { it.startsWith("V${version}__") }) { "no wallet script V$version" }
        val sql = checkNotNull(javaClass.getResourceAsStream("/db/wallet/$name")) { name }.bufferedReader().readText()
        return sql.replace("wallet.", "$schema.")
    }

    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.one(sql: String): String? =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    @Test
    fun `existing rows become testnet, their holders keep the testnet, and nothing is lost`() {
        // Makes sure the container is up; the scratch schema below is separate from the migrated one.
        TestPostgres.database(walletDatabaseSpec)
        val amina = UUID.randomUUID() // a personal wallet
        val bob = UUID.randomUUID() // signs for a group's wallet, no wallet of his own
        val groupWallet = UUID.randomUUID()
        val chat = UUID.randomUUID()
        TestPostgres.superuser { db ->
            db.run("DROP SCHEMA IF EXISTS $schema CASCADE; CREATE SCHEMA $schema")
            (1..11).forEach { db.run(script(it)) }
            db.run(
                """
                INSERT INTO $schema.circle_users (user_id, circle_user_id, created_at) VALUES
                    ('$amina', '${UUID.randomUUID()}', now()), ('$groupWallet', '${UUID.randomUUID()}', now());
                INSERT INTO $schema.wallets (circle_wallet_id, user_id, blockchain, account_type, address, state, created_at)
                    VALUES ('cw-1', '$amina', 'BASE-SEPOLIA', 'SCA', '0xa', 'ACTIVE', now()),
                           ('cw-2', '$amina', 'SOL-DEVNET', 'EOA', 'So1', 'ACTIVE', now()),
                           ('cw-3', '$groupWallet', 'BASE-SEPOLIA', 'SCA', '0xb', 'ACTIVE', now());
                INSERT INTO $schema.wallet_owners (wallet_id, owner_type, chat_id, signer_id, created_by, created_at)
                    VALUES ('$groupWallet', 'GROUP', '$chat', '$bob', '$bob', now());
                INSERT INTO $schema.payment_holds (user_id, reason, expires_at)
                    VALUES ('$amina', 'VAULT_OPENED', now() + interval '1 day');
                INSERT INTO $schema.transfers (transfer_id, sender_id, sender_wallet_id, blockchain, idempotency_key,
                        destination_address, amount_minor, asset, state, origin, created_at, updated_at, usd_total_minor)
                    VALUES ('${UUID.randomUUID()}', '$amina', 'cw-1', 'BASE-SEPOLIA', '${UUID.randomUUID()}', '0xb',
                            5000000, 'USDC', 'CONFIRMED', 'APP', now(), now(), 5000000);
                INSERT INTO $schema.transactions (id, user_id, direction, state, amount_minor, asset, blockchain,
                        occurred_at) VALUES ('${UUID.randomUUID()}', '$amina', 'OUT', 'CONFIRMED', 5000000, 'USDC',
                        'BASE-SEPOLIA', now());
                """.trimIndent(),
            )
            val limitRules = db.one("SELECT count(*) FROM $schema.limit_rules")
            val feeRules = db.one("SELECT count(*) FROM $schema.fee_rules")

            db.run(script(12))

            assertEquals("2", db.one("SELECT count(*) FROM $schema.circle_users WHERE network = 'TESTNET'"))
            assertEquals("3", db.one("SELECT count(*) FROM $schema.wallets WHERE network = 'TESTNET'"))
            assertEquals("TESTNET", db.one("SELECT network FROM $schema.transfers"))
            assertEquals("TESTNET", db.one("SELECT network FROM $schema.transactions"))
            assertEquals("TESTNET", db.one("SELECT network FROM $schema.wallet_owners"))
            assertEquals("TESTNET", db.one("SELECT network FROM $schema.payment_holds"))
            assertEquals(limitRules, db.one("SELECT count(*) FROM $schema.limit_rules WHERE network IS NULL"))
            assertEquals(feeRules, db.one("SELECT count(*) FROM $schema.fee_rules WHERE network IS NULL"))
            assertEquals("0", db.one("SELECT count(*) FROM $schema.network_settings"), "the server seeds the settings")

            // Everyone with a wallet keeps the testnet: the person, and the group wallet's signer; never the group's id.
            assertEquals(
                listOf(amina, bob).map { it.toString() }.sorted().joinToString(","),
                db.one(
                    "SELECT string_agg(user_id::text, ',' ORDER BY user_id::text) FROM $schema.network_access " +
                        "WHERE network = 'TESTNET' AND source = 'GRANDFATHERED'",
                ),
            )
            assertEquals("2", db.one("SELECT count(*) FROM $schema.network_access"))

            // A server from before the script, still running, writes without the column: it lands on the testnet.
            db.run(
                "INSERT INTO $schema.circle_users (user_id, circle_user_id, created_at) " +
                    "VALUES ('$bob', '${UUID.randomUUID()}', now()) ON CONFLICT DO NOTHING",
            )
            assertEquals("TESTNET", db.one("SELECT network FROM $schema.circle_users WHERE user_id = '$bob'"))

            // The same person on mainnet is a second row; a mainnet chain's rows say MAINNET by themselves.
            db.run(
                "INSERT INTO $schema.circle_users (user_id, network, circle_user_id, created_at) " +
                    "VALUES ('$amina', 'MAINNET', '${UUID.randomUUID()}', now())",
            )
            db.run(
                "INSERT INTO $schema.wallets (circle_wallet_id, user_id, blockchain, account_type, address, state, " +
                    "created_at) VALUES ('cw-9', '$amina', 'BASE', 'SCA', '0xa', 'ACTIVE', now())",
            )
            assertEquals("MAINNET", db.one("SELECT network FROM $schema.wallets WHERE circle_wallet_id = 'cw-9'"))
            db.run(
                "INSERT INTO $schema.wallet_owners (wallet_id, owner_type, chat_id, signer_id, created_by, " +
                    "created_at, network) VALUES ('${UUID.randomUUID()}', 'GROUP', '$chat', '$bob', '$bob', now(), 'MAINNET')",
            )
            assertFailsWith<java.sql.SQLException>("one wallet per group per network") {
                db.run(
                    "INSERT INTO $schema.wallet_owners (wallet_id, owner_type, chat_id, signer_id, created_by, " +
                        "created_at, network) " +
                        "VALUES ('${UUID.randomUUID()}', 'GROUP', '$chat', '$bob', '$bob', now(), 'MAINNET')",
                )
            }
            assertFailsWith<java.sql.SQLException>("a chain belongs to one network") {
                db.run(
                    "INSERT INTO $schema.network_settings (network, state, app_id, default_chain, updated_at) " +
                        "VALUES ('MAINNET', 'OFF', '', 'BASE', now()); " +
                        "INSERT INTO $schema.network_chains (blockchain, network, position, enabled, " +
                        "receive_enabled, account_type) VALUES ('BASE-SEPOLIA', 'MAINNET', 0, true, true, 'SCA')",
                )
            }
            db.run("DROP SCHEMA $schema CASCADE")
        }
    }

    private companion object {
        val SCRIPTS =
            listOf(
                "V1__wallet.sql",
                "V2__transfers.sql",
                "V3__group_wallets.sql",
                "V4__fees_limits.sql",
                "V5__space_wallets.sql",
                "V6__split_settlement.sql",
                "V7__inbound_transfer_link.sql",
                "V8__any_held_coin.sql",
                "V9__staff_reports.sql",
                "V10__fee_gas_inclusive.sql",
                "V11__payment_holds.sql",
                "V12__networks.sql",
            )
    }
}
