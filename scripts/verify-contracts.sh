#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"

python3 -m json.tool "$root/contracts/signaling/v1.schema.json" >/dev/null
python3 -m json.tool "$root/contracts/fixtures/signaling-offer.v1.json" >/dev/null

echo "Contracts: JSON syntax valid"

