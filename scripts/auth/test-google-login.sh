#!/usr/bin/env bash
# Exercises the Google federation login flow (docs/learning/09-oauth2-and-oidc.md §8) with no
# frontend.
#
# Fill in DOMAIN / CLIENT_ID below, or export them before running. Get them from stack outputs:
#   sam list stack-outputs --output json | jq -r '.[] | select(.OutputKey=="CognitoHostedUiDomain" or .OutputKey=="UserPoolClientId")'
set -euo pipefail

DOMAIN="${DOMAIN:-https://tylink-725069913301.auth.us-east-1.amazoncognito.com}"
CLIENT_ID="${CLIENT_ID:-1c07kmvifk3fqltffhg557ln0k}"
# Must match one of UserPoolClient's CallbackURLs in template.yaml.
REDIRECT_URI="${REDIRECT_URI:-https://example.com/callback}"
SCOPE="openid email profile"

urlencode() {
  local string="$1" i c encoded=""
  for (( i = 0; i < ${#string}; i++ )); do
    c="${string:i:1}"
    case "$c" in
      [a-zA-Z0-9.~_-]) encoded+="$c" ;;
      *) printf -v c '%%%02X' "'$c"; encoded+="$c" ;;
    esac
  done
  printf '%s' "$encoded"
}

# Generates the PKCE verifier/challenge (docs/learning/09-oauth2-and-oidc.md §3) for the token
# exchange.
CODE_VERIFIER=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')
CODE_CHALLENGE=$(printf '%s' "$CODE_VERIFIER" | openssl dgst -sha256 -binary | openssl base64 | tr '+/' '-_' | tr -d '=')
STATE=$(openssl rand -hex 16)

AUTHORIZE_URL="${DOMAIN}/oauth2/authorize?identity_provider=Google&response_type=code&client_id=${CLIENT_ID}&redirect_uri=$(urlencode "$REDIRECT_URI")&scope=$(urlencode "$SCOPE")&state=${STATE}&code_challenge=${CODE_CHALLENGE}&code_challenge_method=S256"

echo "1. Open this URL in a browser and sign in with Google:"
echo
echo "   $AUTHORIZE_URL"
echo
echo "2. After Google redirects back through Cognito, the browser lands on a 404 at"
echo "   ${REDIRECT_URI}?code=...&state=..."
echo
read -rp "Paste the full address-bar URL here (or just the code): " PASTED

# Accepts either the full callback URL or a bare code.
if [[ "$PASTED" == *"code="* ]]; then
  CODE="${PASTED#*code=}"
  CODE="${CODE%%&*}"
else
  CODE="$PASTED"
fi

echo
echo "Exchanging code at ${DOMAIN}/oauth2/token ..."
curl -sS -X POST "${DOMAIN}/oauth2/token" \
  --data-urlencode "grant_type=authorization_code" \
  --data-urlencode "client_id=${CLIENT_ID}" \
  --data-urlencode "code=${CODE}" \
  --data-urlencode "redirect_uri=${REDIRECT_URI}" \
  --data-urlencode "code_verifier=${CODE_VERIFIER}" \
  | (command -v jq >/dev/null && jq . || cat)
