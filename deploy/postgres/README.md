# PostgreSQL on the dev server

Reference for `/opt/circaso/postgres` (a copy of this folder). The design is in architecture.md → Infrastructure →
Exposed and PostgreSQL; the compose services are in `deploy/docker-compose.dev.yml`.

| File | What it does |
| --- | --- |
| `initdb/10-readonly-role.sh` | First start only: `circaso_readonly`, the role pgAdmin browses with |
| `initdb/20-login-roles.sh` | First start, and safe to run again: `circaso_migrator`, `circaso_app`, `circaso_jobs` with their passwords and limits |
| `pgadmin-servers.json` | pgAdmin's saved server, as `circaso_readonly` |
| `backup.sh` | The nightly `pg_dump -Fc` to R2, kept 7 days |
| `backup.env.example` | Template for `/opt/circaso/backup.env`, the backup's R2 token |

Module schemas and module roles are not made here: the deploy's `Migrate` step creates them as `circaso_migrator`.

## Adding the login roles to the running sandbox database

`initdb` only runs on an empty volume, and the sandbox's volume already exists. Once, after adding the three
`CIRCASO_*_PASSWORD` lines to `/opt/circaso/postgres.env` (see `deploy/postgres.env.example`):

```sh
cd /opt/circaso
docker compose up -d postgres                     # picks up the new postgres.env values
docker compose exec -T postgres bash /docker-entrypoint-initdb.d/20-login-roles.sh
```

The script reads the passwords from the container's environment, creates the roles that are missing and resets the
passwords and limits of those that exist, so it is also how a password is rotated.

## Nightly backup

`backup.sh` dumps the database inside the `postgres` container and uploads it to its own R2 bucket
(`R2_BACKUP_BUCKET`, default `circaso-backups-dev`) as `backups/postgres/circaso-<Mon…Sun>.dump`: seven files, each
overwritten a week later. It uses one connection, for a few seconds a night. Create the bucket and a token with
"Object Read & Write" on it only (R2 > Manage API tokens), then install it once, as the deploy user:

```sh
install -m 600 /dev/null /opt/circaso/backup.env  # then fill it in from backup.env.example
echo '30 2 * * * deploy /opt/circaso/postgres/backup.sh >>/var/log/circaso-backup.log 2>&1' | sudo tee /etc/cron.d/circaso-backup
sudo touch /var/log/circaso-backup.log && sudo chown deploy /var/log/circaso-backup.log
/opt/circaso/postgres/backup.sh                   # once by hand, to see it work
```

## Restoring a backup

```sh
set -a; . /opt/circaso/backup.env; set +a      # the keys stay out of the command line
export AWS_ACCESS_KEY_ID="$R2_BACKUP_ACCESS_KEY_ID" AWS_SECRET_ACCESS_KEY="$R2_BACKUP_SECRET_ACCESS_KEY"
docker run --rm -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_DEFAULT_REGION=auto -v /tmp:/backup \
  amazon/aws-cli:2.38.0 s3 cp s3://circaso-backups-dev/backups/postgres/circaso-Tue.dump /backup/ \
  --endpoint-url https://<account>.r2.cloudflarestorage.com
docker compose exec -T postgres sh -c 'pg_restore --clean --if-exists --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
  </tmp/circaso-Tue.dump
```

A restore is tested once in PG6.
