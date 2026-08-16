#!/usr/bin/env bash
# Pulls GoogleClientSecret fresh from SSM each run — samconfig.toml is git-tracked, and a plain
# ssm-secure dynamic reference isn't on CloudFormation's allowlist for
# AWS::Cognito::UserPoolIdentityProvider. ALERT_EMAIL is required the same way for the same
# reason (keep it out of git), even though it's not a secret. Extra args (e.g. --guided) pass
# straight through to `sam deploy`.
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

: "${ALERT_EMAIL:?Set ALERT_EMAIL to the email address that should receive CloudWatch alarm notifications}"

sam deploy --parameter-overrides "GoogleClientSecret=$GOOGLE_CLIENT_SECRET AlertEmail=$ALERT_EMAIL" "$@"
