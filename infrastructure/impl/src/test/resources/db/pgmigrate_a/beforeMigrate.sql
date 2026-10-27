-- The drafting convention (circaso.impl → generateMigrations): creates the schema only where it is missing, so the
-- scripts apply in the plugin's empty container; under Migrate the schema exists and this does nothing.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_namespace WHERE nspname = 'pgmigrate_a') THEN
        CREATE SCHEMA pgmigrate_a;
    END IF;
END
$$;
