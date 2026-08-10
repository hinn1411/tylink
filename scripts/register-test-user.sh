#!/usr/bin/env bash
# Seed a confirmed test user (bypasses email confirmation), for use with test-basic-login.sh or
# manual API testing — not the app's real sign-up path.
# Fill in USER_POOL_ID / REGION below, or export them before running. Get the pool ID from stack
# outputs:
#   sam list stack-outputs --stack-name <stack>
set -euo pipefail

USER_POOL_ID="${USER_POOL_ID:-us-east-1_CC787AutF}"
REGION="${REGION:-us-east-1}"

read -rp "Email: " EMAIL
read -rsp "Password: " PASSWORD
echo

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

echo "Registered and confirmed: $EMAIL"
