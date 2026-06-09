# MCP Client Support for LCA

**Date:** 2026-06-09
**Status:** Approved (revised after review)

## Goal

Add Model Context Protocol (MCP) client support to LCA so that MCP servers developed for Claude (and other MCP-compatible hosts) work out of the box. LCA becomes an MCP client that can discover and invoke tools, read resources, and use prompt templates from any stdio-based MCP server.

## Decisions

- **Scope:** Full MCP client — tools, resources, and prompts
- **Transport:** stdio only (matches Claude Desktop / Claude Code)
- **Config format:** Claude-compatible JSON (`mcpServers` block)
- **Library:** Spring AI MCP Client Starter — already on classpath via Embabel 0.3.5 (`spring-ai-starter-mcp-client:1.1.4`, `mcp-sdk:0.17.0`). No new dependency needed.
- **Tool presentation:** Hybrid — user-guided activation with budget-based autoselect as default
- **Internal representation:** MCP tool calls use `StandardToolCall` with `Map<String, Object>` arguments. Built-in tools keep existing `ToolCall` with `List<String>` arguments. `ToolCallDispatcher` accepts both types.

## Architecture

### 1. Configuration Layer

`McpConfigLoader` reads Claude-compatible JSON config files at startup.

**Config file priority:**
1. `~/.lca/mcp-servers.json` (LCA-specific, same format)
2. `~/.claude/settings.json` → `mcpServers` block
3. `~/Library/Application Support/Claude/claude_desktop_config.json`
4. Path from `assistant.mcp.servers-configuration` property

**Format:**
```json
{
  "mcpServers": {
    "bq": {
      "command": "uvx",
      "args": ["mcp-server-bigquery", "--project", "my-project"],
      "env": { "GOOGLE_APPLICATION_CREDENTIALS": "/path/to/creds.json" }
    }
  }
}
```

The loader merges configs (later files override earlier ones for same server name — full replacement, no partial merging of env vars or args) and writes a consolidated JSON file that the Spring AI starter reads via `spring.ai.mcp.client.stdio.servers-configuration`.

**Missing binary handling:** If a server's `command` binary is not found on `PATH` (e.g. `uvx` not installed), the loader logs a warning and skips that server. LCA startup is not blocked. `/mcp status` shows the server as `not started — command not found`.

### 2. MCP Client Integration Layer

**`McpToolRegistry`** — central Spring `@Component` wrapping `List<McpSyncClient>`.

Responsibilities:
- Registry of connected servers and their capabilities (tools, resources, prompts)
- Cached listings with TTL, refreshed on change notifications (`toolsChangeConsumer`, `resourcesChangeConsumer`, `promptsChangeConsumer`)
- Methods: `listTools(serverName?)`, `callTool(serverName, toolName, args)`, `listResources(serverName?)`, `readResource(uri)`, `listPrompts(serverName?)`, `getPrompt(serverName, promptName, args)`
- Delegates per-session activation state to `McpSessionState`
- Implements `McpToolFilter` to expose only tools from active servers
- Health tracking — marks crashed servers as unhealthy

**`McpSessionState`** — separate `@Component` owning all MCP-specific per-session state. Keeps MCP concerns isolated from `SessionState`.

Fields per session:
- `Set<String> activeMcpServers` — explicitly activated servers
- `McpActivationMode` enum (`AUTO`, `MANUAL`) — current mode
- Methods: `activate(sessionId, serverName)`, `deactivate(sessionId, serverName)`, `resetToAuto(sessionId)`, `isActive(sessionId, serverName)`, `getMode(sessionId)`

**Server activation model:**
- Default: autoselect mode (budget-based — see Section 6)
- `/mcp use <server>` — explicitly activate, switches session to manual mode
- `/mcp stop <server>` — deactivate a server
- `/mcp autoselect` — return to autoselect mode, clearing manual overrides
- `/mcp status` — show all servers with health and activation state

**Lifecycle:** Managed by Spring AI starter — child processes start on init, graceful shutdown on close.

### 3. Tool Call Architecture

LCA uses text-based tool calling (regex parsing of LLM output via `ToolCallParser`). Two separate call types flow through a unified dispatcher.

**Flow:**
```
LLM text output
  |
  +-> ToolCallParser
        |
        +-> Built-in patterns (writeFile, replace, etc.) --> ToolCall (existing)
        +-> MCP pattern (mcp_*_*({...}))                 --> StandardToolCall (new)
        |
        +-> ToolCallDispatcher
              |
              +-> ToolCall     --> executeSingleTool() (existing logic, unchanged)
              +-> StandardToolCall --> McpToolExecutor.execute()
```

**Built-in tools are NOT migrated.** `ToolCall` with `List<String>` positional arguments and `executeSingleTool()` with index-based access (`call.arguments[0]`) remain unchanged. No risk of breaking working code.

**`StandardToolCall`** — new canonical class for MCP tool calls only:
```groovy
@Canonical
class StandardToolCall {
  String serverName  // "bq", "filesystem", etc.
  String toolName    // "query", "read_file", etc.
  Map<String, Object> arguments  // named args from JSON
}
```

**`ToolCallDispatcher`** — new component that accepts both `ToolCall` and `StandardToolCall`:
- `dispatch(ToolCall)` → delegates to existing `executeSingleTool()` logic
- `dispatch(StandardToolCall)` → delegates to `McpToolExecutor`

**`ToolCallParser` changes:**
- `parseToolCalls()` returns a new `ParsedToolCalls` result containing both `List<ToolCall>` (built-in) and `List<StandardToolCall>` (MCP)
- New MCP pattern: `mcp_<server>_<tool>({...})` where the argument block is JSON
- Example: `mcp_bq_query({"sql": "SELECT count(*) FROM t", "max_rows": 10})`

**Malformed JSON fallback:** Local models may produce invalid JSON arguments. `ToolCallParser` attempts lenient parsing (trailing commas, unquoted keys). If JSON still fails, the tool call is skipped with a warning logged and an error message returned to the LLM: `"Failed to parse arguments for mcp_bq_query — expected valid JSON object"`. This gives the LLM a chance to retry on the next turn.

**`McpToolPromptBuilder`** generates tool descriptions for the system prompt:
```
Available MCP tools:

mcp_bq_query(sql, dry_run?, max_rows?) - Execute a SQL query against BigQuery
  - sql (string, required): The SQL query
  - dry_run (boolean, optional): Validate without executing
  - max_rows (integer, optional): Max rows to return (default 100)

Call MCP tools with JSON arguments:
  mcp_bq_query({"sql": "SELECT 1", "max_rows": 10})
```

**Tool name format:** `mcp_<serverName>_<toolName>` — prefixed to avoid collisions with built-in tools.

### 4. Resource and Prompt Integration

**Resources:**
- `/mcp resources [server]` — list available resources (URI, name, description, MIME type)
- `/mcp read <uri>` — read and display a resource
- Agent context injection — `ContextPacker` can pull MCP resources as additional context
- `mcp_read_resource(uri)` tool call pattern available to the LLM
- Change notifications handled automatically by the starter

**Prompts:**
- `/mcp prompts [server]` — list prompt templates (name, description, arguments)
- `/mcp prompt <name> [args...]` — fetch a template, display it or feed into `/chat`
- User-initiated only — no automatic agent integration, avoids conflicts with LCA's persona system

### 5. Slash Commands

All MCP interaction under `/mcp`:

| Command | Description |
|---|---|
| `/mcp status` | Show servers, health, active/auto/manual state |
| `/mcp use <server>` | Activate a server (switches to manual mode) |
| `/mcp stop <server>` | Deactivate a server |
| `/mcp autoselect` | Return to autoselect mode |
| `/mcp restart <server>` | Restart a crashed or unhealthy server |
| `/mcp tools [server]` | List available tools |
| `/mcp call <tool> <json>` | Directly invoke a tool (bypass LLM). Arguments as JSON: `/mcp call bq.query {"sql": "SELECT 1"}` |
| `/mcp resources [server]` | List resources |
| `/mcp read <uri>` | Read a resource |
| `/mcp prompts [server]` | List prompt templates |
| `/mcp prompt <name> [args...]` | Fetch and use a prompt |

Implemented in `McpCommands` component, routed from `CommandExecutor`.

JLine tab completion for server names and tool names. Resource URI completion is lazy-loaded (fetched on first tab press for a given server) to avoid expensive upfront listing.

`IntentRouterAgent` gets `/mcp` added to allowed commands for natural language routing.

### 6. Autoselect / Budget-Based Tool Inclusion

Default mode — includes all active servers' tools up to a context budget cap.

**Mechanism:**
1. All healthy servers' tools are candidates for inclusion
2. `McpToolPromptBuilder` serialises tool descriptions and tracks character count
3. Hard cap: `assistant.mcp.tool-description-budget` (default: 3000 chars)
4. Tools are included in server registration order until budget is exhausted
5. If budget is exceeded, remaining tools are listed by name only (no parameter descriptions) as a compact fallback
6. Code context always takes priority — MCP tool descriptions are allocated from the remaining budget after code context

**Why not domain-based filtering (v1):** Keyword heuristics for domain tagging are fragile and would add output format complexity to the intent router's local LLM. Budget-based inclusion degrades gracefully. Domain-based curation can be added as a v2 optimisation once the base system is proven.

**Priority ordering in `ContextBudgetManager`:**
1. Code context (files, diffs, search results) — existing budget
2. MCP tool descriptions — capped at `tool-description-budget`
3. Conversation history — remaining budget

### 7. Error Handling and Server Crash Recovery

**Crash detection:** The Spring AI starter detects when a stdio child process exits unexpectedly. `McpToolRegistry` receives this via the transport layer and marks the server as unhealthy.

**Recovery flow:**
1. Tool call targets unhealthy server → return error text to LLM: `"MCP server 'bq' is unavailable — it crashed or failed to start. Use /mcp restart bq to retry."`
2. On next LLM turn, `McpToolPromptBuilder` excludes tools from unhealthy servers (no stale tool descriptions in the prompt)
3. `/mcp restart <server>` — attempts to restart the server process. On success, tools are re-discovered and re-registered
4. `/mcp status` shows unhealthy servers with the reason (exit code, stderr snippet if available)

**No automatic restart** — restarting a crashlooping server would waste resources. User must explicitly restart.

### 8. Security and Auditability

**Logging:** All MCP tool invocations are logged at INFO level with server name, tool name, and argument keys (not values, to avoid leaking sensitive data). Full arguments logged at DEBUG level.

**Confirmation prompts:** Tool calls are classified by the MCP server's tool annotations. If not annotated, `McpToolExecutor` applies a conservative heuristic:
- Tool names containing `write`, `delete`, `create`, `update`, `send`, `execute`, `run` → prompt user for confirmation before execution
- Read-only tools (`list`, `get`, `read`, `search`, `query`) → execute without confirmation
- Configurable via `assistant.mcp.confirm-destructive` (default: `true`)

**Argument sanitisation:** `McpToolExecutor` does not sanitise arguments beyond JSON schema validation. The MCP server is user-configured (trusted), and sanitisation would break legitimate use cases (e.g. SQL queries contain path-like strings). The confirmation prompt is the safety net for destructive operations.

## New Components

| Component | Path | Responsibility |
|---|---|---|
| `McpConfigLoader` | `mcp/McpConfigLoader.groovy` | Read Claude-format configs, merge, feed to starter |
| `McpToolRegistry` | `mcp/McpToolRegistry.groovy` | Central registry, caching, health tracking, `McpToolFilter` |
| `McpSessionState` | `mcp/McpSessionState.groovy` | Per-session MCP activation state (auto/manual, active servers) |
| `McpToolPromptBuilder` | `mcp/McpToolPromptBuilder.groovy` | Generate tool/resource descriptions for system prompts, budget-aware |
| `McpToolExecutor` | `mcp/McpToolExecutor.groovy` | Validate args, confirm destructive ops, invoke `callTool()`, format results |
| `StandardToolCall` | `tools/StandardToolCall.groovy` | Canonical class for MCP tool calls |
| `ToolCallDispatcher` | `tools/ToolCallDispatcher.groovy` | Route `ToolCall` (built-in) and `StandardToolCall` (MCP) to executors |
| `McpCommands` | `shell/McpCommands.groovy` | All `/mcp` subcommands |

## Modified Components

| File | Change |
|---|---|
| `ToolCallParser` | Return `ParsedToolCalls` (both `List<ToolCall>` and `List<StandardToolCall>`). Add MCP tool call pattern with lenient JSON parsing. |
| `CommandExecutor` | Route `/mcp` to `McpCommands` |
| `IntentRouterAgent` | Add `/mcp` to allowed commands |
| `CodingAssistantAgent` | Inject `McpToolPromptBuilder` to include MCP tool descriptions in system prompts |
| `ContextBudgetManager` | Add MCP tool description budget tier (after code context, before history) |
| `application.properties` | Add `assistant.mcp.*` properties |

## Components NOT Modified

| File | Reason |
|---|---|
| `SessionState` | MCP state lives in `McpSessionState` — no coupling |
| `pom.xml` | `spring-ai-starter-mcp-client:1.1.4` already on classpath via Embabel 0.3.5 |
| `executeSingleTool()` | Built-in tool execution unchanged — positional args preserved |

## Dependencies

Already on classpath (no changes to `pom.xml`):
- `org.springframework.ai:spring-ai-starter-mcp-client:1.1.4` (via Embabel 0.3.5)
- `io.modelcontextprotocol.sdk:mcp:0.17.0` (transitive)

## Configuration Properties

```properties
# MCP client
assistant.mcp.enabled=true
assistant.mcp.servers-configuration=          # optional override path to JSON config
assistant.mcp.tool-description-budget=3000    # max chars for MCP tool descriptions in prompt
assistant.mcp.confirm-destructive=true        # prompt before destructive MCP tool calls
assistant.mcp.request-timeout=20s             # per-tool-call timeout
```

## Testing

- Spock tests for all new components
- `McpToolRegistry`, `ToolCallDispatcher`, and `McpToolExecutor` are the most critical to test
- `ToolCallParser` MCP pattern tests including malformed JSON cases
- Integration tests using a mock MCP server (the Java SDK includes `MockMcpTransport` and similar test utilities)
- Manual testing with a real MCP server (e.g. `@modelcontextprotocol/server-filesystem`)
- Crash recovery tested by killing a server process mid-session
