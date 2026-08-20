#!/usr/bin/env bash
# Seeds a confirmed test user for manual login testing.
# Fill in USER_POOL_ID / REGION below, or export them before running. Get the pool ID:
#   sam list stack-outputs --stack-name <stack>
set -euo pipefail

USER_POOL_ID="${USER_POOL_ID:-us-east-1_CC787AutF}"
REGION="${REGION:-us-east-1}"

read -rp "Email: " EMAIL
read -rsp "Password: " PASSWORD
echo

# Create the user
aws cognito-idp admin-create-user \
  --user-pool-id "$USER_POOL_ID" \
  --username "$EMAIL" \
  --message-action SUPPRESS \
  --region "$REGION"

# Set a permanent password
aws cognito-idp admin-set-user-password \
  --user-pool-id "$USER_POOL_ID" \
  --username "$EMAIL" \
  --password "$PASSWORD" \
  --permanent \
  --region "$REGION"

echo "Registered and confirmed: $EMAIL"
