#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${INNOLIVE_WEB_DEPLOY_ENV_FILE:-}" ]]; then
  # This file is managed on the deployment host and must not be committed.
  # shellcheck source=/dev/null
  source "$INNOLIVE_WEB_DEPLOY_ENV_FILE"
fi

: "${INNOLIVE_PROJECT_DIR:?INNOLIVE_PROJECT_DIR must be set on the deployment host}"
: "${INNOLIVE_CADDY_FILE:?INNOLIVE_CADDY_FILE must be set on the deployment host}"
: "${INNOLIVE_CADDY_CONTAINER:?INNOLIVE_CADDY_CONTAINER must be set on the deployment host}"

project_dir="$INNOLIVE_PROJECT_DIR"
web_dir="$project_dir/apps/web"
caddy_file="$INNOLIVE_CADDY_FILE"
site_host="${INNOLIVE_SITE_HOST:-innolive.chaeyn.com}"
compose_project_name="${INNOLIVE_COMPOSE_PROJECT_NAME:-innolive-web}"

if [[ ! -s "$web_dir/.env" ]]; then
  db_password=$(openssl rand -hex 32)
  umask 077
  cat > "$web_dir/.env" <<ENV_FILE
POSTGRES_DB=innolive
POSTGRES_USER=innolive
POSTGRES_PASSWORD=$db_password
DATABASE_URL=postgresql://innolive:$db_password@db:5432/innolive
NEXT_PUBLIC_PRIVACY_CONTACT_EMAIL=chaeyn@dgsw.hs.kr
ENV_FILE
fi

if ! grep -q '^NEXT_PUBLIC_INNOLIVE_SIGNALING_URL=wss://' "$web_dir/.env"; then
  echo "NEXT_PUBLIC_INNOLIVE_SIGNALING_URL must be set to the public signaling wss:// URL in $web_dir/.env" >&2
  exit 1
fi

cd "$web_dir"
docker compose --project-name "$compose_project_name" -f docker-compose.yml -f docker-compose.server.yml up -d --build --remove-orphans

if ! grep -Fq "$site_host" "$caddy_file"; then
  cat >> "$caddy_file" <<CADDY_BLOCK

$site_host {
  reverse_proxy 127.0.0.1:3010
}
CADDY_BLOCK
fi

docker exec "$INNOLIVE_CADDY_CONTAINER" caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile

for _ in {1..30}; do
  if curl --fail --silent --show-error http://127.0.0.1:3010/ >/dev/null; then
    echo "InnoLive web deployment completed."
    exit 0
  fi
  sleep 2
done

echo "InnoLive web did not become healthy." >&2
exit 1
