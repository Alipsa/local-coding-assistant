package se.alipsa.lca.mcp

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
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

  @Value('${assistant.mcp.servers-config:}')
  private String serversConfigOverride

  McpAutoConfiguration(McpToolRegistry registry, List<McpSyncClient> clients) {
    this.registry = registry
    this.clients = clients
  }

  @Bean
  McpConfigLoader mcpConfigLoader() {
    return createConfigLoader()
  }

  @PostConstruct
  void registerClients() {
    McpConfigLoader loader = createConfigLoader()
    Map<String, McpConfigLoader.ServerConfig> discovered = loader.loadServers()

    if (!discovered.isEmpty()) {
      log.info('McpConfigLoader discovered {} server(s) in config files: {}',
        discovered.size(), discovered.keySet())
    }

    if (clients == null || clients.isEmpty()) {
      if (!discovered.isEmpty()) {
        log.warn('No MCP clients were auto-configured by the Spring AI starter, but McpConfigLoader ' +
          'found {} server(s) in config files. Set the property ' +
          '\'spring.ai.mcp.client.stdio.servers-configuration\' to point to your config file.',
          discovered.size())
      } else {
        log.info('No MCP servers configured.')
      }
      return
    }

    int registered = 0
    for (int i = 0; i < clients.size(); i++) {
      McpSyncClient client = clients.get(i)
      String name = getServerName(client, i)
      try {
        registry.registerServer(name, client)
        registered++
      } catch (Exception e) {
        log.warn("Failed to register MCP server '{}': {}", name, e.message)
      }
    }

    log.info('Registered {} MCP server(s).', registered)
  }

  private McpConfigLoader createConfigLoader() {
    List<String> paths = McpConfigLoader.defaultConfigPaths(serversConfigOverride)
    String tmpDir = System.getProperty('java.io.tmpdir')
    return new McpConfigLoader(paths, tmpDir)
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
