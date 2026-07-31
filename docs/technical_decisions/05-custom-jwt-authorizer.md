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
