# MCP Client Support for LCA

**Date:** 2026-06-09
**Status:** Approved

## Goal

Add Model Context Protocol (MCP) client support to LCA so that MCP servers developed for Claude (and other MCP-compatible hosts) work out of the box. LCA becomes an MCP client that can discover and invoke tools, read resources, and use prompt templates from any stdio-based MCP server.

## Decisions

- **Scope:** Full MCP client — tools, resources, and prompts
- **Transport:** stdio only (matches Claude Desktop / Claude Code)
- **Config format:** Claude-compatible JSON (`mcpServers` block)
- **Library:** Spring AI MCP Client Starter (`spring-ai-starter-mcp-client`)
- **Tool presentation:** Hybrid — user-guided activation with context-based autoselect as default
- **Internal representation:** All tool calls (built-in and MCP) normalised to a `StandardToolCall` JSON format before execution

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

### 2. MCP Client Integration Layer

**`McpToolRegistry`** — central Spring `@Component` wrapping `List<McpSyncClient>`.

Responsibilities:
- Registry of connected servers and their capabilities (tools, resources, prompts)
- Cached listings with TTL, refreshed on change notifications (`toolsChangeConsumer`, `resourcesChangeConsumer`, `promptsChangeConsumer`)
- Methods: `listTools(serverName?)`, `callTool(serverName, toolName, args)`, `listResources(serverName?)`, `readResource(uri)`, `listPrompts(serverName?)`, `getPrompt(serverName, promptName, args)`
- Tracks per-session activation state (auto vs manual, which servers active)
- Implements `McpToolFilter` to expose only tools from active servers
- Health tracking — marks crashed servers as unhealthy

**Server activation model:**
- Default: autoselect mode (context-based curation)
- `/mcp use <server>` — explicitly activate, switches session to manual mode
- `/mcp stop <server>` — deactivate a server
- `/mcp autoselect` — return to autoselect mode, clear manual overrides
- `/mcp status` — show all servers with health and activation state

**Lifecycle:** Managed by Spring AI starter — child processes start on init, graceful shutdown on close.

### 3. Tool Presentation to LLM

LCA uses text-based tool calling (regex parsing of LLM output via `ToolCallParser`). MCP tools are presented to the LLM in the system prompt and parsed from output the same way.

**Unified tool call flow:**
```
LLM text output
  |
  +-> ToolCallParser (regex) --> StandardToolCall (JSON)
                                       |
                                       +-> Built-in executor (writeFile, replace, etc.)
                                       +-> MCP executor (callTool via McpSyncClient)
```

**`StandardToolCall`** — canonical class for all tool calls:
```groovy
@Canonical
class StandardToolCall {
  String source      // "builtin" or "mcp"
  String serverName  // null for built-in, "bq" for MCP
  String toolName    // "writeFile" or "query"
  Map<String, Object> arguments  // JSON-style args
}
```

**`McpToolPromptBuilder`** generates tool descriptions for the system prompt:
```
Available MCP tools:

mcp_bq_query(sql, dry_run?, max_rows?) - Execute a SQL query against BigQuery
  - sql (string, required): The SQL query
  - dry_run (boolean, optional): Validate without executing
  - max_rows (integer, optional): Max rows to return (default 100)
```

**`McpToolExecutor`** validates arguments against JSON schema, invokes `McpSyncClient.callTool()`, and formats results as text.

**`ToolCallDispatcher`** routes `StandardToolCall` to built-in or MCP executors.

**`ToolCallParser` changes:** Output becomes `List<StandardToolCall>`. New dynamic pattern matches `mcp_<server>_<tool>({...})` syntax — MCP tool arguments use inline JSON: `mcp_bq_query({"sql": "SELECT count(*) FROM t", "max_rows": 10})`. Built-in tools retain their existing positional argument syntax.

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
| `/mcp tools [server]` | List available tools |
| `/mcp call <tool> [args...]` | Directly invoke a tool (bypass LLM) |
| `/mcp resources [server]` | List resources |
| `/mcp read <uri>` | Read a resource |
| `/mcp prompts [server]` | List prompt templates |
| `/mcp prompt <name> [args...]` | Fetch and use a prompt |

Implemented in `McpCommands` component, routed from `CommandExecutor`.

JLine tab completion for server names, tool names, and resource URIs.

`IntentRouterAgent` gets `/mcp` added to allowed commands for natural language routing.

### 6. Autoselect / Context-Based Tool Curation

Default mode — intelligently selects which MCP tools to present based on intent.

**Mechanism:**
1. `IntentRouterAgent` extended with a `domain` field in its output (`data`, `code`, `infrastructure`, `communication`, `general`)
2. Each MCP server gets a domain tag at discovery time, inferred from tool descriptions:
   - Tools mentioning "query", "SQL", "table", "schema" -> `data`
   - Tools mentioning "file", "read", "write", "directory" -> `code`
   - Tools mentioning "deploy", "workspace", "terraform" -> `infrastructure`
   - Tools mentioning "message", "channel", "send" -> `communication`
   - Fallback: `general`
3. When intent domain matches server domain, that server's tools are included in the system prompt
4. `ToolCallDispatcher` rejects tool calls for tools that weren't presented

Domain tagging is cached at discovery time (no per-request cost). Intent domain classification piggybacks on the existing intent router call.

## New Components

| Component | Path | Responsibility |
|---|---|---|
| `McpConfigLoader` | `mcp/McpConfigLoader.groovy` | Read Claude-format configs, merge, feed to starter |
| `McpToolRegistry` | `mcp/McpToolRegistry.groovy` | Central registry, caching, activation state, `McpToolFilter` |
| `McpToolPromptBuilder` | `mcp/McpToolPromptBuilder.groovy` | Generate tool/resource descriptions for system prompts |
| `McpToolExecutor` | `mcp/McpToolExecutor.groovy` | Validate args, invoke `callTool()`, format results |
| `StandardToolCall` | `tools/StandardToolCall.groovy` | Canonical class for unified tool call representation |
| `ToolCallDispatcher` | `tools/ToolCallDispatcher.groovy` | Route `StandardToolCall` to built-in or MCP executor |
| `McpCommands` | `shell/McpCommands.groovy` | All `/mcp` subcommands |

## Modified Components

| File | Change |
|---|---|
| `ToolCallParser` | Output `List<StandardToolCall>`, add MCP tool call pattern |
| `SessionState` | Add `Set<String> activeMcpServers`, `McpActivationMode` (auto/manual) |
| `CommandExecutor` | Route `/mcp` to `McpCommands` |
| `IntentRouterAgent` | Add `domain` field to output, add `/mcp` to allowed commands |
| `CodingAssistantAgent` | Inject `McpToolPromptBuilder` for MCP tool descriptions in system prompts |
| `ContextBudgetManager` | Reserve budget for MCP tool descriptions |
| `application.properties` | Add `assistant.mcp.*` properties |
| `pom.xml` | Add `spring-ai-starter-mcp-client` + Spring AI BOM |

## Dependencies

```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

Spring AI BOM for version management — needs compatibility check with Embabel's Spring AI transitive dependencies.

## Testing

- Spock tests for all new components
- `McpToolRegistry` and `ToolCallDispatcher` are the most critical to test
- Integration tests using a mock MCP server (Java SDK includes test utilities)
- Manual testing with a real MCP server (e.g. `@modelcontextprotocol/server-filesystem`)
