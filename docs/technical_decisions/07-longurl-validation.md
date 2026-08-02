## Context
`ShortenUrlHandler` accepts an arbitrary `longUrl` from any caller (including unauthenticated public callers for `PUBLIC` visibility) and stores it for later redirection. Before this decision, the only check was that `longUrl` was non-blank — no protocol restriction, no content validation.

## Options considered

- **SQL injection denylist.** Not needed: `longUrl` is written via `AttributeValue.fromS(...)` through DynamoDB's typed `PutItemRequest` API, never concatenated into a query string anywhere in the codebase. A denylist would add no real safety and would reject legitimate URLs (query strings routinely contain `'`, `;`, `=`). **Rejected** — no SQLi-specific logic added.
- **HTML sanitizer library (e.g. OWASP Java HTML Sanitizer).** Would work but adds a dependency for a problem strict URI parsing already solves, since `longUrl` is never rendered as HTML by this codebase today.
- **Strict RFC 3986 URI parsing + protocol allowlist (chosen).** `java.net.URI`'s single-arg constructor is a strict syntax parser: unescaped `<`, `>`, `"`, spaces, and other HTML/script metacharacters are illegal in a URI and throw `URISyntaxException`. Combined with an explicit reject on ISO control characters (CR/LF/NUL) and a `{http, https}` scheme allowlist, this blocks XSS-shaped payloads, non-redirectable protocols (`javascript:`, `data:`, `file:`, ...), and future `Location`-header CRLF injection, without a new dependency.

## Decision
Added `LongUrlValidator` (`com.tylink.features.shorten.util`), used by `ShortenUrlHandler` after the existing blank check:

1. Trim whitespace; reject if resulting length exceeds 2048 characters.
2. Reject if it contains any ISO control character (blocks CR/LF/NUL header-injection payloads).
3. Parse with `new URI(...)`; reject on `URISyntaxException` (rejects raw `<`, `>`, `"`, unescaped spaces, etc.).
4. Reject unless `scheme` is `http` or `https` (case-insensitive).
5. Reject if `host` is blank.

Query parameters and fragments are accepted and stored as-is — the full string is validated as one unit rather than stripped or rewritten.

Deferred, tracked separately (feature F6 in `docs/plans/00-overview.md`): domain blocklist for phishing/spam, create-rate-limiting. Neither is an injection/XSS concern.
