#!/usr/bin/env bash
set -euo pipefail

project_dir=/srv/innolive-client
web_dir="$project_dir/apps/web"
caddy_file=/etc/caddy/Caddyfile
site_host=innolive.chaeyn.com

if [[ ! -s "$web_dir/.env" ]]; then
  db_password=$(openssl rand -hex 32)
  umask 077
  cat > "$web_dir/.env" <<ENV_FILE
POSTGRES_DB=innolive
POSTGRES_USER=innolive
POSTGRES_PASSWORD=$db_password
DATABASE_URL=postgresql://innolive:$db_password@db:5432/innolive
NEXT_PUBLIC_PRIVACY_CONTACT_EMAIL=chaulfe@gmail.com
ENV_FILE
fi

cd "$web_dir"
docker compose -f docker-compose.yml -f docker-compose.server.yml up -d --build --remove-orphans

if ! grep -Fq "$site_host" "$caddy_file"; then
  cat >> "$caddy_file" <<CADDY_BLOCK

$site_host {
  reverse_proxy 127.0.0.1:3010
}
CADDY_BLOCK
fi

docker exec caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile

for _ in {1..30}; do
  if curl --fail --silent --show-error http://127.0.0.1:3010/ >/dev/null; then
    echo "InnoLive web deployment completed."
    exit 0
  fi
  sleep 2
done

echo "InnoLive web did not become healthy." >&2
exit 1
