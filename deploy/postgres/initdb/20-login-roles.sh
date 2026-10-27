#!/usr/bin/env bash
# The three login roles the app uses (architecture.md → Exposed and PostgreSQL). Runs on the first start with an empty volume,
# and is safe to run again: it creates what is missing and resets each password and limit to what postgres.env says. On a
# database that already exists, run it once by hand (deploy/postgres/README.md).
#
#   circaso_migrator  the Migrate step. CREATEROLE and CREATE on the database: it creates each module's schema (so it owns
#                     them all) and role, and runs Flyway as that role.
#   circaso_app       the app pool. Granted every module role by Migrate; each transaction runs SET LOCAL ROLE <module>.
#                     5 s per statement, 2 s waiting for a lock, 10 s idle inside a transaction.
#   circaso_jobs      the jobs pool (outbox relay, event handlers, purges and partitions). Same grants; 60 s per statement.
#
# The limits sit on the login roles, so they hold whichever module role a transaction switches to.
#
# The passwords reach psql on its standard input, as \set lines ahead of the SQL, never as arguments, which `ps` would show.
# Use plain random strings (openssl rand -hex 24): a single quote is doubled, but a backslash would be read as an escape.
set -euo pipefail

: "${CIRCASO_MIGRATOR_PASSWORD:?set it in postgres.env}"
: "${CIRCASO_APP_PASSWORD:?set it in postgres.env}"
: "${CIRCASO_JOBS_PASSWORD:?set it in postgres.env}"

# A psql \set line holding $2 as a quoted literal. printf is a shell builtin, so the value never becomes a process argument.
set_variable() {
  local quoted=${2//\'/\'\'}
  printf "\\\\set %s '%s'\n" "$1" "$quoted"
}

{
  set_variable migrator_pw "$CIRCASO_MIGRATOR_PASSWORD"
  set_variable app_pw "$CIRCASO_APP_PASSWORD"
  set_variable jobs_pw "$CIRCASO_JOBS_PASSWORD"
  set_variable db "$POSTGRES_DB"
  cat <<'SQL'
SELECT 'CREATE ROLE circaso_migrator LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'circaso_migrator')\gexec
SELECT 'CREATE ROLE circaso_app LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'circaso_app')\gexec
SELECT 'CREATE ROLE circaso_jobs LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'circaso_jobs')\gexec

ALTER ROLE circaso_migrator WITH LOGIN CREATEROLE PASSWORD :'migrator_pw';
ALTER ROLE circaso_app WITH LOGIN NOCREATEROLE PASSWORD :'app_pw';
ALTER ROLE circaso_jobs WITH LOGIN NOCREATEROLE PASSWORD :'jobs_pw';

GRANT CREATE ON DATABASE :"db" TO circaso_migrator;

ALTER ROLE circaso_app SET statement_timeout = '5s';
ALTER ROLE circaso_app SET lock_timeout = '2s';
ALTER ROLE circaso_app SET idle_in_transaction_session_timeout = '10s';
ALTER ROLE circaso_jobs SET statement_timeout = '60s';
SQL
} | psql -v ON_ERROR_STOP=1 --no-psqlrc --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"
