#!/usr/bin/env bash
# Builds and starts the API locally, applying env.json for real deployed config values
# (e.g. Cognito IDs). Set SKIP_BUILD=1 to skip the build step on repeat runs.
set -euo pipefail

PORT="${PORT:-3000}"

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  sam build
fi

sam local start-api --port "$PORT" --env-vars env.json --warm-containers EAGER
