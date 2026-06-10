package se.alipsa.lca.shell

import io.modelcontextprotocol.spec.McpSchema
import se.alipsa.lca.mcp.McpSessionState
import se.alipsa.lca.mcp.McpToolRegistry
import spock.lang.Specification

class McpCommandsSpec extends Specification {

  McpToolRegistry registry = Mock(McpToolRegistry)
  McpSessionState sessionState = new McpSessionState()
  McpCommands mcpCommands

  def setup() {
    mcpCommands = new McpCommands(registry, sessionState)
  }

  def "status shows healthy server"() {
    given:
    registry.getServerNames() >> (["bq"] as Set)
    registry.getServerHealth() >> ["bq": new McpToolRegistry.ServerHealth(true, null)]
    McpSchema.Tool tool = new McpSchema.Tool("query", null, "Run a query", null, null, null, null)
    registry.listTools("bq") >> [tool]

    when:
    String result = mcpCommands.execute("status", "", "default")

    then:
    result.contains("MCP servers (mode: AUTO)")
    result.contains("bq: healthy, 1 tool(s)")
  }

  def "status shows unhealthy server with reason"() {
    given:
    registry.getServerNames() >> (["bq"] as Set)
    registry.getServerHealth() >> ["bq": new McpToolRegistry.ServerHealth(false, "connection timeout")]
    registry.listTools("bq") >> []

    when:
    String result = mcpCommands.execute("status", "", "default")

    then:
    result.contains("bq: unhealthy, 0 tool(s)")
  }

  def "status shows active server marker"() {
    given:
    sessionState.activate("default", "fs")
    registry.getServerNames() >> (["bq", "fs"] as Set)
    registry.getServerHealth() >> [
      "bq": new McpToolRegistry.ServerHealth(true, null),
      "fs": new McpToolRegistry.ServerHealth(true, null)
    ]
    registry.listTools("bq") >> []
    registry.listTools("fs") >> []

    when:
    String result = mcpCommands.execute("status", "", "default")

    then:
    result.contains("MCP servers (mode: MANUAL)")
    result.contains("fs:") && result.contains("[active]")
    !result.contains("bq:") || !result.substring(result.indexOf("bq:"), result.indexOf("\n", result.indexOf("bq:"))).contains("[active]")
  }

  def "use activates server and switches to manual mode"() {
    given:
    registry.getServerNames() >> (["bq"] as Set)

    when:
    String result = mcpCommands.execute("use", "bq", "default")

    then:
    result.contains("Activated server 'bq'")
    sessionState.getMode("default") == McpSessionState.McpActivationMode.MANUAL
    sessionState.isActive("default", "bq")
  }

  def "use rejects unknown server"() {
    given:
    registry.getServerNames() >> (["bq"] as Set)

    when:
    String result = mcpCommands.execute("use", "unknown", "default")

    then:
    result.contains("Unknown server: unknown")
  }

  def "stop deactivates server"() {
    given:
    sessionState.activate("default", "bq")

    when:
    String result = mcpCommands.execute("stop", "bq", "default")

    then:
    result.contains("Deactivated server 'bq'")
    !sessionState.isActive("default", "bq")
  }

  def "autoselect resets to auto mode"() {
    given:
    registry.getServerNames() >> (["bq"] as Set)
    sessionState.activate("default", "bq")

    when:
    String result = mcpCommands.execute("autoselect", "", "default")

    then:
    result.contains("Reset to AUTO mode")
    sessionState.getMode("default") == McpSessionState.McpActivationMode.AUTO
  }

  def "tools lists available tools"() {
    given:
    McpSchema.Tool tool1 = new McpSchema.Tool("query", null, "Run SQL", null, null, null, null)
    McpSchema.Tool tool2 = new McpSchema.Tool("list_tables", null, "List tables", null, null, null, null)
    registry.listTools("bq") >> [tool1, tool2]

    when:
    String result = mcpCommands.execute("tools", "bq", "default")

    then:
    result.contains("Tools (bq):")
    result.contains("- query: Run SQL")
    result.contains("- list_tables: List tables")
  }

  def "tools with no server lists all tools"() {
    given:
    McpSchema.Tool tool = new McpSchema.Tool("query", null, "Run SQL", null, null, null, null)
    registry.listTools(null) >> [tool]

    when:
    String result = mcpCommands.execute("tools", "", "default")

    then:
    result.contains("Tools:")
    result.contains("- query: Run SQL")
  }

  def "tools reports empty when none found"() {
    given:
    registry.listTools("bq") >> []

    when:
    String result = mcpCommands.execute("tools", "bq", "default")

    then:
    result == "No tools found for server 'bq'."
  }

  def "call returns usage when no args"() {
    when:
    String result = mcpCommands.execute("call", "", "default")

    then:
    result.contains("Usage:")
  }

  def "call rejects invalid format without dot"() {
    when:
    String result = mcpCommands.execute("call", "nodot", "default")

    then:
    result.contains("Invalid format")
  }

  def "resources lists available resources"() {
    given:
    McpSchema.Resource resource = new McpSchema.Resource("file:///data.csv", "data.csv", "A CSV file", null, null)
    registry.listResources("fs") >> [resource]

    when:
    String result = mcpCommands.execute("resources", "fs", "default")

    then:
    result.contains("Resources (fs):")
    result.contains("- file:///data.csv (data.csv): A CSV file")
  }

  def "prompts lists available prompts"() {
    given:
    McpSchema.Prompt prompt = new McpSchema.Prompt("summarise", "Summarise text", [])
    registry.listPrompts("bq") >> [prompt]

    when:
    String result = mcpCommands.execute("prompts", "bq", "default")

    then:
    result.contains("Prompts (bq):")
    result.contains("- summarise: Summarise text")
  }

  def "prompt returns stub message"() {
    when:
    String result = mcpCommands.execute("prompt", "bq.summarise", "default")

    then:
    result == "Prompt fetching not yet implemented."
  }

  def "restart returns stub message"() {
    when:
    String result = mcpCommands.execute("restart", "bq", "default")

    then:
    result == "Server restart not yet implemented."
  }

  def "unknown subcommand returns help"() {
    when:
    String result = mcpCommands.execute("banana", "", "default")

    then:
    result.contains("Available /mcp subcommands:")
    result.contains("status")
    result.contains("use")
    result.contains("tools")
    result.contains("call")
  }

  def "status shows none configured when no servers"() {
    given:
    registry.getServerNames() >> ([] as Set)
    registry.getServerHealth() >> [:]

    when:
    String result = mcpCommands.execute("status", "", "default")

    then:
    result.contains("(none configured)")
  }
}
