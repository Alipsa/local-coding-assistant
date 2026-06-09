package se.alipsa.lca.mcp

import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import spock.lang.Specification

class McpToolRegistrySpec extends Specification {

  McpToolRegistry registry
  McpSessionState sessionState

  def setup() {
    sessionState = new McpSessionState()
    registry = new McpToolRegistry(sessionState)
  }

  def "registers server and lists tools"() {
    given:
    McpSyncClient client = Mock(McpSyncClient)
    McpSchema.Tool tool = new McpSchema.Tool("test-tool", null, "A test tool", null, null, null, null)
    McpSchema.ListToolsResult toolsResult = new McpSchema.ListToolsResult([tool], null)
    McpSchema.ListResourcesResult resourcesResult = new McpSchema.ListResourcesResult([], null)
    McpSchema.ListPromptsResult promptsResult = new McpSchema.ListPromptsResult([], null)

    client.listTools() >> toolsResult
    client.listResources() >> resourcesResult
    client.listPrompts() >> promptsResult

    when:
    registry.registerServer("server-a", client)

    then:
    registry.listTools(null).size() == 1
    registry.listTools(null)[0].name() == "test-tool"
    registry.listTools("server-a").size() == 1
    registry.isHealthy("server-a")
  }

  def "marks server unhealthy and excludes its tools"() {
    given:
    McpSyncClient client = Mock(McpSyncClient)
    McpSchema.Tool tool = new McpSchema.Tool("my-tool", null, "desc", null, null, null, null)
    client.listTools() >> new McpSchema.ListToolsResult([tool], null)
    client.listResources() >> new McpSchema.ListResourcesResult([], null)
    client.listPrompts() >> new McpSchema.ListPromptsResult([], null)
    registry.registerServer("server-a", client)

    when:
    registry.markUnhealthy("server-a", "connection lost")

    then:
    !registry.isHealthy("server-a")
    registry.listTools(null).isEmpty()
    registry.listTools("server-a").isEmpty()
  }

  def "filters tools by server name"() {
    given:
    McpSyncClient clientA = Mock(McpSyncClient)
    McpSyncClient clientB = Mock(McpSyncClient)

    McpSchema.Tool toolA = new McpSchema.Tool("tool-a", null, "Tool A", null, null, null, null)
    McpSchema.Tool toolB = new McpSchema.Tool("tool-b", null, "Tool B", null, null, null, null)

    clientA.listTools() >> new McpSchema.ListToolsResult([toolA], null)
    clientA.listResources() >> new McpSchema.ListResourcesResult([], null)
    clientA.listPrompts() >> new McpSchema.ListPromptsResult([], null)

    clientB.listTools() >> new McpSchema.ListToolsResult([toolB], null)
    clientB.listResources() >> new McpSchema.ListResourcesResult([], null)
    clientB.listPrompts() >> new McpSchema.ListPromptsResult([], null)

    registry.registerServer("server-a", clientA)
    registry.registerServer("server-b", clientB)

    expect:
    registry.listTools("server-a").size() == 1
    registry.listTools("server-a")[0].name() == "tool-a"
    registry.listTools("server-b").size() == 1
    registry.listTools("server-b")[0].name() == "tool-b"
    registry.listTools(null).size() == 2
  }

  def "getServerNames returns all registered servers"() {
    given:
    McpSyncClient clientA = Mock(McpSyncClient)
    McpSyncClient clientB = Mock(McpSyncClient)

    clientA.listTools() >> new McpSchema.ListToolsResult([], null)
    clientA.listResources() >> new McpSchema.ListResourcesResult([], null)
    clientA.listPrompts() >> new McpSchema.ListPromptsResult([], null)

    clientB.listTools() >> new McpSchema.ListToolsResult([], null)
    clientB.listResources() >> new McpSchema.ListResourcesResult([], null)
    clientB.listPrompts() >> new McpSchema.ListPromptsResult([], null)

    when:
    registry.registerServer("server-a", clientA)
    registry.registerServer("server-b", clientB)

    then:
    registry.getServerNames().size() == 2
    registry.getServerNames().containsAll(["server-a", "server-b"])
  }

  def "getServerHealth shows healthy and unhealthy servers"() {
    given:
    McpSyncClient clientA = Mock(McpSyncClient)
    McpSyncClient clientB = Mock(McpSyncClient)

    clientA.listTools() >> new McpSchema.ListToolsResult([], null)
    clientA.listResources() >> new McpSchema.ListResourcesResult([], null)
    clientA.listPrompts() >> new McpSchema.ListPromptsResult([], null)

    clientB.listTools() >> new McpSchema.ListToolsResult([], null)
    clientB.listResources() >> new McpSchema.ListResourcesResult([], null)
    clientB.listPrompts() >> new McpSchema.ListPromptsResult([], null)

    registry.registerServer("server-a", clientA)
    registry.registerServer("server-b", clientB)

    when:
    registry.markUnhealthy("server-b", "timeout")

    then:
    Map<String, McpToolRegistry.ServerHealth> health = registry.getServerHealth()
    health.size() == 2
    health["server-a"].healthy
    health["server-a"].reason == null
    !health["server-b"].healthy
    health["server-b"].reason == "timeout"
  }

  def "findServerForTool returns correct server"() {
    given:
    McpSyncClient clientA = Mock(McpSyncClient)
    McpSyncClient clientB = Mock(McpSyncClient)

    McpSchema.Tool toolA = new McpSchema.Tool("tool-a", null, "Tool A", null, null, null, null)
    McpSchema.Tool toolB = new McpSchema.Tool("tool-b", null, "Tool B", null, null, null, null)

    clientA.listTools() >> new McpSchema.ListToolsResult([toolA], null)
    clientA.listResources() >> new McpSchema.ListResourcesResult([], null)
    clientA.listPrompts() >> new McpSchema.ListPromptsResult([], null)

    clientB.listTools() >> new McpSchema.ListToolsResult([toolB], null)
    clientB.listResources() >> new McpSchema.ListResourcesResult([], null)
    clientB.listPrompts() >> new McpSchema.ListPromptsResult([], null)

    registry.registerServer("server-a", clientA)
    registry.registerServer("server-b", clientB)

    expect:
    registry.findServerForTool("tool-a") == "server-a"
    registry.findServerForTool("tool-b") == "server-b"
    registry.findServerForTool("non-existent") == null
  }

  def "callTool throws for unknown server"() {
    when:
    registry.callTool("no-such-server", "tool", [:])

    then:
    thrown(IllegalArgumentException)
  }

  def "callTool throws for unhealthy server"() {
    given:
    McpSyncClient client = Mock(McpSyncClient)
    client.listTools() >> new McpSchema.ListToolsResult([], null)
    client.listResources() >> new McpSchema.ListResourcesResult([], null)
    client.listPrompts() >> new McpSchema.ListPromptsResult([], null)
    registry.registerServer("server-a", client)
    registry.markUnhealthy("server-a", "down")

    when:
    registry.callTool("server-a", "tool", [:])

    then:
    thrown(IllegalArgumentException)
  }

  def "callTool delegates to client"() {
    given:
    McpSyncClient client = Mock(McpSyncClient)
    client.listTools() >> new McpSchema.ListToolsResult([], null)
    client.listResources() >> new McpSchema.ListResourcesResult([], null)
    client.listPrompts() >> new McpSchema.ListPromptsResult([], null)
    registry.registerServer("server-a", client)

    McpSchema.CallToolResult expected = new McpSchema.CallToolResult("result text", false)
    client.callTool(_ as McpSchema.CallToolRequest) >> expected

    when:
    McpSchema.CallToolResult result = registry.callTool("server-a", "my-tool", [key: "value"])

    then:
    result == expected
  }

  def "markHealthy restores server after being unhealthy"() {
    given:
    McpSyncClient client = Mock(McpSyncClient)
    McpSchema.Tool tool = new McpSchema.Tool("tool-x", null, "desc", null, null, null, null)
    client.listTools() >> new McpSchema.ListToolsResult([tool], null)
    client.listResources() >> new McpSchema.ListResourcesResult([], null)
    client.listPrompts() >> new McpSchema.ListPromptsResult([], null)
    registry.registerServer("server-a", client)

    when:
    registry.markUnhealthy("server-a", "failed")

    then:
    !registry.isHealthy("server-a")
    registry.listTools(null).isEmpty()

    when:
    registry.markHealthy("server-a")

    then:
    registry.isHealthy("server-a")
    registry.listTools(null).size() == 1
  }

  def "listResources filters by health"() {
    given:
    McpSyncClient client = Mock(McpSyncClient)
    McpSchema.Resource resource = new McpSchema.Resource("file:///test", "test.txt", "A resource", null, null)
    client.listTools() >> new McpSchema.ListToolsResult([], null)
    client.listResources() >> new McpSchema.ListResourcesResult([resource], null)
    client.listPrompts() >> new McpSchema.ListPromptsResult([], null)
    registry.registerServer("server-a", client)

    expect:
    registry.listResources(null).size() == 1
    registry.listResources("server-a").size() == 1

    when:
    registry.markUnhealthy("server-a", "down")

    then:
    registry.listResources(null).isEmpty()
    registry.listResources("server-a").isEmpty()
  }

  def "listPrompts filters by health"() {
    given:
    McpSyncClient client = Mock(McpSyncClient)
    McpSchema.Prompt prompt = new McpSchema.Prompt("my-prompt", "A prompt", [])
    client.listTools() >> new McpSchema.ListToolsResult([], null)
    client.listResources() >> new McpSchema.ListResourcesResult([], null)
    client.listPrompts() >> new McpSchema.ListPromptsResult([prompt], null)
    registry.registerServer("server-a", client)

    expect:
    registry.listPrompts(null).size() == 1
    registry.listPrompts("server-a").size() == 1

    when:
    registry.markUnhealthy("server-a", "error")

    then:
    registry.listPrompts(null).isEmpty()
    registry.listPrompts("server-a").isEmpty()
  }

  def "refreshCapabilities handles partial support gracefully"() {
    given: "a server that supports tools but throws on resources and prompts"
    McpSyncClient client = Mock(McpSyncClient)
    McpSchema.Tool tool = new McpSchema.Tool("working-tool", null, "works", null, null, null, null)
    client.listTools() >> new McpSchema.ListToolsResult([tool], null)
    client.listResources() >> { throw new UnsupportedOperationException("no resources") }
    client.listPrompts() >> { throw new UnsupportedOperationException("no prompts") }

    when:
    registry.registerServer("partial-server", client)

    then:
    noExceptionThrown()
    registry.listTools("partial-server").size() == 1
    registry.listResources("partial-server").isEmpty()
    registry.listPrompts("partial-server").isEmpty()
  }

  def "getServerNames returns unmodifiable set"() {
    given:
    McpSyncClient client = Mock(McpSyncClient)
    client.listTools() >> new McpSchema.ListToolsResult([], null)
    client.listResources() >> new McpSchema.ListResourcesResult([], null)
    client.listPrompts() >> new McpSchema.ListPromptsResult([], null)
    registry.registerServer("server-a", client)

    when:
    registry.getServerNames().add("server-b")

    then:
    thrown(UnsupportedOperationException)
  }

  def "isHealthy returns false for unregistered server"() {
    expect:
    !registry.isHealthy("non-existent")
  }
}
