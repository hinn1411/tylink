#!/usr/bin/env bash
# Tests login directly against Cognito, bypassing the API.
# Requires a registered test user (scripts/auth/register-test-user.sh) and USER_POOL_CLIENT_ID.
set -euo pipefail

USER_POOL_CLIENT_ID="${USER_POOL_CLIENT_ID:-1c07kmvifk3fqltffhg557ln0k}"
REGION="${REGION:-us-east-1}"

read -rp "Email: " EMAIL
read -rsp "Password: " PASSWORD
echo

aws cognito-idp initiate-auth \
  --client-id "$USER_POOL_CLIENT_ID" \
  --auth-flow USER_PASSWORD_AUTH \
  --auth-parameters USERNAME="$EMAIL",PASSWORD="$PASSWORD" \
  --region "$REGION"
