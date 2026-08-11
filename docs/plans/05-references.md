# Reference Documents

## AWS Official Docs

- DynamoDB single-table design: https://aws.amazon.com/blogs/compute/creating-a-single-table-design-with-amazon-dynamodb/
- DynamoDB TTL + Streams: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/time-to-live-ttl-streams.html
- DynamoDB Global Tables: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/GlobalTables.html
- Lambda SnapStart: https://docs.aws.amazon.com/lambda/latest/dg/snapstart.html
- Lambda concurrency model: https://docs.aws.amazon.com/lambda/latest/dg/lambda-concurrency.html
- API Gateway throttling: https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-request-throttling.html
- Cognito + API Gateway JWT authorizer: https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-integrate-with-cognito.html
- Cognito Google IdP setup: https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-social-idp.html
- Cognito quotas: https://docs.aws.amazon.com/cognito/latest/developerguide/quotas.html
- X-Ray + API Gateway service maps: https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-using-xray-maps.html
- Lambda Powertools for Java: https://docs.aws.amazon.com/powertools/java/latest/
- AWS SAM + GitHub Actions OIDC: https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/deploying-with-oidc.html
- Distributed Load Testing on AWS: https://docs.aws.amazon.com/solutions/distributed-load-testing-on-aws/
- k6 docs: https://k6.io/docs/
- Serverless Land patterns library: https://serverlessland.com/patterns

## Conceptual / System-Design References

- Alex Xu, *System Design Interview* (Vol. 1) — the canonical URL-shortener chapter this project's requirements mirror
- Martin Kleppmann, *Designing Data-Intensive Applications* — theory of partitioning, hot keys, caching, idempotency (not AWS-specific, but the conceptual backbone for the Phase 2 deep dive)
- AWS Well-Architected Framework, Reliability & Performance Efficiency pillars: https://aws.amazon.com/architecture/well-architected/
- `../learning/01-system-design-concepts.md` — beginner-friendly recap of the system-design concepts behind this project's decisions, mapped to specific DDIA chapters/pages
