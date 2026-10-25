package com.circaso.app

import com.circaso.infrastructure.api.KandraModuleSpec
import com.circaso.infrastructure.impl.config.EnvConfig
import io.kandra.migrate.KandraMigration
import org.koin.dsl.koinApplication
import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards every module's migrations against Kandra bug #5 (docs/kandra-bugs.md): an inline call in a migration
 * (`forEach`, `let`, `use`, ...) leaves Kotlin's line table, the SMAP, in the class constant pool, and Kandra hashes
 * it into the checksum, so moving the migration's lines stops the deploy on an already-applied migration.
 */
class MigrationClassesTest {
    private val specs =
        koinApplication { modules(businessModules(EnvConfig(emptyMap()))) }.koin.getAll<KandraModuleSpec>()

    @Test
    fun `no migration class carries a line table in its constant pool`() {
        val migrations = specs.flatMap { it.migrations }
        assertTrue(migrations.isNotEmpty(), "every module's migrations are collected")

        val offenders = migrations.filter { smapConstants(it).isNotEmpty() }.map { it::class.qualifiedName }

        assertTrue(offenders.isEmpty(), "migrations with an inline call (use a plain for loop): $offenders")
    }

    /**
     * Prints each migration's checksum as Kandra computes it, the value `kandra_migrations` stores. `checksum()` is
     * internal to kandra-migrate, so it is called through its compiled name.
     */
    @Test
    fun `print the checksums kandra stores`() {
        val checksum = KandraMigration::class.java.getMethod("checksum\$kandra_migrate")
        for (spec in specs) {
            for (migration in spec.migrations) {
                println("checksum ${spec.keyspace} v${migration.version}: ${checksum.invoke(migration)}")
            }
        }
    }

    /** The UTF-8 constants of [migration]'s class that start with `SMAP`. */
    private fun smapConstants(migration: KandraMigration): List<String> {
        val type = migration::class.java
        val bytes =
            type.getResourceAsStream("${type.simpleName}.class")?.readBytes()
                ?: fail("no class file for ${type.name}")
        return utf8Constants(DataInputStream(bytes.inputStream())).filter { it.startsWith("SMAP") }
    }

    /**
     * Walks a class file's constant pool (JVMS 4.4) and returns its UTF-8 entries. By hand because the ASM on the
     * classpath (9.2, through the Cassandra driver) refuses Java 21 class files.
     */
    private fun utf8Constants(input: DataInputStream): List<String> {
        input.skipBytes(8) // magic, minor and major version
        val count = input.readUnsignedShort()
        val constants = mutableListOf<String>()
        var index = 1
        while (index < count) {
            val tag = input.readUnsignedByte()
            if (tag == UTF8) {
                constants += input.readUTF()
            } else {
                input.skipBytes(entrySize(tag))
            }
            index += if (tag == LONG || tag == DOUBLE) 2 else 1
        }
        return constants
    }

    private fun entrySize(tag: Int): Int =
        when (tag) {
            CLASS, STRING, METHOD_TYPE, MODULE, PACKAGE -> 2
            METHOD_HANDLE -> 3
            INTEGER, FLOAT, FIELD_REF, METHOD_REF, INTERFACE_METHOD_REF, NAME_AND_TYPE, DYNAMIC, INVOKE_DYNAMIC -> 4
            LONG, DOUBLE -> 8
            else -> fail("unknown constant pool tag $tag")
        }

    private companion object {
        const val UTF8 = 1
        const val INTEGER = 3
        const val FLOAT = 4
        const val LONG = 5
        const val DOUBLE = 6
        const val CLASS = 7
        const val STRING = 8
        const val FIELD_REF = 9
        const val METHOD_REF = 10
        const val INTERFACE_METHOD_REF = 11
        const val NAME_AND_TYPE = 12
        const val METHOD_HANDLE = 15
        const val METHOD_TYPE = 16
        const val DYNAMIC = 17
        const val INVOKE_DYNAMIC = 18
        const val MODULE = 19
        const val PACKAGE = 20
    }
}
