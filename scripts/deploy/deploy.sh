#!/usr/bin/env bash
# Deploys the stack. Pulls the Google OAuth client secret from SSM and prompts for
# ALERT_EMAIL if not already set (pass it as an env var to skip the prompt, e.g. in CI).
# Extra args (e.g. --guided) pass straight through to `sam deploy`.
set -euo pipefail

GOOGLE_CLIENT_SECRET_PARAM="${GOOGLE_CLIENT_SECRET_PARAM:-/tylink/google-oauth-client-secret}"

GOOGLE_CLIENT_SECRET=$(aws ssm get-parameter \
  --name "$GOOGLE_CLIENT_SECRET_PARAM" \
  --with-decryption \
  --query Parameter.Value \
  --output text)

if [[ -z "${ALERT_EMAIL:-}" ]]; then
  read -rp "Enter ALERT_EMAIL (receives CloudWatch alarm notifications): " ALERT_EMAIL
fi
: "${ALERT_EMAIL:?ALERT_EMAIL is required}"

sam deploy --parameter-overrides "GoogleClientSecret=$GOOGLE_CLIENT_SECRET AlertEmail=$ALERT_EMAIL" "$@"
