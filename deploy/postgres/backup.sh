#!/usr/bin/env bash
# Nightly backup of the sandbox database to Cloudflare R2 (architecture.md → Where Postgres runs → Backups).
#
# Runs on the server's host cron as the deploy user (deploy/postgres/README.md has the cron line):
#   1. pg_dump -Fc inside the postgres container, as the superuser over its local socket (one connection, no new port);
#   2. uploads the dump to R2 with the AWS CLI image, under backups/postgres/circaso-<weekday>.dump.
# Seven weekday names make seven files that overwrite themselves a week later: 7 days are kept with no listing, no
# lifecycle rule and no delete call. A night that fails leaves last week's file for that day in place.
#
# R2 credentials come from /opt/circaso/backup.env (mode 600; template deploy/postgres/backup.env.example), a token
# scoped to the bucket, separate from the app's keys. Nothing here prints a secret or the dump.
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/circaso}"
ENV_FILE="${ENV_FILE:-$APP_DIR/backup.env}"
AWS_CLI_IMAGE="amazon/aws-cli:2.38.0"

[ -r "$ENV_FILE" ] || { echo "backup: $ENV_FILE is missing" >&2; exit 1; }
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a
: "${R2_ACCOUNT_ID:?}" "${R2_BUCKET:?}" "${R2_BACKUP_ACCESS_KEY_ID:?}" "${R2_BACKUP_SECRET_ACCESS_KEY:?}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
dump="circaso-$(date -u +%a).dump"

docker compose -f "$APP_DIR/docker-compose.yml" exec -T postgres \
  sh -c 'pg_dump --format=custom --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' >"$work/$dump"
[ -s "$work/$dump" ] || { echo "backup: pg_dump wrote nothing" >&2; exit 1; }

docker run --rm --network bridge \
  -e AWS_ACCESS_KEY_ID="$R2_BACKUP_ACCESS_KEY_ID" \
  -e AWS_SECRET_ACCESS_KEY="$R2_BACKUP_SECRET_ACCESS_KEY" \
  -e AWS_DEFAULT_REGION=auto \
  -v "$work:/backup:ro" \
  "$AWS_CLI_IMAGE" s3 cp "/backup/$dump" "s3://$R2_BUCKET/backups/postgres/$dump" \
  --endpoint-url "https://$R2_ACCOUNT_ID.r2.cloudflarestorage.com" --only-show-errors

echo "backup: $dump uploaded ($(du -h "$work/$dump" | cut -f1))"
