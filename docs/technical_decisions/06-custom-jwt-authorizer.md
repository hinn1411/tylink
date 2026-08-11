## Context
Some routes in the system must support public access.

## Options considered

- **Native JWT authorizer** Zero custom
  code, AWS-managed verification. But it rejects public users because they don't have JWT
- **Verify the JWT inside `Handler`** Works but we need to duplicate JWT verification
  logic into functions that support public route (violates DRY). Additionally, this violates single-responsibility principles, Handlers only care about business logic
- **Custom Lambda authorizer (chosen).** Can support public and private user at the same  time. But this solution increases development effort, we need to create an extra function
utilizing Cognito APIs

## Decision
Create a custom Authorizer, it does the followings:

1. Reads the `Authorization` header if present.
2. Verifies raw token
3. Returns verification result to Gateway destination

## Scope: only for routes needing optional auth

This custom authorizer (`ExtractTokenAuthorizer` in `template.yaml`) is specifically for routes
that must support **both** anonymous and authenticated callers on the same path — `create` and
`redirect`. Routes that always require a caller (e.g. `list` — `GET /v1/urls`) use a **native
JWT authorizer** (`NativeJwtAuthorizer` in `template.yaml`) instead: API Gateway verifies the
Cognito-issued token and rejects the request before any Lambda runs, with zero custom code. This
is exactly the case the "Native JWT authorizer" option above was rejected for ("rejects public
users because they don't have JWT") — that's a problem only when a route needs to serve
anonymous callers too. A route with no such requirement should default to the native JWT
authorizer, not this custom one.
