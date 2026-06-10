package se.alipsa.lca.tools

import spock.lang.Specification

class ToolCallDispatcherSpec extends Specification {

  FileEditingTool fileEditingTool = Mock()
  CommandRunner commandRunner = Mock()
  McpToolExecutorFunction mcpExecutor = Mock()

  def "dispatches writeFile to FileEditingTool"() {
    given:
    ToolCallDispatcher dispatcher = new ToolCallDispatcher(fileEditingTool, commandRunner, mcpExecutor)
    ToolCallParser.ToolCall call = new ToolCallParser.ToolCall("writeFile", ["/tmp/test.txt", "content"])

    when:
    String result = dispatcher.dispatchBuiltin(call)

    then:
    1 * fileEditingTool.writeFile("/tmp/test.txt", "content") >> "File written"
    result == "File written"
  }

  def "dispatches replace to FileEditingTool"() {
    given:
    ToolCallDispatcher dispatcher = new ToolCallDispatcher(fileEditingTool, commandRunner, mcpExecutor)
    ToolCallParser.ToolCall call = new ToolCallParser.ToolCall("replace", ["/tmp/test.txt", "old", "new"])

    when:
    String result = dispatcher.dispatchBuiltin(call)

    then:
    1 * fileEditingTool.replace("/tmp/test.txt", "old", "new") >> "Replaced"
    result == "Replaced"
  }

  def "dispatches deleteFile to FileEditingTool"() {
    given:
    ToolCallDispatcher dispatcher = new ToolCallDispatcher(fileEditingTool, commandRunner, mcpExecutor)
    ToolCallParser.ToolCall call = new ToolCallParser.ToolCall("deleteFile", ["/tmp/test.txt"])

    when:
    String result = dispatcher.dispatchBuiltin(call)

    then:
    1 * fileEditingTool.deleteFile("/tmp/test.txt") >> "Deleted"
    result == "Deleted"
  }

  def "dispatches runCommand to CommandRunner"() {
    given:
    ToolCallDispatcher dispatcher = new ToolCallDispatcher(fileEditingTool, commandRunner, mcpExecutor)
    ToolCallParser.ToolCall call = new ToolCallParser.ToolCall("runCommand", ["echo hello"])
    CommandRunner.CommandResult cmdResult = new CommandRunner.CommandResult(
      false, false, 0, "hello\n", false, null
    )

    when:
    String result = dispatcher.dispatchBuiltin(call)

    then:
    1 * commandRunner.run("echo hello", 60000L, 8000) >> cmdResult
    result.contains("Successfully executed: echo hello")
    result.contains("hello")
  }

  def "runCommand with non-zero exit code"() {
    given:
    ToolCallDispatcher dispatcher = new ToolCallDispatcher(fileEditingTool, commandRunner, mcpExecutor)
    ToolCallParser.ToolCall call = new ToolCallParser.ToolCall("runCommand", ["false"])
    CommandRunner.CommandResult cmdResult = new CommandRunner.CommandResult(
      false, false, 1, "error", false, null
    )

    when:
    String result = dispatcher.dispatchBuiltin(call)

    then:
    1 * commandRunner.run("false", 60000L, 8000) >> cmdResult
    result.contains("Failed (exit 1): false")
    result.contains("error")
  }

  def "throws exception for unknown tool"() {
    given:
    ToolCallDispatcher dispatcher = new ToolCallDispatcher(fileEditingTool, commandRunner, mcpExecutor)
    ToolCallParser.ToolCall call = new ToolCallParser.ToolCall("unknownTool", ["arg"])

    when:
    dispatcher.dispatchBuiltin(call)

    then:
    thrown(IllegalArgumentException)
  }

  def "validates writeFile argument count"() {
    given:
    ToolCallDispatcher dispatcher = new ToolCallDispatcher(fileEditingTool, commandRunner, mcpExecutor)
    ToolCallParser.ToolCall call = new ToolCallParser.ToolCall("writeFile", ["only-one-arg"])

    when:
    dispatcher.dispatchBuiltin(call)

    then:
    thrown(IllegalArgumentException)
  }

  def "validates replace argument count"() {
    given:
    ToolCallDispatcher dispatcher = new ToolCallDispatcher(fileEditingTool, commandRunner, mcpExecutor)
    ToolCallParser.ToolCall call = new ToolCallParser.ToolCall("replace", ["file", "old"])

    when:
    dispatcher.dispatchBuiltin(call)

    then:
    thrown(IllegalArgumentException)
  }

  def "throws exception when CommandRunner is null for runCommand"() {
    given:
    ToolCallDispatcher dispatcher = new ToolCallDispatcher(fileEditingTool, null, mcpExecutor)
    ToolCallParser.ToolCall call = new ToolCallParser.ToolCall("runCommand", ["echo test"])

    when:
    dispatcher.dispatchBuiltin(call)

    then:
    thrown(IllegalStateException)
  }

  def "dispatches MCP tool call to executor function"() {
    given:
    ToolCallDispatcher dispatcher = new ToolCallDispatcher(fileEditingTool, commandRunner, mcpExecutor)
    StandardToolCall call = new StandardToolCall("myServer", "myTool", [param: "value"])

    when:
    String result = dispatcher.dispatchMcp(call)

    then:
    1 * mcpExecutor.execute(call) >> "MCP result"
    result == "MCP result"
  }

  def "throws exception when MCP executor is null"() {
    given:
    ToolCallDispatcher dispatcher = new ToolCallDispatcher(fileEditingTool, commandRunner, null)
    StandardToolCall call = new StandardToolCall("myServer", "myTool", [param: "value"])

    when:
    dispatcher.dispatchMcp(call)

    then:
    thrown(IllegalStateException)
  }
}
