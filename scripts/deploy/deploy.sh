#!/usr/bin/env bash
# Pulls GoogleClientSecret fresh from SSM each run — samconfig.toml is git-tracked, and a plain
# ssm-secure dynamic reference isn't on CloudFormation's allowlist for
# AWS::Cognito::UserPoolIdentityProvider. ALERT_EMAIL is kept out of git the same way, even
# though it's not a secret — pass it as an env var to skip the prompt (e.g. in CI). Extra args
# (e.g. --guided) pass straight through to `sam deploy`.
#
# --parameter-overrides is single-value — both overrides must share one quoted string, or the
# second call overwrites the first instead of merging.
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
