# Kandra bugs

Problems found in Kandra while building Circaso, with how to reproduce each and what we did about it. Kandra version: `com.kandraorm.kandra` **0.5.1**. Each entry says whether it was reproduced here or only read from the source. Each is filed on https://github.com/Dev-Pasaka/kandra/issues (links below). Fix them upstream and delete the workaround noted with each.

## 1. `@LookupIndex` on a clustering key generates invalid DDL

**Issue:** https://github.com/Dev-Pasaka/kandra/issues/187

**Status:** reproduced (the generated DDL, printed with `DdlGenerator.allStatements`).

**Entity**

```kotlin
@ScyllaTable(name = "support_recoveries", keyspace = "circaso_identity")
data class SupportRecoveryRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC)
    @LookupIndex(tableSuffix = "by_request") val requestId: UUID,
    ...
)
```

**Generated** for the lookup table:

```sql
CREATE TABLE IF NOT EXISTS circaso_identity.support_recoveries_by_request (
    request_id UUID,
    user_id UUID,
    request_id UUID,      -- the indexed column appears twice
    PRIMARY KEY (request_id)
);
```

The indexed column is also part of the primary key, so the lookup table repeats it. The statement is rejected by the database (duplicate column name).

**Expected:** the lookup table holds the indexed column once, plus the primary key columns that are not it (`user_id`), or Kandra refuses the annotation at startup with a clear message.

**Workaround:** no `@LookupIndex`; a table of our own, `support_recoveries_by_request(request_id PRIMARY KEY, user_id)`, written in the same batch (`RecoveryByRequestRow`). The architecture doc suggested the lookup index here, so that part of the spec could not be followed as written.

## 2. Identifiers are not quoted in generated DDL, so a CQL reserved word as a column name breaks the schema

**Issue:** https://github.com/Dev-Pasaka/kandra/issues/188

**Status:** reproduced.

A column named `token` (`InviteRow.token`, the primary key of `invites`) is emitted as

```sql
CREATE TABLE IF NOT EXISTS circaso_users.invites (token TEXT, ...)
```

and fails with `no viable alternative at input 'token'`, because `token` is a built-in function name in CQL. Any reserved or function-like word used as a property name has the same problem.

**Expected:** Kandra quotes identifiers that need it (`"token"`) in DDL and in the statements it builds, or rejects a reserved name when the entity is registered.

**Workaround:** `@PartitionKey @Column(name = "invite_token") val token`. Worth checking the other words on the reserved list for the same treatment.

## 3. A null cannot be written through `save`, `saveInBatch` or `update`, so a column cannot be cleared in a batch or a versioned update

**Issue:** https://github.com/Dev-Pasaka/kandra/issues/189

**Status:** reproduced for `save` (documented behaviour, but it makes clearing a value easy to get wrong); not reproduced for `update(old, new)`, where the source suggests the same.

A nullable property set to `null` is bound as "unset", not as a null, so the database keeps the old value:

```kotlin
claims.save(existing.copy(releasedAt = null))   // releasedAt keeps its old value
claims.saveWithNulls(existing.copy(releasedAt = null))   // clears it
```

`saveWithNulls` exists for `save`, but there is no equivalent for:

- `saveInBatch` / `KandraBatchScope`, so a batch (the way several tables are changed together) cannot clear a column;
- `update(old, new)` / `updateAndGet`, so a version-checked update cannot clear a column.

**Expected:** a null-writing variant of both, or an option on the entity or the call.

**Workaround:** the columns that must be clearable store a sentinel instead (an empty text, the nil UUID or the epoch) and the row mapper turns it back into `null` (`AccountRow.phone` and `activeDeviceId`, `ProfileRow.about` and `avatarKey`, `ContactRow.removedAt`). `HandleRepositoryImpl.claim` uses `saveWithNulls` for `releasedAt`.

## 4. `kandra-test` does not bring the Cassandra Testcontainers module it needs

**Issue:** https://github.com/Dev-Pasaka/kandra/issues/190

**Status:** reproduced.

`KandraTestcontainers.freshKeyspace(...)` fails with

```
java.lang.NoClassDefFoundError: org/testcontainers/containers/CassandraContainer
```

unless `org.testcontainers:cassandra` is added to the test classpath by hand. `kandra-test` uses the class but does not declare the dependency (the skill notes also say JUnit is not brought, which is expected; the Cassandra module is not).

**Expected:** `kandra-test` declares `org.testcontainers:cassandra` (as `api`, since its public types expose the container).

**Workaround:** `testImplementation(libs.testcontainers.cassandra)` (version 1.21.4, matching the other Testcontainers modules) in every module that uses it.

## 5. Migration checksum changes when the migration moves to other lines

**Issue:** not filed yet.

**Status:** reproduced (compiled `75a8a9d` and the next commit, same source except layout, and compared the class files).

A migration whose `up()` uses an inline function (`listOf(...).forEach { session.execute(it) }`) gets Kotlin's source-line table (the SMAP) as a text entry in the class's constant pool. `BytecodeNormalizer` removes the `SourceDebugExtension` and `LineNumberTable` attributes, but not that constant, so the checksum depends on which lines the code is on. Reformatting the file, or adding a KDoc line above the migration, makes `KandraMigrationRunner` fail on an already-applied migration:

```
KandraMigrationException: Migration v1 ('create users tables') checksum mismatch — the migration was modified after being applied.
```

The SQL and the instructions were identical; only `line 25: 0` became `line 34: 0` in the javap output.

**What we do:** migrations avoid inline functions (a plain `for` loop), so their classes carry no line table. A checksum that already changed is updated once in `kandra_migrations`, as Kandra's own recovery note says, after confirming with a bytecode diff that nothing but line numbers moved.

## Not bugs, but surprises worth a line in the docs

- `SchemaRegistry` and `DdlGenerator` are `@InternalKandraApi`, so a migration cannot reuse the entity DDL without an opt-in. We wrote the DDL by hand, from what Kandra generated, in each module's `V001` migration.
- `KandraTestcontainers` starts **Cassandra**, not ScyllaDB, so data-layer tests run against Cassandra-compatible CQL.
