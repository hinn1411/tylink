#!/usr/bin/env bash
# Deploy the stack, pulling the Google OAuth client secret fresh from SSM each time — it can't
# live in samconfig.toml (git-tracked) or a plain ssm-secure dynamic reference
# (AWS::Cognito::UserPoolIdentityProvider isn't on CloudFormation's ssm-secure allowlist; see the
# GoogleClientSecret parameter's description in template.yaml). Any extra args (e.g. --guided)
# pass straight through to `sam deploy`.
set -euo pipefail

GOOGLE_CLIENT_SECRET_PARAM="${GOOGLE_CLIENT_SECRET_PARAM:-/tylink/google-oauth-client-secret}"

GOOGLE_CLIENT_SECRET=$(aws ssm get-parameter \
  --name "$GOOGLE_CLIENT_SECRET_PARAM" \
  --with-decryption \
  --query Parameter.Value \
  --output text)

sam deploy --parameter-overrides GoogleClientSecret="$GOOGLE_CLIENT_SECRET" "$@"
