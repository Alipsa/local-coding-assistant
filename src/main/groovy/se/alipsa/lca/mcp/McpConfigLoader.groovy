package se.alipsa.lca.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Reads Claude-format JSON config files, merges them (later overrides earlier — full replacement,
 * no partial merging), and writes a consolidated file for the Spring AI starter.
 */
@Slf4j
@CompileStatic
class McpConfigLoader {

  @Canonical
  @CompileStatic
  static class ServerConfig {
    String command
    List<String> args
    Map<String, String> env
  }

  private final List<String> configPaths
  private final String outputDir
  private final ObjectMapper objectMapper

  /**
   * Creates a new McpConfigLoader.
   *
   * @param configPaths List of config file paths to read in order (later overrides earlier)
   * @param outputDir Directory where consolidated config will be written
   */
  McpConfigLoader(List<String> configPaths, String outputDir) {
    this.configPaths = configPaths
    this.outputDir = outputDir
    this.objectMapper = new ObjectMapper()
  }

  /**
   * Loads and merges server configurations from all config files.
   * Later files override earlier ones completely for the same server name.
   * Missing files are skipped with a debug log.
   *
   * @return LinkedHashMap of server configurations (preserves insertion order)
   */
  Map<String, ServerConfig> loadServers() {
    Map<String, ServerConfig> merged = new LinkedHashMap<>()

    for (String configPath : configPaths) {
      Path path = resolvePath(configPath)

      if (!Files.exists(path)) {
        log.debug("Config file not found, skipping: {}", configPath)
        continue
      }

      try {
        Map<String, Object> config = objectMapper.readValue(path.toFile(), Map)
        Map<String, Map<String, Object>> mcpServers = config.mcpServers as Map<String, Map<String, Object>>

        if (mcpServers) {
          mcpServers.each { String serverName, Map<String, Object> serverData ->
            ServerConfig serverConfig = new ServerConfig(
              command: serverData.command as String,
              args: serverData.args as List<String> ?: [],
              env: serverData.env as Map<String, String> ?: [:]
            )
            merged[serverName] = serverConfig
            log.debug("Loaded server '{}' from {}", serverName, configPath)
          }
        }
      } catch (Exception e) {
        log.warn("Failed to read config from {}: {}", configPath, e.message)
      }
    }

    return merged
  }

  /**
   * Loads servers and writes consolidated config to {outputDir}/mcp-consolidated.json.
   * Creates parent directories if needed.
   *
   * @return Path to the written consolidated config file
   */
  Path writeConsolidatedConfig() {
    Map<String, ServerConfig> servers = loadServers()

    Path outputPath = Paths.get(outputDir, "mcp-consolidated.json")
    Files.createDirectories(outputPath.parent)

    Map<String, Object> output = [
      mcpServers: servers.collectEntries { String name, ServerConfig config ->
        [
          name,
          [
            command: config.command,
            args   : config.args,
            env    : config.env
          ]
        ]
      }
    ]

    objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), output)
    log.info("Wrote consolidated config to: {}", outputPath)

    return outputPath
  }

  /**
   * Returns default config paths to check in priority order.
   * If overridePath is provided, it's checked first.
   *
   * @param overridePath Optional override path (checked first if provided)
   * @return List of config paths to check
   */
  static List<String> defaultConfigPaths(String overridePath) {
    List<String> paths = []

    if (overridePath) {
      paths.add(overridePath)
    }

    String home = System.getProperty("user.home")
    paths.add("${home}/.lca/mcp-servers.json".toString())
    paths.add("${home}/.claude/settings.json".toString())

    // macOS Claude desktop config
    if (System.getProperty("os.name").toLowerCase().contains("mac")) {
      paths.add("${home}/Library/Application Support/Claude/claude_desktop_config.json".toString())
    }

    return paths
  }

  private static Path resolvePath(String pathString) {
    String expanded = pathString.replaceFirst("^~", System.getProperty("user.home"))
    return Paths.get(expanded)
  }
}
