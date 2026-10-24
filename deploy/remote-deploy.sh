#!/usr/bin/env bash
# Runs ON the server, started by the deploy workflow:  IMAGE=user/circaso-backend:sha REGISTRY_USER=... bash remote-deploy.sh   (the Docker Hub token arrives on stdin)
# Pulls the image, runs the database migrations once, starts the new version, waits until it reports ready, and puts the previous
# version back if it does not. After a good deploy it removes the old images. Settings are in /opt/circaso/.env, which this script never writes or prints.
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

# The proxy and the app share this network, created once here because Compose treats it as external; the data network
# (app and Redis only, no internet) is created by Compose itself.
docker network inspect circaso-backend >/dev/null 2>&1 || docker network create circaso-backend >/dev/null

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

# Frees disk after a good deploy: every older image of this app goes (they are tagged by commit, so a plain prune keeps
# them all), except the one just started and the one before it, which a rollback would want. Untagged leftovers of
# nginx and valkey go too. Volumes are never touched: valkey keeps its data there.
clean_images() {
  repo="${IMAGE%:*}"
  docker image ls "$repo" --format '{{.Repository}}:{{.Tag}}' \
    | grep -v -x -F -e "$IMAGE" -e "${previous:-none}" \
    | xargs -r docker image rm >/dev/null 2>&1 || true
  docker image prune -f >/dev/null 2>&1 || true
  docker network rm circaso_internal >/dev/null 2>&1 || true   # the single network of the first version, now unused
  echo "images kept: $(docker image ls "$repo" --format '{{.Tag}}' | tr '\n' ' ')"
  df -h / | awk 'NR==2 {print "disk used: " $5 " of " $2}'
}

if ready; then
  echo "$IMAGE" > .current
  echo "deployed $IMAGE"
  clean_images
else
  back_out "the new version did not become ready; last logs:"
fi
