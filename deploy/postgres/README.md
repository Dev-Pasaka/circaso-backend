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
```

Test a restore on a throwaway `postgres:18` container, never with `pg_restore --clean` against the live database
(last done 2026-10-28). Download the night's dump from the backup bucket to `/tmp` first. Run the steps as a script
file, not a heredoc piped into ssh: `docker exec -i` and `docker compose run` read standard input.

```sh
cd /opt/circaso
docker run -d --name circaso-restore-test --env-file postgres.env \
  -v "$PWD/postgres/initdb:/docker-entrypoint-initdb.d:ro" postgres:18
docker network connect "$(docker network ls --format '{{.Name}}' | grep circaso-data)" circaso-restore-test
# the login roles come from initdb on first start; the module roles own the restored tables, so make them first, granted
# to circaso_migrator with ADMIN OPTION as Migrate itself would have made them
for role in identity users messaging wallet assistant; do
  docker exec circaso-restore-test psql -U postgres -d circaso \
    -c "CREATE ROLE $role NOLOGIN; GRANT $role TO circaso_migrator WITH ADMIN OPTION"
done
docker exec -i circaso-restore-test pg_restore --exit-on-error -U postgres -d circaso </tmp/circaso-Tue.dump
# Migrate's history check: against an up-to-date copy it validates every module's history and applies nothing
docker compose run --rm --no-deps -e DATABASE_MIGRATION_URL=jdbc:postgresql://circaso-restore-test:5432/circaso \
  --entrypoint java app -cp "/app/lib/*" com.circaso.app.Migrate
docker rm -f circaso-restore-test && rm /tmp/circaso-Tue.dump
```

`Migrate` must end with `applied=0` and no history problem; anything else means the backup can't be trusted.
