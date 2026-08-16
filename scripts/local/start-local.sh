#!/usr/bin/env bash
# Builds and starts sam local start-api with env.json applied, so functions whose config
# comes from a !Ref sam local can't resolve locally (e.g. Cognito User Pool / Client IDs —
# see LoginFunction/ExtractTokenAuthorizerFunction in env.json) get the real deployed values
# instead of a broken literal-logical-ID fallback.
# Set SKIP_BUILD=1 to skip the build step on repeat runs where only template.yaml changed.
set -euo pipefail

PORT="${PORT:-3000}"

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  sam build
fi

sam local start-api --port "$PORT" --env-vars env.json --warm-containers EAGER
