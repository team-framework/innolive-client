#!/usr/bin/env bash
set -euo pipefail

original_command=${SSH_ORIGINAL_COMMAND:-}

if [[ "$original_command" == *$'\n'* || "$original_command" == *$'\r'* ]]; then
  echo 'only the deploy-web command is allowed' >&2
  exit 64
fi

if [[ ! "$original_command" =~ ^deploy-web\ ([0-9a-f]{40})$ ]]; then
  echo 'only the deploy-web command is allowed' >&2
  exit 64
fi

revision=${BASH_REMATCH[1]}
exec sudo -n -- /opt/innolive/deploy/deploy-innolive-web.sh "$revision"
