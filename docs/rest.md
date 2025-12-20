# REST API

The REST API mirrors the CLI commands so remote clients can use the same workflows.
Endpoints live under `/api/cli` and accept JSON request bodies for POST endpoints.

Notes:
- Confirmation gates are disabled by default for REST requests; pass `confirm: true`
  to opt into safety prompts where supported.
- `/edit` does not launch an editor. It either echoes the supplied `seed` or forwards
  it to `/chat` when `send` is true.

## Examples

Chat:
```
POST /api/cli/chat
{"prompt":"Summarize the repo layout","persona":"ARCHITECT"}
```

Review:
```
POST /api/cli/review
{"prompt":"Check error handling","paths":["src/main/groovy"]}
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
