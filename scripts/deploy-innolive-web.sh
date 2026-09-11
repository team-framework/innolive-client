#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

readonly DEPLOY_CONFIG_FILE='/etc/innolive/web-deploy.env'
readonly MAX_ARCHIVE_BYTES=$((256 * 1024 * 1024))
readonly MAX_TAR_BYTES=$((512 * 1024 * 1024))
readonly MAX_MEMBER_COUNT=10000
readonly MAX_FILE_BYTES=$((256 * 1024 * 1024))

private_log='/dev/null'
staging_dir=''
release_dir=''
release_source=''
release_dir_created=0
release_validated=0
rollback_override=''
rollback_image=''
old_image_id=''
replacement_attempted=0
expected_sha=''

fail() {
  printf 'deployment failed: %s\n' "$1" >&2
  exit 1
}

stage() {
  printf 'stage: %s %s\n' "$1" "$expected_sha"
}

hash_file() {
  local output

  if ! output=$(sha256sum "$1" 2>>"$private_log"); then
    return 1
  fi
  output=${output%% *}
  [[ "$output" =~ ^[0-9a-f]{64}$ ]] || return 1
  printf '%s' "$output"
}

container_id() {
  local output

  if ! output=$(docker inspect --format '{{.Id}}' "$1" 2>>"$private_log"); then
    return 1
  fi
  output=${output//$'\n'/}
  [[ "$output" =~ ^[0-9a-f]{64}$ ]] || return 1
  printf '%s' "$output"
}

container_image_id() {
  local output

  if ! output=$(docker inspect --format '{{.Image}}' "$1" 2>>"$private_log"); then
    return 1
  fi
  output=${output//$'\n'/}
  [[ "$output" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
  printf '%s' "$output"
}

image_id() {
  local output

  if ! output=$(docker image inspect --format '{{.Id}}' "$1" 2>>"$private_log"); then
    return 1
  fi
  output=${output//$'\n'/}
  [[ "$output" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
  printf '%s' "$output"
}

web_container_id() {
  local output line count=0 selected=''

  if ! output=$("${compose_base[@]}" ps -q web 2>>"$private_log"); then
    return 1
  fi

  while IFS= read -r line; do
    if [[ -n "$line" ]]; then
      count=$((count + 1))
      selected=$line
    fi
  done <<<"$output"

  [[ "$count" -eq 1 && "$selected" =~ ^[0-9a-f]{12,64}$ ]] || return 1
  printf '%s' "$selected"
}

write_release_override() {
  local override_file=$1
  local image=$2

  if ! {
    printf '%s\n' 'services:'
    printf '%s\n' '  web:'
    printf '    build:\n'
    printf '      context: %s\n' "$release_source"
    printf '      args:\n'
    printf '        INNOLIVE_WEB_REVISION: "%s"\n' "$expected_sha"
    printf '    image: %s\n' "$image"
  } >"$override_file"; then
    return 1
  fi
  chmod 0600 "$override_file" 2>>"$private_log"
}

write_rollback_override() {
  rollback_override="$release_dir/docker-compose.rollback.yml"

  if ! {
    printf '%s\n' 'services:'
    printf '%s\n' '  web:'
    printf '    image: %s\n' "$rollback_image"
  } >"$rollback_override"; then
    return 1
  fi
  chmod 0600 "$rollback_override" 2>>"$private_log"
}

rollback_web() {
  local restored_container restored_image

  [[ -n "$rollback_image" && -n "$old_image_id" && -n "$release_dir" ]] || return 1
  [[ -f "$rollback_override" ]] || write_rollback_override || return 1

  if ! "${compose_base[@]}" -f "$rollback_override" up -d --no-deps --no-build web >>"$private_log" 2>&1; then
    return 1
  fi

  if ! restored_container=$(web_container_id); then
    return 1
  fi
  if ! restored_image=$(container_image_id "$restored_container"); then
    return 1
  fi
  [[ "$restored_image" == "$old_image_id" ]] || return 1
  wait_for_local_root
}

verify_json_revision() {
  if ! python3 - "$1" "$expected_sha" >>"$private_log" 2>&1 <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as response:
    payload = json.load(response)

if payload.get("revision") != sys.argv[2]:
    raise SystemExit(1)
PY
  then
    return 1
  fi
}

fetch_and_verify_endpoint() {
  local url=$1
  local response_file=$2
  local headers_file="$response_file.headers"
  local deadline=$((SECONDS + 90))

  while (( SECONDS < deadline )); do
    if curl \
      --fail \
      --silent \
      --show-error \
      --location \
      --connect-timeout 5 \
      --max-time 15 \
      --header 'Cache-Control: no-cache, no-store' \
      --header 'Pragma: no-cache' \
      --dump-header "$headers_file" \
      --output "$response_file" \
      "$url" >>"$private_log" 2>&1 &&
      grep -Eiq '^cache-control:.*no-store' "$headers_file" &&
      verify_json_revision "$response_file"; then
      return 0
    fi
    sleep 2
  done

  return 1
}

wait_for_local_root() {
  local deadline=$((SECONDS + 90))

  while (( SECONDS < deadline )); do
    if curl \
      --fail \
      --silent \
      --show-error \
      --location \
      --connect-timeout 2 \
      --max-time 3 \
      --output /dev/null \
      'http://127.0.0.1:3010/' >>"$private_log" 2>&1; then
      return 0
    fi
    sleep 1
  done

  return 1
}

cleanup() {
  local status=$?
  trap - EXIT

  if (( status != 0 && replacement_attempted == 1 )); then
    stage 'rollback-started'
    if rollback_web; then
      stage 'rollback-completed'
    else
      printf 'deployment rollback failed\n' >&2
    fi
  fi

  if (( release_dir_created == 1 && release_validated == 0 )) && [[ -n "$release_dir" && -d "$release_dir" ]]; then
    rm -rf -- "$release_dir" >/dev/null 2>>"$private_log" || true
  fi

  if [[ -n "$staging_dir" && -d "$staging_dir" ]]; then
    rm -rf -- "$staging_dir" >/dev/null 2>>"$private_log" || true
  fi

  exit "$status"
}

trap cleanup EXIT

[[ "$#" -eq 1 ]] || fail 'one deployment revision is required'
expected_sha=$1
[[ "$expected_sha" =~ ^[0-9a-f]{40}$ ]] || fail 'deployment revision must be 40 lowercase hexadecimal characters'
[[ "$(id -u)" -eq 0 ]] || fail 'deployment script must run as root'

[[ -f "$DEPLOY_CONFIG_FILE" && ! -L "$DEPLOY_CONFIG_FILE" ]] || fail 'deployment configuration is unavailable'
# shellcheck source=/dev/null
if ! source "$DEPLOY_CONFIG_FILE" >/dev/null 2>&1; then
  fail 'deployment configuration could not be loaded'
fi

required_variables=(
  INNOLIVE_WEB_DIR
  INNOLIVE_WEB_RELEASES_DIR
  INNOLIVE_WEB_SITE_URL
  INNOLIVE_WEB_COMPOSE_PROJECT
  INNOLIVE_WEB_DB_CONTAINER
  INNOLIVE_WEB_PROXY_CONTAINER
)
for variable in "${required_variables[@]}"; do
  [[ -n "${!variable:-}" ]] || fail 'deployment configuration is incomplete'
done

for path_value in "$INNOLIVE_WEB_DIR" "$INNOLIVE_WEB_RELEASES_DIR"; do
  [[ "$path_value" =~ ^/[A-Za-z0-9._/-]+$ ]] || fail 'deployment paths must be absolute and simple'
  [[ "$path_value" != *'//' && "$path_value" != *'/../'* && "$path_value" != */.. ]] || fail 'deployment paths are unsafe'
done
[[ "$INNOLIVE_WEB_DIR" != "$INNOLIVE_WEB_RELEASES_DIR" ]] || fail 'source and release directories must differ'
[[ "$INNOLIVE_WEB_COMPOSE_PROJECT" =~ ^[a-z0-9][a-z0-9_.-]*$ ]] || fail 'compose project name is invalid'
[[ "$INNOLIVE_WEB_DB_CONTAINER" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || fail 'database container name is invalid'
[[ "$INNOLIVE_WEB_PROXY_CONTAINER" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || fail 'proxy container name is invalid'
[[ "$INNOLIVE_WEB_SITE_URL" =~ ^https?://[^/?#[:space:]]+(/[^?#[:space:]]*)?$ ]] || fail 'public site URL is invalid'

web_dir=$INNOLIVE_WEB_DIR
releases_dir=$INNOLIVE_WEB_RELEASES_DIR
env_file="$web_dir/.env"
compose_main="$web_dir/docker-compose.yml"
compose_server="$web_dir/docker-compose.server.yml"
compose_gpu="$web_dir/docker-compose.gpu.yml"

[[ -d "$web_dir" && ! -L "$web_dir" ]] || fail 'web source directory is unavailable'
[[ -d "$releases_dir" && ! -L "$releases_dir" && -w "$releases_dir" ]] || fail 'release directory is unavailable'
for required_file in "$env_file" "$compose_main" "$compose_server" "$compose_gpu"; do
  [[ -s "$required_file" && ! -L "$required_file" ]] || fail 'production runtime configuration is incomplete'
done

lock_file="$releases_dir/.deploy.lock"
if ! exec 9>"$lock_file"; then
  fail 'deployment lock could not be opened'
fi
if ! flock -x 9; then
  fail 'deployment lock could not be acquired'
fi
stage 'lock-acquired'

if ! staging_dir=$(mktemp -d "$releases_dir/.staging.XXXXXX" 2>/dev/null); then
  fail 'private staging directory could not be created'
fi
chmod 0700 "$staging_dir" 2>/dev/null || fail 'private staging directory could not be protected'
private_log="$staging_dir/deployment.log"
: >"$private_log"
chmod 0600 "$private_log" 2>/dev/null || fail 'private deployment log could not be protected'

archive_file="$staging_dir/payload.tar.gz"
tar_file="$staging_dir/payload.tar"
exec 3<&0
if ! python3 - "$archive_file" "$tar_file" "$MAX_ARCHIVE_BYTES" "$MAX_TAR_BYTES" 3<&3 >>"$private_log" 2>&1 <<'PY'
import gzip
import os
import sys

archive_path, tar_path = sys.argv[1:3]
max_archive_bytes, max_tar_bytes = (int(value) for value in sys.argv[3:5])

received = 0
with os.fdopen(3, "rb", closefd=False) as source, open(archive_path, "xb") as destination:
    while True:
        chunk = source.read(1024 * 1024)
        if not chunk:
            break
        received += len(chunk)
        if received > max_archive_bytes:
            raise ValueError("compressed archive exceeds the size limit")
        destination.write(chunk)

if os.path.getsize(archive_path) != received:
    raise ValueError("compressed archive size could not be checked")

expanded = 0
with gzip.open(archive_path, "rb") as source, open(tar_path, "xb") as destination:
    while True:
        chunk = source.read(1024 * 1024)
        if not chunk:
            break
        expanded += len(chunk)
        if expanded > max_tar_bytes:
            raise ValueError("tar archive exceeds the size limit")
        destination.write(chunk)
PY
then
  exec 3<&-
  fail 'deployment archive could not be received or decompressed'
fi
exec 3<&-
stage 'archive-received'

if ! release_dir=$(mktemp -d "$releases_dir/${expected_sha}.XXXXXX" 2>>"$private_log"); then
  fail 'isolated release directory could not be created'
fi
release_dir_created=1
chmod 0750 "$release_dir" 2>>"$private_log" || fail 'isolated release could not be protected'
extracted_dir="$release_dir"

if ! python3 - "$tar_file" "$extracted_dir" "$expected_sha" "$MAX_MEMBER_COUNT" "$MAX_FILE_BYTES" "$MAX_TAR_BYTES" >>"$private_log" 2>&1 <<'PY'
import os
import sys
import tarfile
from pathlib import Path

tar_path, destination_path, expected_sha = sys.argv[1:4]
max_member_count, max_file_bytes, max_total_bytes = (int(value) for value in sys.argv[4:7])
destination = Path(destination_path)

with tarfile.open(tar_path, "r:") as archive:
    if archive.pax_headers.get("comment") != expected_sha:
        raise ValueError("git archive comment does not match the requested revision")

    members = archive.getmembers()
    if not members or len(members) > max_member_count:
        raise ValueError("archive member count is outside the allowed range")

    seen = set()
    total_size = 0
    for member in members:
        name = member.name
        if (
            not name
            or name.startswith("/")
            or "\\" in name
            or name.endswith("/")
            or any(part in ("", ".", "..") for part in name.split("/"))
        ):
            raise ValueError("archive contains an unsafe path")
        if name != "apps" and name != "apps/web" and not name.startswith("apps/web/"):
            raise ValueError("archive contains a path outside apps/web")
        if name in seen:
            raise ValueError("archive contains a duplicate path")
        seen.add(name)

        if member.isdir():
            continue
        if not member.isreg() or member.size < 0 or member.size > max_file_bytes:
            raise ValueError("archive contains a non-regular file")
        total_size += member.size
        if total_size > max_total_bytes:
            raise ValueError("archive file contents exceed the size limit")

    for member in members:
        target = destination.joinpath(*member.name.split("/"))
        if member.isdir():
            target.mkdir(parents=True, exist_ok=False)
            os.chmod(target, member.mode & 0o777)
            continue

        target.parent.mkdir(parents=True, exist_ok=True)
        source = archive.extractfile(member)
        if source is None:
            raise ValueError("archive member could not be read")
        written = 0
        with source, target.open("xb") as output:
            while True:
                chunk = source.read(1024 * 1024)
                if not chunk:
                    break
                written += len(chunk)
                if written > member.size:
                    raise ValueError("archive member size changed while extracting")
                output.write(chunk)
        if written != member.size:
            raise ValueError("archive member size is invalid")
        os.chmod(target, member.mode & 0o777)

if not (destination / "apps" / "web" / "Dockerfile").is_file():
    raise ValueError("archive does not contain the web Dockerfile")
PY
then
  fail 'deployment archive failed safety validation'
fi
stage 'archive-validated'

private_log="$release_dir/deployment.log"
: >"$private_log"
chmod 0600 "$private_log" 2>>"$private_log" || fail 'private deployment log could not be protected'
release_source="$release_dir/apps/web"
release_validated=1
stage 'release-created'

compose_base=(
  docker compose
  --project-directory "$web_dir"
  --env-file "$env_file"
  --project-name "$INNOLIVE_WEB_COMPOSE_PROJECT"
  -f "$compose_main"
  -f "$compose_server"
  -f "$compose_gpu"
)

if ! web_before=$(web_container_id); then
  fail 'running web container could not be identified'
fi
if ! old_image_id=$(container_image_id "$web_before"); then
  fail 'current web image could not be identified'
fi
if ! db_before=$(container_id "$INNOLIVE_WEB_DB_CONTAINER"); then
  fail 'database container could not be identified'
fi
if ! proxy_before=$(container_id "$INNOLIVE_WEB_PROXY_CONTAINER"); then
  fail 'proxy container could not be identified'
fi
if ! env_before=$(hash_file "$env_file"); then
  fail 'runtime environment could not be fingerprinted'
fi
if ! compose_main_before=$(hash_file "$compose_main"); then
  fail 'main Compose file could not be fingerprinted'
fi
if ! compose_server_before=$(hash_file "$compose_server"); then
  fail 'server Compose file could not be fingerprinted'
fi
if ! compose_gpu_before=$(hash_file "$compose_gpu"); then
  fail 'GPU Compose file could not be fingerprinted'
fi

rollback_image="${INNOLIVE_WEB_COMPOSE_PROJECT}:web-previous-${expected_sha}"
if ! docker image tag "$old_image_id" "$rollback_image" >>"$private_log" 2>&1; then
  fail 'current web image could not be saved for rollback'
fi
stage 'protected-state-captured'

override_file="$release_dir/docker-compose.web-release.yml"
new_image="${INNOLIVE_WEB_COMPOSE_PROJECT}:web-${expected_sha}"
if ! write_release_override "$override_file" "$new_image"; then
  fail 'release Compose override could not be written'
fi
compose_with_release=("${compose_base[@]}" -f "$override_file")

build_log="$release_dir/docker-build.log"
  if ! "${compose_with_release[@]}" build web >"$build_log" 2>&1; then
  fail 'web image build failed; inspect the private release log'
fi
chmod 0600 "$build_log" 2>>"$private_log" || fail 'private build log could not be protected'

if ! built_image_id=$(image_id "$new_image"); then
  fail 'built web image could not be identified'
fi
if ! built_revision=$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$new_image" 2>>"$private_log"); then
  fail 'built web image label could not be inspected'
fi
built_revision=${built_revision//$'\n'/}
[[ "$built_revision" == "$expected_sha" ]] || fail 'built web image revision label is incorrect'
stage 'web-built'

replacement_attempted=1
if ! "${compose_with_release[@]}" up -d --no-deps --no-build web >>"$private_log" 2>&1; then
  fail 'web container replacement failed'
fi
stage 'web-replaced'

if ! web_after=$(web_container_id); then
  fail 'replacement web container could not be identified'
fi
if ! running_image_id=$(container_image_id "$web_after"); then
  fail 'replacement web image could not be identified'
fi
[[ "$running_image_id" == "$built_image_id" ]] || fail 'replacement web container is using an unexpected image'

if ! db_after=$(container_id "$INNOLIVE_WEB_DB_CONTAINER"); then
  fail 'database container could not be checked after replacement'
fi
if ! proxy_after=$(container_id "$INNOLIVE_WEB_PROXY_CONTAINER"); then
  fail 'proxy container could not be checked after replacement'
fi
if ! env_after=$(hash_file "$env_file"); then
  fail 'runtime environment could not be checked after replacement'
fi
if ! compose_main_after=$(hash_file "$compose_main"); then
  fail 'main Compose file could not be checked after replacement'
fi
if ! compose_server_after=$(hash_file "$compose_server"); then
  fail 'server Compose file could not be checked after replacement'
fi
if ! compose_gpu_after=$(hash_file "$compose_gpu"); then
  fail 'GPU Compose file could not be checked after replacement'
fi

[[ "$db_after" == "$db_before" ]] || fail 'database container identity changed during web deployment'
[[ "$proxy_after" == "$proxy_before" ]] || fail 'proxy container identity changed during web deployment'
[[ "$env_after" == "$env_before" ]] || fail 'runtime environment changed during web deployment'
[[ "$compose_main_after" == "$compose_main_before" ]] || fail 'main Compose file changed during web deployment'
[[ "$compose_server_after" == "$compose_server_before" ]] || fail 'server Compose file changed during web deployment'
[[ "$compose_gpu_after" == "$compose_gpu_before" ]] || fail 'GPU Compose file changed during web deployment'

site_url=${INNOLIVE_WEB_SITE_URL%/}
local_version_url="http://127.0.0.1:3010/api/version?deploy_sha=${expected_sha}&cache_bust=${expected_sha}"
public_version_url="${site_url}/api/version?deploy_sha=${expected_sha}&cache_bust=${expected_sha}"
if ! fetch_and_verify_endpoint "$local_version_url" "$release_dir/local-version.json"; then
  fail 'local web revision verification failed'
fi
stage 'local-version-verified'
if ! fetch_and_verify_endpoint "$public_version_url" "$release_dir/public-version.json"; then
  fail 'public web revision verification failed'
fi
stage 'public-version-verified'

state_file="$release_dir/deployment-state"
state_tmp="$release_dir/.deployment-state.tmp"
if ! {
  printf 'sha=%s\n' "$expected_sha"
  printf 'image=%s\n' "$new_image"
  printf 'source=%s\n' "$release_source"
  printf 'override=%s\n' "$override_file"
} >"$state_tmp"; then
  fail 'deployment state could not be written'
fi
chmod 0600 "$state_tmp" 2>>"$private_log" || fail 'deployment state could not be protected'
if ! mv "$state_tmp" "$state_file" >>"$private_log" 2>&1; then
  fail 'deployment state could not be saved'
fi
stage 'state-saved'
stage 'completed'
