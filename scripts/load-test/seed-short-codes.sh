#!/usr/bin/env bash
# Seeds short codes for the load-test scripts (realistic.js/stress.js) by POSTing to
# POST /v1/urls repeatedly (unauthenticated, PUBLIC), then writes the resulting shortCodes
# to a JSON array file that k6's SharedArray loads directly (see lib.js).
# Requires: BASE_URL reachable (sam local start-api, or a deployed HttpApiUrl), and jq.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:3000}"
COUNT="${COUNT:-20}"
OUTPUT_FILE="${OUTPUT_FILE:-$(dirname "$0")/short-codes.json}"

codes=()

for i in $(seq 1 "$COUNT"); do
  idempotency_key=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen)
  response=$(curl -sS -X POST "$BASE_URL/v1/urls" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $idempotency_key" \
    -d "{\"longUrl\": \"https://example.com/seed/$i\", \"visibility\": \"PUBLIC\"}")

  short_code=$(echo "$response" | jq -r '.shortCode // empty')
  if [[ -z "$short_code" ]]; then
    echo "Failed to seed short code $i: $response" >&2
    exit 1
  fi

  codes+=("$short_code")
  echo "Seeded $i/$COUNT: $short_code"
done

jq -n '$ARGS.positional' --args "${codes[@]}" > "$OUTPUT_FILE"
echo "Wrote $COUNT short codes to $OUTPUT_FILE"
