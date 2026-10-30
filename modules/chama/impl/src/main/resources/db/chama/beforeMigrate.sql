-- The drafting convention (circaso.impl → generateMigrations): creates the schema only where it is missing, so the
-- scripts apply in the plugin's empty container; under Migrate the schema exists and this does nothing.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_namespace WHERE nspname = 'chama') THEN
        CREATE SCHEMA chama;
    END IF;
END
$$;
