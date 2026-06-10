package se.alipsa.lca.tools

import spock.lang.Specification

class ToolCallDispatcherSpec extends Specification {

  ToolCallParser toolCallParser = new ToolCallParser()
  FileEditingTool fileEditingTool = Mock()
  CommandRunner commandRunner = Mock()
  McpToolExecutorFunction mcpExecutor = Mock()

  def "dispatches writeFile via ToolCallParser"() {
    given:
    def dispatcher = new ToolCallDispatcher(
      toolCallParser, fileEditingTool, commandRunner, mcpExecutor
    )
    def call = new ToolCallParser.ToolCall("writeFile", ["/tmp/t.txt", "hello"])

    when:
    String result = dispatcher.dispatchBuiltin(call)

    then:
    1 * fileEditingTool.writeFile("/tmp/t.txt", "hello") >> "File written"
    result.contains("writeFile")
  }

  def "dispatches MCP tool call to executor function"() {
    given:
    def dispatcher = new ToolCallDispatcher(
      toolCallParser, fileEditingTool, commandRunner, mcpExecutor
    )
    def call = new StandardToolCall("bq", "query", [sql: "SELECT 1"])

    when:
    String result = dispatcher.dispatchMcp(call)

    then:
    1 * mcpExecutor.execute(call) >> "query result"
    result == "query result"
  }

  def "throws when MCP executor is null"() {
    given:
    def dispatcher = new ToolCallDispatcher(
      toolCallParser, fileEditingTool, commandRunner, null
    )
    def call = new StandardToolCall("bq", "query", [:])

    when:
    dispatcher.dispatchMcp(call)

    then:
    thrown(IllegalStateException)
  }

  def "dispatchAll handles mixed built-in and MCP calls"() {
    given:
    def dispatcher = new ToolCallDispatcher(
      toolCallParser, fileEditingTool, commandRunner, mcpExecutor
    )
    def parsed = new ToolCallParser.ParsedToolCalls(
      [new ToolCallParser.ToolCall("writeFile", ["/tmp/f.txt", "x"])],
      [new StandardToolCall("bq", "query", [sql: "SELECT 1"])],
      []
    )
    fileEditingTool.writeFile("/tmp/f.txt", "x") >> "OK"
    mcpExecutor.execute(_) >> "query done"

    when:
    String result = dispatcher.dispatchAll(parsed)

    then:
    result.contains("writeFile")
    result.contains("query done")
  }

  def "dispatchAll includes parse errors"() {
    given:
    def dispatcher = new ToolCallDispatcher(
      toolCallParser, fileEditingTool, commandRunner, mcpExecutor
    )
    def parsed = new ToolCallParser.ParsedToolCalls([], [], ["bad JSON"])

    when:
    String result = dispatcher.dispatchAll(parsed)

    then:
    result.contains("PARSE ERROR")
    result.contains("bad JSON")
  }

  def "dispatchAll returns null when no calls"() {
    given:
    def dispatcher = new ToolCallDispatcher(
      toolCallParser, fileEditingTool, commandRunner, mcpExecutor
    )
    def parsed = new ToolCallParser.ParsedToolCalls([], [], [])

    when:
    String result = dispatcher.dispatchAll(parsed)

    then:
    result == null
  }
}
