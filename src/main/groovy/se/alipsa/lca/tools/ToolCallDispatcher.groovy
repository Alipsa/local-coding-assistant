package se.alipsa.lca.tools

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Dispatches tool calls to their respective executors.
 * Routes built-in ToolCall instances to FileEditingTool/CommandRunner,
 * and StandardToolCall instances to the MCP executor function.
 */
@CompileStatic
class ToolCallDispatcher {

  private static final Logger log = LoggerFactory.getLogger(ToolCallDispatcher)

  private final FileEditingTool fileEditingTool
  private final CommandRunner commandRunner
  private final McpToolExecutorFunction mcpExecutor

  ToolCallDispatcher(
    FileEditingTool fileEditingTool,
    CommandRunner commandRunner,
    McpToolExecutorFunction mcpExecutor
  ) {
    this.fileEditingTool = fileEditingTool
    this.commandRunner = commandRunner
    this.mcpExecutor = mcpExecutor
  }

  /**
   * Dispatch a built-in tool call to FileEditingTool or CommandRunner.
   * Mirrors the logic from ToolCallParser.executeSingleTool().
   */
  String dispatchBuiltin(ToolCallParser.ToolCall call) {
    switch (call.toolName) {
      case "writeFile":
        if (call.arguments.size() != 2) {
          throw new IllegalArgumentException("writeFile requires 2 arguments")
        }
        return fileEditingTool.writeFile(call.arguments[0], call.arguments[1])

      case "replace":
        if (call.arguments.size() != 3) {
          throw new IllegalArgumentException("replace requires 3 arguments")
        }
        return fileEditingTool.replace(call.arguments[0], call.arguments[1], call.arguments[2])

      case "deleteFile":
        if (call.arguments.size() != 1) {
          throw new IllegalArgumentException("deleteFile requires 1 argument")
        }
        return fileEditingTool.deleteFile(call.arguments[0])

      case "runCommand":
        if (commandRunner == null) {
          throw new IllegalStateException("CommandRunner not available - cannot execute shell commands")
        }
        if (call.arguments.size() != 1) {
          throw new IllegalArgumentException("runCommand requires 1 argument")
        }
        String command = call.arguments[0]
        CommandRunner.CommandResult result = commandRunner.run(command, 60000L, 8000)
        if (result.exitCode == 0) {
          return "Successfully executed: ${command}\n${result.output}"
        } else {
          return "Failed (exit ${result.exitCode}): ${command}\n${result.output}"
        }

      default:
        throw new IllegalArgumentException("Unknown tool: ${call.toolName}")
    }
  }

  /**
   * Dispatch an MCP tool call to the configured MCP executor function.
   */
  String dispatchMcp(StandardToolCall call) {
    if (mcpExecutor == null) {
      throw new IllegalStateException("MCP executor not configured - cannot execute MCP tools")
    }
    log.debug("Dispatching MCP tool call: server={}, tool={}", call.serverName, call.toolName)
    return mcpExecutor.execute(call)
  }
}
