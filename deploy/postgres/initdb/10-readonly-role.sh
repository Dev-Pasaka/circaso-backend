#!/usr/bin/env bash
# First start only (empty volume): the role pgAdmin browses with. It can read every table in every schema, including tables
# created later (pg_read_all_data), and its sessions are read-only by default, so browsing never changes data by accident.
set -euo pipefail
psql -v ON_ERROR_STOP=1 -v pw="$CIRCASO_READONLY_PASSWORD" --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE ROLE circaso_readonly LOGIN PASSWORD :'pw';
GRANT pg_read_all_data TO circaso_readonly;
ALTER ROLE circaso_readonly SET default_transaction_read_only = on;
ALTER ROLE circaso_readonly SET statement_timeout = '30s';
ALTER ROLE circaso_readonly CONNECTION LIMIT 20;  -- pgAdmin opens one connection per database and query tab
SQL
