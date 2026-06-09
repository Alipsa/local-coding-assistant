package se.alipsa.lca.mcp

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class McpConfigLoaderSpec extends Specification {

  @TempDir
  Path tempDir

  def "loads single config file"() {
    given: "a config file with two servers"
    Path configFile = tempDir.resolve("config1.json")
    Files.writeString(configFile, '''
      {
        "mcpServers": {
          "server1": {
            "command": "node",
            "args": ["index.js"],
            "env": {"KEY1": "VALUE1"}
          },
          "server2": {
            "command": "python",
            "args": ["-m", "server"],
            "env": {}
          }
        }
      }
    ''')

    and: "a loader with this config"
    def loader = new McpConfigLoader([configFile.toString()], tempDir.toString())

    when: "loading servers"
    Map<String, McpConfigLoader.ServerConfig> servers = loader.loadServers()

    then: "both servers are loaded"
    servers.size() == 2
    servers.server1.command == "node"
    servers.server1.args == ["index.js"]
    servers.server1.env == ["KEY1": "VALUE1"]
    servers.server2.command == "python"
    servers.server2.args == ["-m", "server"]
    servers.server2.env == [:]
  }

  def "merges multiple configs with later overriding earlier"() {
    given: "first config file"
    Path config1 = tempDir.resolve("config1.json")
    Files.writeString(config1, '''
      {
        "mcpServers": {
          "server1": {
            "command": "old-command",
            "args": ["old-arg"],
            "env": {"OLD": "value"}
          },
          "server2": {
            "command": "preserved",
            "args": [],
            "env": {}
          }
        }
      }
    ''')

    and: "second config file that overrides server1"
    Path config2 = tempDir.resolve("config2.json")
    Files.writeString(config2, '''
      {
        "mcpServers": {
          "server1": {
            "command": "new-command",
            "args": ["new-arg1", "new-arg2"],
            "env": {"NEW": "value"}
          },
          "server3": {
            "command": "added",
            "args": ["arg"],
            "env": {"KEY": "VAL"}
          }
        }
      }
    ''')

    and: "a loader with both configs"
    def loader = new McpConfigLoader(
      [config1.toString(), config2.toString()],
      tempDir.toString()
    )

    when: "loading servers"
    Map<String, McpConfigLoader.ServerConfig> servers = loader.loadServers()

    then: "server1 is fully replaced by config2"
    servers.size() == 3
    servers.server1.command == "new-command"
    servers.server1.args == ["new-arg1", "new-arg2"]
    servers.server1.env == ["NEW": "value"]
    !servers.server1.env.containsKey("OLD")

    and: "server2 is preserved from config1"
    servers.server2.command == "preserved"

    and: "server3 is added from config2"
    servers.server3.command == "added"
    servers.server3.env == ["KEY": "VAL"]
  }

  def "skips missing files without error"() {
    given: "a config path that exists and one that doesn't"
    Path existingConfig = tempDir.resolve("exists.json")
    Files.writeString(existingConfig, '''
      {
        "mcpServers": {
          "server1": {
            "command": "test",
            "args": [],
            "env": {}
          }
        }
      }
    ''')
    String missingPath = tempDir.resolve("missing.json").toString()

    and: "a loader with both paths"
    def loader = new McpConfigLoader(
      [missingPath, existingConfig.toString()],
      tempDir.toString()
    )

    when: "loading servers"
    Map<String, McpConfigLoader.ServerConfig> servers = loader.loadServers()

    then: "only the existing config is loaded"
    servers.size() == 1
    servers.server1.command == "test"
    noExceptionThrown()
  }

  def "writes consolidated config"() {
    given: "two config files"
    Path config1 = tempDir.resolve("config1.json")
    Files.writeString(config1, '''
      {
        "mcpServers": {
          "server1": {
            "command": "cmd1",
            "args": ["a"],
            "env": {"E1": "v1"}
          }
        }
      }
    ''')

    Path config2 = tempDir.resolve("config2.json")
    Files.writeString(config2, '''
      {
        "mcpServers": {
          "server2": {
            "command": "cmd2",
            "args": ["b", "c"],
            "env": {}
          }
        }
      }
    ''')

    and: "a loader with both configs"
    Path outputDir = tempDir.resolve("output")
    def loader = new McpConfigLoader(
      [config1.toString(), config2.toString()],
      outputDir.toString()
    )

    when: "writing consolidated config"
    Path outputPath = loader.writeConsolidatedConfig()

    then: "output file is created"
    Files.exists(outputPath)
    outputPath.toString().endsWith("mcp-consolidated.json")

    and: "content is correct"
    String content = Files.readString(outputPath)
    content.contains('"server1"')
    content.contains('"cmd1"')
    content.contains('"server2"')
    content.contains('"cmd2"')
    content.contains('"mcpServers"')
  }

  def "handles empty mcpServers block"() {
    given: "a config with empty mcpServers"
    Path configFile = tempDir.resolve("empty.json")
    Files.writeString(configFile, '''
      {
        "mcpServers": {}
      }
    ''')

    and: "a loader with this config"
    def loader = new McpConfigLoader([configFile.toString()], tempDir.toString())

    when: "loading servers"
    Map<String, McpConfigLoader.ServerConfig> servers = loader.loadServers()

    then: "an empty map is returned"
    servers.isEmpty()

    when: "writing consolidated config"
    Path output = loader.writeConsolidatedConfig()

    then: "file is created with empty mcpServers"
    Files.exists(output)
    Files.readString(output).contains('"mcpServers"')
  }

  def "handles missing mcpServers key"() {
    given: "a config without mcpServers key"
    Path configFile = tempDir.resolve("no-key.json")
    Files.writeString(configFile, '''
      {
        "someOtherKey": "value"
      }
    ''')

    and: "a loader with this config"
    def loader = new McpConfigLoader([configFile.toString()], tempDir.toString())

    when: "loading servers"
    Map<String, McpConfigLoader.ServerConfig> servers = loader.loadServers()

    then: "an empty map is returned"
    servers.isEmpty()
  }

  def "defaultConfigPaths includes override path first"() {
    when: "getting default paths with an override"
    List<String> paths = McpConfigLoader.defaultConfigPaths("/custom/path.json")

    then: "override is first"
    paths[0] == "/custom/path.json"

    and: "standard paths follow"
    paths.any { it.contains(".lca/mcp-servers.json") }
    paths.any { it.contains(".claude/settings.json") }
  }

  def "defaultConfigPaths works without override"() {
    when: "getting default paths without override"
    List<String> paths = McpConfigLoader.defaultConfigPaths(null)

    then: "standard paths are returned"
    paths.size() >= 2
    paths.any { it.contains(".lca/mcp-servers.json") }
    paths.any { it.contains(".claude/settings.json") }
  }

  def "preserves insertion order from config files"() {
    given: "a config with multiple servers in specific order"
    Path configFile = tempDir.resolve("ordered.json")
    Files.writeString(configFile, '''
      {
        "mcpServers": {
          "zebra": {"command": "z", "args": [], "env": {}},
          "apple": {"command": "a", "args": [], "env": {}},
          "middle": {"command": "m", "args": [], "env": {}}
        }
      }
    ''')

    and: "a loader"
    def loader = new McpConfigLoader([configFile.toString()], tempDir.toString())

    when: "loading servers"
    Map<String, McpConfigLoader.ServerConfig> servers = loader.loadServers()

    then: "order is preserved from JSON (implementation-dependent, but LinkedHashMap should preserve)"
    servers.keySet().toList() == ["zebra", "apple", "middle"]
  }
}
