#!/usr/bin/env bash
# Exercise the same call LoginHandler makes, directly against Cognito.
# Register a user first with scripts/auth/register-test-user.sh if you don't have one.
# Fill in USER_POOL_CLIENT_ID / REGION below, or export them before running. Get the client ID
# from stack outputs:
#   sam list stack-outputs --stack-name <stack>
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
