# CI/CD & Infrastructure-as-Code

## CI/CD Shape (realistic for a solo learning project)

GitHub Actions + GitHub **OIDC** (no long-lived AWS keys in secrets), scoped deploy role:

`build (Maven + sam build) → unit tests → sam validate --lint → sam deploy to a dev stack on push to main → optional integration-test job against the freshly deployed dev stack`

Skip `sam pipeline bootstrap`'s multi-account tooling — it's built for enterprise multi-account setups, overkill for a solo project; hand-write the OIDC role + workflow directly (the official `aws-samples/aws-sam-github-actions-example` repo is a good template).

Load testing runs as its own manually-triggered workflow, not on every commit (cost + Cognito quota impact).

## IaC: Why SAM Alone Is Enough

Stick with SAM for the whole project — no CDK.

- SAM is a CloudFormation superset, so anything CloudFormation can declare (CloudFront, KMS, Cognito) is expressible via raw `AWS::*` resource types even where SAM's shorthand doesn't natively cover it.
- CDK's real advantage (L2 abstractions, imperative code, reusable constructs) pays off in large multi-stack, non-serverless-heavy setups (VPCs, ECS, RDS) — not here.
- SAM's local tooling (`sam local start-api`, `sam local invoke`) is also better tuned to a Lambda-first workflow.

Worth flagging CDK as "the natural next IaC tool to learn" for a future, larger project — not this one.
