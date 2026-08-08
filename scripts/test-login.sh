#!/usr/bin/env bash
# Seed a confirmed test user (bypasses email confirmation) and exercise login.
# Fill in USER_POOL_ID / USER_POOL_CLIENT_ID / REGION below, or export them before running.
# Get the pool/client IDs from stack outputs:
#   sam list stack-outputs --stack-name <stack>
set -euo pipefail

USER_POOL_ID="${USER_POOL_ID:-us-east-1_CC787AutF}"
USER_POOL_CLIENT_ID="${USER_POOL_CLIENT_ID:-1c07kmvifk3fqltffhg557ln0k}"
REGION="${REGION:-us-east-1}"
EMAIL="${EMAIL:-test@example.com}"
PASSWORD="${PASSWORD:-YourTestPass123!}"

# 1. Create the user, suppress the welcome/verification email
aws cognito-idp admin-create-user \
  --user-pool-id "$USER_POOL_ID" \
  --username "$EMAIL" \
  --message-action SUPPRESS \
  --region "$REGION"

# 2. Set a permanent password -> user becomes CONFIRMED, no code needed
aws cognito-idp admin-set-user-password \
  --user-pool-id "$USER_POOL_ID" \
  --username "$EMAIL" \
  --password "$PASSWORD" \
  --permanent \
  --region "$REGION"

# 3. Exercise the same call LoginHandler makes, directly against Cognito
aws cognito-idp initiate-auth \
  --client-id "$USER_POOL_CLIENT_ID" \
  --auth-flow USER_PASSWORD_AUTH \
  --auth-parameters USERNAME="$EMAIL",PASSWORD="$PASSWORD" \
  --region "$REGION"
