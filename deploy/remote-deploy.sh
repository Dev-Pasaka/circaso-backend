#!/usr/bin/env bash
# Runs ON the server, started by the deploy workflow:  IMAGE=user/circaso-backend:sha REGISTRY_USER=... bash remote-deploy.sh   (the Docker Hub token arrives on stdin)
# Pulls the image, runs the database migrations once, starts the new version, waits until it reports ready, and puts the previous
# version back if it does not. Settings are in /opt/circaso/.env, which this script never writes or prints.
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/circaso}"
: "${IMAGE:?IMAGE is required}"
: "${REGISTRY_USER:?REGISTRY_USER is required}"
cd "$APP_DIR"
[ -f .env ] || { echo "missing $APP_DIR/.env (see deploy/dev.env.example)"; exit 1; }
chmod 600 .env

# The token comes in on stdin, never on a command line or in the environment.
docker login -u "$REGISTRY_USER" --password-stdin >/dev/null
trap 'docker logout >/dev/null 2>&1 || true' EXIT

previous="$(cat .current 2>/dev/null || true)"
export IMAGE
export APP_VERSION="${IMAGE##*:}"   # the commit, shown in the banner and logs
compose() { docker compose -f docker-compose.dev.yml "$@"; }

echo "pulling $IMAGE"
docker pull --quiet "$IMAGE" >/dev/null

echo "migrating"
docker run --rm --env-file .env -e LOG_FORMAT=JSON -e LOG_COLOR=false --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges:true \
  --entrypoint java "$IMAGE" --enable-native-access=ALL-UNNAMED -cp "/app/lib/*" com.circaso.app.Migrate

back_out() {
  echo "$1"
  compose logs --tail 60 app valkey 2>&1 || true
  if [ -n "$previous" ]; then
    echo "putting back $previous"
    IMAGE="$previous" compose up -d --remove-orphans || true
  fi
  exit 1
}

echo "starting"
compose up -d --remove-orphans || back_out "the new version could not be started; last logs:"

# nginx does not notice that its mounted files changed, so check the config and reload it. A config that does not pass leaves the old one running.
compose exec -T nginx nginx -t >/dev/null 2>&1 && compose exec -T nginx nginx -s reload >/dev/null 2>&1 || echo "warning: nginx kept its previous config (it did not pass nginx -t)"

ready() {
  for _ in $(seq 1 40); do
    if compose exec -T app curl -fsS http://localhost:8080/health/ready >/dev/null 2>&1; then return 0; fi
    sleep 3
  done
  return 1
}

if ready; then
  echo "$IMAGE" > .current
  echo "deployed $IMAGE"
  docker image prune -f --filter "until=168h" >/dev/null
else
  back_out "the new version did not become ready; last logs:"
fi
