package se.alipsa.lca.mcp

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry wrapping MCP sync clients. Manages tool/resource/prompt listings,
 * health tracking, and server filtering.
 */
@Slf4j
@Component
@CompileStatic
class McpToolRegistry {

  @Canonical
  @CompileStatic
  static class ServerHealth {
    boolean healthy
    String reason
  }

  private final McpSessionState sessionState
  private final Map<String, McpSyncClient> clients = new LinkedHashMap<>()
  private final Map<String, List<McpSchema.Tool>> toolCache = new ConcurrentHashMap<>()
  private final Map<String, List<McpSchema.Resource>> resourceCache = new ConcurrentHashMap<>()
  private final Map<String, List<McpSchema.Prompt>> promptCache = new ConcurrentHashMap<>()
  private final Map<String, ServerHealth> healthMap = new ConcurrentHashMap<>()

  McpToolRegistry(McpSessionState sessionState) {
    this.sessionState = sessionState
  }

  /**
   * Registers a server client, marks it healthy, and refreshes its capabilities.
   */
  void registerServer(String name, McpSyncClient client) {
    clients.put(name, client)
    markHealthy(name)
    refreshCapabilities(name, client)
  }

  /**
   * Refreshes cached capabilities (tools, resources, prompts) for a server.
   * Each call is try-caught independently since a server might support tools but not resources.
   */
  private void refreshCapabilities(String name, McpSyncClient client) {
    try {
      McpSchema.ListToolsResult toolsResult = client.listTools()
      toolCache.put(name, toolsResult.tools() ?: [])
      log.debug("Loaded {} tools from server '{}'", toolsResult.tools()?.size() ?: 0, name)
    } catch (Exception e) {
      log.debug("Server '{}' does not support tools: {}", name, e.message)
      toolCache.put(name, [])
    }

    try {
      McpSchema.ListResourcesResult resourcesResult = client.listResources()
      resourceCache.put(name, resourcesResult.resources() ?: [])
      log.debug("Loaded {} resources from server '{}'", resourcesResult.resources()?.size() ?: 0, name)
    } catch (Exception e) {
      log.debug("Server '{}' does not support resources: {}", name, e.message)
      resourceCache.put(name, [])
    }

    try {
      McpSchema.ListPromptsResult promptsResult = client.listPrompts()
      promptCache.put(name, promptsResult.prompts() ?: [])
      log.debug("Loaded {} prompts from server '{}'", promptsResult.prompts()?.size() ?: 0, name)
    } catch (Exception e) {
      log.debug("Server '{}' does not support prompts: {}", name, e.message)
      promptCache.put(name, [])
    }
  }

  /**
   * Lists tools. If serverName is null, returns all tools from healthy servers.
   * If specified, returns that server's tools (empty list if unhealthy).
   */
  List<McpSchema.Tool> listTools(String serverName) {
    if (serverName == null) {
      List<McpSchema.Tool> all = []
      toolCache.each { String name, List<McpSchema.Tool> tools ->
        if (isHealthy(name)) {
          all.addAll(tools)
        }
      }
      return all
    }
    if (!isHealthy(serverName)) {
      return []
    }
    return toolCache.getOrDefault(serverName, [])
  }

  /**
   * Finds which server owns a given tool name.
   *
   * @return the server name, or null if not found
   */
  String findServerForTool(String toolName) {
    for (Map.Entry<String, List<McpSchema.Tool>> entry : toolCache.entrySet()) {
      for (McpSchema.Tool tool : entry.value) {
        if (tool.name() == toolName) {
          return entry.key
        }
      }
    }
    return null
  }

  /**
   * Calls a tool on the specified server.
   *
   * @throws IllegalArgumentException if server does not exist or is unhealthy
   */
  McpSchema.CallToolResult callTool(String serverName, String toolName, Map<String, Object> args) {
    McpSyncClient client = getHealthyClient(serverName)
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, args)
    return client.callTool(request)
  }

  /**
   * Lists resources. If serverName is null, returns all resources from healthy servers.
   * If specified, returns that server's resources (empty list if unhealthy).
   */
  List<McpSchema.Resource> listResources(String serverName) {
    if (serverName == null) {
      List<McpSchema.Resource> all = []
      resourceCache.each { String name, List<McpSchema.Resource> resources ->
        if (isHealthy(name)) {
          all.addAll(resources)
        }
      }
      return all
    }
    if (!isHealthy(serverName)) {
      return []
    }
    return resourceCache.getOrDefault(serverName, [])
  }

  /**
   * Reads a resource by URI, trying each healthy server until one succeeds.
   *
   * @throws IllegalStateException if no server can read the resource
   */
  McpSchema.ReadResourceResult readResource(String uri) {
    McpSchema.ReadResourceRequest request = new McpSchema.ReadResourceRequest(uri)
    for (Map.Entry<String, McpSyncClient> entry : clients.entrySet()) {
      if (!isHealthy(entry.key)) {
        continue
      }
      try {
        return entry.value.readResource(request)
      } catch (Exception e) {
        log.debug("Server '{}' could not read resource '{}': {}", entry.key, uri, e.message)
      }
    }
    throw new IllegalStateException("No server could read resource: ${uri}")
  }

  /**
   * Lists prompts. If serverName is null, returns all prompts from healthy servers.
   * If specified, returns that server's prompts (empty list if unhealthy).
   */
  List<McpSchema.Prompt> listPrompts(String serverName) {
    if (serverName == null) {
      List<McpSchema.Prompt> all = []
      promptCache.each { String name, List<McpSchema.Prompt> prompts ->
        if (isHealthy(name)) {
          all.addAll(prompts)
        }
      }
      return all
    }
    if (!isHealthy(serverName)) {
      return []
    }
    return promptCache.getOrDefault(serverName, [])
  }

  /**
   * Gets a prompt from the specified server.
   *
   * @throws IllegalArgumentException if server does not exist or is unhealthy
   */
  McpSchema.GetPromptResult getPrompt(String serverName, String promptName, Map<String, String> args) {
    McpSyncClient client = getHealthyClient(serverName)
    Map<String, Object> arguments = args != null ? new LinkedHashMap<String, Object>(args) : null
    McpSchema.GetPromptRequest request = new McpSchema.GetPromptRequest(promptName, arguments)
    return client.getPrompt(request)
  }

  /**
   * Marks a server as unhealthy with a reason.
   */
  void markUnhealthy(String serverName, String reason) {
    healthMap.put(serverName, new ServerHealth(healthy: false, reason: reason))
    log.warn("Server '{}' marked unhealthy: {}", serverName, reason)
  }

  /**
   * Marks a server as healthy.
   */
  void markHealthy(String serverName) {
    healthMap.put(serverName, new ServerHealth(healthy: true, reason: null))
    log.debug("Server '{}' marked healthy", serverName)
  }

  /**
   * Returns all registered server names.
   */
  Set<String> getServerNames() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(clients.keySet()))
  }

  /**
   * Returns health status for all servers.
   */
  Map<String, ServerHealth> getServerHealth() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(healthMap))
  }

  /**
   * Returns whether a server is healthy.
   */
  boolean isHealthy(String serverName) {
    ServerHealth health = healthMap.get(serverName)
    return health != null && health.healthy
  }

  /**
   * Retrieves the client for a server, validating it exists and is healthy.
   *
   * @throws IllegalArgumentException if server does not exist or is unhealthy
   */
  private McpSyncClient getHealthyClient(String serverName) {
    McpSyncClient client = clients.get(serverName)
    if (client == null) {
      throw new IllegalArgumentException("Unknown server: ${serverName}")
    }
    if (!isHealthy(serverName)) {
      String reason = healthMap.get(serverName)?.reason ?: 'unknown'
      throw new IllegalArgumentException("Server '${serverName}' is unhealthy: ${reason}")
    }
    return client
  }
}
