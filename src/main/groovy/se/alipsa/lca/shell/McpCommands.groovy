package se.alipsa.lca.shell

import com.fasterxml.jackson.databind.ObjectMapper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component
import se.alipsa.lca.mcp.McpSessionState
import se.alipsa.lca.mcp.McpToolRegistry

/**
 * Handles all /mcp slash commands — status, use, stop, autoselect, tools, call,
 * resources, read, prompts, prompt, and restart.
 */
@Slf4j
@Component
@CompileStatic
class McpCommands {

  private final McpToolRegistry registry
  private final McpSessionState sessionState
  private final ObjectMapper objectMapper = new ObjectMapper()

  McpCommands(McpToolRegistry registry, McpSessionState sessionState) {
    this.registry = registry
    this.sessionState = sessionState
  }

  /**
   * Main entry point for /mcp subcommands.
   *
   * @param subcommand the subcommand name (e.g. "status", "use", "tools")
   * @param args remaining arguments after the subcommand
   * @param sessionId the current session ID
   * @return formatted output string
   */
  String execute(String subcommand, String args, String sessionId) {
    switch (subcommand) {
      case "status":
        return executeStatus(sessionId)
      case "use":
        return executeUse(args, sessionId)
      case "stop":
        return executeStop(args, sessionId)
      case "autoselect":
        return executeAutoselect(sessionId)
      case "tools":
        return executeTools(args)
      case "call":
        return executeCall(args)
      case "resources":
        return executeResources(args)
      case "read":
        return executeRead(args)
      case "prompts":
        return executePrompts(args)
      case "prompt":
        return "Prompt fetching not yet implemented."
      case "restart":
        return "Server restart not yet implemented."
      default:
        return helpText()
    }
  }

  private String executeStatus(String sessionId) {
    McpSessionState.McpActivationMode mode = sessionState.getMode(sessionId)
    Set<String> activeServers = sessionState.getActiveServers(sessionId)
    Map<String, McpToolRegistry.ServerHealth> health = registry.getServerHealth()
    Set<String> serverNames = registry.getServerNames()

    StringBuilder sb = new StringBuilder()
    sb.append("MCP servers (mode: ${mode}):")

    if (serverNames.isEmpty()) {
      sb.append("\n  (none configured)")
      return sb.toString()
    }

    for (String name : serverNames) {
      McpToolRegistry.ServerHealth sh = health.get(name)
      String status = (sh != null && sh.healthy) ? "healthy" : "unhealthy"
      int toolCount = registry.listTools(name).size()
      boolean active = activeServers.contains(name)
      sb.append("\n  ${name}: ${status}, ${toolCount} tool(s)")
      if (active) {
        sb.append(" [active]")
      }
    }

    return sb.toString()
  }

  private String executeUse(String args, String sessionId) {
    String serverName = args?.trim()
    if (!serverName) {
      return "Usage: /mcp use <server-name>"
    }
    if (!registry.getServerNames().contains(serverName)) {
      return "Unknown server: ${serverName}"
    }
    sessionState.activate(sessionId, serverName)
    return "Activated server '${serverName}' for session '${sessionId}'."
  }

  private String executeStop(String args, String sessionId) {
    String serverName = args?.trim()
    if (!serverName) {
      return "Usage: /mcp stop <server-name>"
    }
    sessionState.deactivate(sessionId, serverName)
    return "Deactivated server '${serverName}' for session '${sessionId}'."
  }

  private String executeAutoselect(String sessionId) {
    sessionState.resetToAuto(sessionId)
    return "Reset to AUTO mode for session '${sessionId}'."
  }

  private String executeTools(String args) {
    String serverName = args?.trim() ?: null
    List<McpSchema.Tool> tools = registry.listTools(serverName)
    if (tools.isEmpty()) {
      return serverName ? "No tools found for server '${serverName}'." : "No tools available."
    }

    StringBuilder sb = new StringBuilder()
    sb.append("Tools${serverName ? " (${serverName})" : ""}:")
    for (McpSchema.Tool tool : tools) {
      sb.append("\n  - ${tool.name()}")
      if (tool.description()) {
        sb.append(": ${tool.description()}")
      }
    }
    return sb.toString()
  }

  @SuppressWarnings('CatchException')
  private String executeCall(String args) {
    if (!args?.trim()) {
      return "Usage: /mcp call <server.tool> {json-args}"
    }

    // Split on first space to separate "server.tool" from "{json}"
    String trimmed = args.trim()
    int spaceIdx = trimmed.indexOf(' ')
    String serverToolPart = spaceIdx > 0 ? trimmed.substring(0, spaceIdx) : trimmed
    String jsonPart = spaceIdx > 0 ? trimmed.substring(spaceIdx + 1).trim() : ""

    // Parse server.tool
    String[] parts = serverToolPart.split("\\.", 2)
    if (parts.length < 2) {
      return "Invalid format. Expected: /mcp call <server>.<tool> {json-args}"
    }
    String serverName = parts[0]
    String toolName = parts[1]

    // Parse JSON args
    Map<String, Object> toolArgs = [:]
    if (jsonPart) {
      try {
        toolArgs = objectMapper.readValue(jsonPart, Map) as Map<String, Object>
      } catch (Exception e) {
        return "Invalid JSON arguments: ${e.message}"
      }
    }

    try {
      McpSchema.CallToolResult result = registry.callTool(serverName, toolName, toolArgs)
      return formatCallResult(result)
    } catch (Exception e) {
      return "Error calling ${serverName}.${toolName}: ${e.message}"
    }
  }

  private String executeResources(String args) {
    String serverName = args?.trim() ?: null
    List<McpSchema.Resource> resources = registry.listResources(serverName)
    if (resources.isEmpty()) {
      return serverName ? "No resources found for server '${serverName}'." : "No resources available."
    }

    StringBuilder sb = new StringBuilder()
    sb.append("Resources${serverName ? " (${serverName})" : ""}:")
    for (McpSchema.Resource resource : resources) {
      sb.append("\n  - ${resource.uri()}")
      if (resource.name()) {
        sb.append(" (${resource.name()})")
      }
      if (resource.description()) {
        sb.append(": ${resource.description()}")
      }
    }
    return sb.toString()
  }

  @SuppressWarnings('CatchException')
  private String executeRead(String args) {
    String uri = args?.trim()
    if (!uri) {
      return "Usage: /mcp read <resource-uri>"
    }

    try {
      McpSchema.ReadResourceResult result = registry.readResource(uri)
      StringBuilder sb = new StringBuilder()
      for (McpSchema.ResourceContents contents : result.contents()) {
        if (contents instanceof McpSchema.TextResourceContents) {
          sb.append(((McpSchema.TextResourceContents) contents).text())
        } else {
          sb.append(contents.toString())
        }
      }
      return sb.toString() ?: "(empty resource)"
    } catch (Exception e) {
      return "Error reading resource '${uri}': ${e.message}"
    }
  }

  private String executePrompts(String args) {
    String serverName = args?.trim() ?: null
    List<McpSchema.Prompt> prompts = registry.listPrompts(serverName)
    if (prompts.isEmpty()) {
      return serverName ? "No prompts found for server '${serverName}'." : "No prompts available."
    }

    StringBuilder sb = new StringBuilder()
    sb.append("Prompts${serverName ? " (${serverName})" : ""}:")
    for (McpSchema.Prompt prompt : prompts) {
      sb.append("\n  - ${prompt.name()}")
      if (prompt.description()) {
        sb.append(": ${prompt.description()}")
      }
    }
    return sb.toString()
  }

  private static String formatCallResult(McpSchema.CallToolResult result) {
    if (result.isError()) {
      StringBuilder sb = new StringBuilder("Error: ")
      for (McpSchema.Content content : result.content()) {
        if (content instanceof McpSchema.TextContent) {
          sb.append(((McpSchema.TextContent) content).text())
        }
      }
      return sb.toString()
    }

    StringBuilder sb = new StringBuilder()
    for (McpSchema.Content content : result.content()) {
      if (content instanceof McpSchema.TextContent) {
        sb.append(((McpSchema.TextContent) content).text())
      }
    }
    return sb.toString() ?: "(no output)"
  }

  private static String helpText() {
    return """\
Available /mcp subcommands:
  status      - Show all servers with health, tool count, activation state
  use <srv>   - Activate a server for this session (switch to MANUAL mode)
  stop <srv>  - Deactivate a server for this session
  autoselect  - Reset to AUTO mode
  tools [srv] - List available tools (optionally for a specific server)
  call <s.t> {json} - Call a tool: /mcp call bq.query {"sql": "SELECT 1"}
  resources [srv]   - List available resources
  read <uri>        - Read a resource by URI
  prompts [srv]     - List available prompts
  prompt            - Fetch a prompt (not yet implemented)
  restart           - Restart a server (not yet implemented)"""
  }
}
