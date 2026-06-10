package se.alipsa.lca.tools

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Dispatches tool calls to their respective executors.
 * Routes built-in ToolCall instances via ToolCallParser (single source of truth),
 * and StandardToolCall instances to the MCP executor function.
 */
@CompileStatic
class ToolCallDispatcher {

  private static final Logger log = LoggerFactory.getLogger(ToolCallDispatcher)

  private final ToolCallParser toolCallParser
  private final FileEditingTool fileEditingTool
  private final CommandRunner commandRunner
  private final McpToolExecutorFunction mcpExecutor

  ToolCallDispatcher(
    ToolCallParser toolCallParser,
    FileEditingTool fileEditingTool,
    CommandRunner commandRunner,
    McpToolExecutorFunction mcpExecutor
  ) {
    this.toolCallParser = toolCallParser
    this.fileEditingTool = fileEditingTool
    this.commandRunner = commandRunner
    this.mcpExecutor = mcpExecutor
  }

  private static final String TOOL_RESULT_BANNER = "\n\n=== Tool Execution Results ===\n"

  String dispatchBuiltin(ToolCallParser.ToolCall call) {
    String result = toolCallParser.executeToolCalls(
      [call], fileEditingTool, commandRunner
    )
    if (result == null) {
      return "No result from ${call.toolName}"
    }
    result.startsWith(TOOL_RESULT_BANNER)
      ? result.substring(TOOL_RESULT_BANNER.length()).trim()
      : result.trim()
  }

  String dispatchMcp(StandardToolCall call) {
    if (mcpExecutor == null) {
      throw new IllegalStateException(
        "MCP executor not configured - cannot execute MCP tools"
      )
    }
    log.debug("Dispatching MCP tool call: server={}, tool={}",
      call.serverName, call.toolName)
    return mcpExecutor.execute(call)
  }

  String dispatchAll(ToolCallParser.ParsedToolCalls parsed) {
    StringBuilder results = new StringBuilder()

    for (ToolCallParser.ToolCall call : parsed.builtinCalls) {
      try {
        results.append(dispatchBuiltin(call)).append('\n')
      } catch (Exception e) {
        log.error("Built-in tool call failed: {}", call.toolName, e)
        results.append("ERROR: ${call.toolName}: ${e.message}\n")
      }
    }

    for (StandardToolCall call : parsed.mcpCalls) {
      try {
        results.append(dispatchMcp(call)).append('\n')
      } catch (Exception e) {
        log.error("MCP tool call failed: {}.{}", call.serverName, call.toolName, e)
        results.append("ERROR: ${call.serverName}.${call.toolName}: ${e.message}\n")
      }
    }

    for (String error : parsed.errors) {
      results.append("PARSE ERROR: ${error}\n")
    }

    results.length() > 0 ? results.toString().trim() : null
  }
}
