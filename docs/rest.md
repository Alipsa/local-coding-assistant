# REST API

The REST API mirrors the CLI commands so remote clients can use the same workflows.
Endpoints live under `/api/cli` and accept JSON request bodies for POST endpoints.

Notes:
- Confirmation is required for destructive operations (patch apply, git apply/push, run, stage, revert).
  Pass `confirm: true` for those endpoints or they will return `400`.
- `/edit` does not launch an editor. It either echoes the supplied `seed` or forwards
  it to `/chat` when `send` is true.
- Local-only mode (`assistant.local-only=true`) blocks remote REST access regardless of other settings.
- Remote access is blocked by default. Enable with `assistant.rest.remote.enabled=true`.
- HTTPS is required for remote access by default; disable with `assistant.rest.require-https=false` only for local dev.
- If `assistant.rest.api-key` is set, include the `X-API-Key` header.
- Optional rate limiting uses `assistant.rest.rate-limit.per-minute`.
- Optional OIDC validation: set `assistant.rest.oidc.enabled=true`, plus
  `assistant.rest.oidc.jwks-file` (or `assistant.rest.oidc.jwks-uri`) and `assistant.rest.oidc.issuer`.
  Add `assistant.rest.oidc.audience` if your tokens require an audience check, and
  `assistant.rest.oidc.jwks-timeout-millis` to tune JWKS fetch timeouts.
- Optional scopes: set `assistant.rest.scope.read` and/or `assistant.rest.scope.write`.
  For API keys, configure `assistant.rest.api-key-scopes` to grant scopes.
- Numeric parameters are validated (for example: `limit`/`page`/`timeoutMillis`/`maxOutputChars` min 1,
  `context`/`maxChars`/`maxTokens`/`padding` min 0, `depth` min -1).
- `/api/code/generateAndReview` uses the default session settings and system prompt for all requests.

## Examples

Chat:
```
POST /api/cli/chat
{"prompt":"Summarize the repo layout","persona":"ARCHITECT"}
```

Plan:
```
POST /api/cli/plan
{"prompt":"Review src/main/groovy and suggest improvements","persona":"ARCHITECT"}
```

Route:
```
POST /api/cli/route
{"prompt":"Please review src/main/groovy and suggest improvements"}
```

Review:
```
POST /api/cli/review
{"prompt":"Check error handling","paths":["src/main/groovy"]}
```

Security review:
```
POST /api/cli/review
{"prompt":"Look for injection risks","paths":["src/main/groovy"],"security":true}
```

SAST-enabled review:
```
POST /api/cli/review
{"prompt":"Run checks","paths":["src/main/groovy"],"sast":true}
```

OIDC-authenticated request:
```
GET /api/cli/health
Authorization: Bearer <access-token>
```

Codesearch:
```
GET /api/cli/codesearch?query=applyPatch&paths=src/main/groovy
```

Tree:
```
GET /api/cli/tree?depth=3
```

Run:
```
POST /api/cli/run
{"command":"./mvnw -q test","timeoutMillis":120000}
```

For all endpoints and request fields, see `docs/commands.md`.
