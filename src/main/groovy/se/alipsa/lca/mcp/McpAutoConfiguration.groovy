package se.alipsa.lca.mcp

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import jakarta.annotation.PostConstruct
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration class that wires MCP clients into the McpToolRegistry.
 * Registers all clients provided by the Spring AI MCP starter, falling back to
 * auto-generated names if server info is unavailable.
 */
@Slf4j
@Configuration
@CompileStatic
@ConditionalOnProperty(name = 'assistant.mcp.enabled', havingValue = 'true', matchIfMissing = true)
class McpAutoConfiguration {

  private final McpToolRegistry registry
  private final List<McpSyncClient> clients

  McpAutoConfiguration(McpToolRegistry registry, List<McpSyncClient> clients) {
    this.registry = registry
    this.clients = clients
  }

  @PostConstruct
  void registerClients() {
    if (clients == null || clients.isEmpty()) {
      log.info('No MCP servers configured.')
      return
    }

    int registered = 0
    for (int i = 0; i < clients.size(); i++) {
      McpSyncClient client = clients.get(i)
      String name = getServerName(client, i)
      registry.registerServer(name, client)
      registered++
    }

    log.info('Registered {} MCP server(s).', registered)
  }

  /**
   * Attempts to get the server info name via client.getServerInfo().
   * Falls back to "server-N" if that fails.
   */
  private String getServerName(McpSyncClient client, int index) {
    try {
      McpSchema.Implementation impl = client.getServerInfo()
      if (impl != null && impl.name() != null && !impl.name().isBlank()) {
        return impl.name()
      }
    } catch (Exception e) {
      log.debug('Failed to get server info for client {}: {}', index, e.message)
    }
    return "server-${index}"
  }
}
