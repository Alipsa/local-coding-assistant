package se.alipsa.lca.mcp

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import se.alipsa.lca.tools.McpToolExecutorFunction
import se.alipsa.lca.tools.StandardToolCall

/**
 * Executor that validates arguments, handles destructive tool detection,
 * invokes callTool(), and formats results.
 */
@Slf4j
@Component
@CompileStatic
class McpToolExecutor implements McpToolExecutorFunction {

  private final McpToolRegistry registry
  private final boolean confirmDestructive

  McpToolExecutor(
    McpToolRegistry registry,
    @Value('${assistant.mcp.confirm-destructive:true}') boolean confirmDestructive
  ) {
    this.registry = registry
    this.confirmDestructive = confirmDestructive
  }

  /**
   * Executes an MCP tool call and returns the formatted result.
   *
   * @param call the tool call to execute
   * @return formatted result string or error message
   */
  @Override
  String execute(StandardToolCall call) {
    try {
      // Handle mcp_read_resource virtual tool (no server health check needed)
      if (call.serverName == '_resource' && call.toolName == 'read_resource') {
        String uri = call.arguments?.get('uri') as String
        if (!uri) {
          return "Error: mcp_read_resource requires a URI argument"
        }
        log.info("Reading MCP resource: {}", uri)
        McpSchema.ReadResourceResult resourceResult = registry.readResource(uri)
        return resourceResult.contents()?.collect { it.toString() }?.join('\n') ?: '(empty)'
      }

      // Check server health
      if (!registry.isHealthy(call.serverName)) {
        return "Error: Server '${call.serverName}' is not healthy"
      }

      // Log invocation (INFO: server, tool, argument keys only; DEBUG: full arguments)
      Set<String> argKeys = call.arguments?.keySet() ?: ([] as Set<String>)
      log.info("Calling MCP tool: server={}, tool={}, arguments=[{}]",
        call.serverName, call.toolName, argKeys.join(', '))
      log.debug("Full arguments for {}.{}: {}", call.serverName, call.toolName, call.arguments)

      if (requiresConfirmation(call.toolName)) {
        return "REFUSED: Tool '${call.serverName}.${call.toolName}' is classified as destructive " +
          "and requires confirmation. Use /mcp call ${call.serverName}_${call.toolName} to invoke directly."
      }

      // Call the tool
      Map<String, Object> args = call.arguments != null ? call.arguments : Map.of()
      McpSchema.CallToolResult result = registry.callTool(
        call.serverName,
        call.toolName,
        args
      )

      // Format and return result
      return formatResult(call, result)

    } catch (Exception e) {
      log.error("Error executing tool {}.{}: {}", call.serverName, call.toolName, e.message, e)
      return "Error: ${e.message}"
    }
  }

  /**
   * Checks if a tool name matches read-only patterns.
   * Order matters: check read-only FIRST (so "list_write_logs" is read-only).
   *
   * @param toolName the tool name to check
   * @return true if destructive, false if read-only
   */
  static boolean isDestructive(String toolName) {
    if (toolName == null) {
      return false
    }

    String lower = toolName.toLowerCase()

    // Check read-only patterns FIRST
    String[] readOnlyPrefixes = ['list', 'get', 'read', 'search', 'query', 'fetch', 'find', 'describe', 'show',
                                  'count', 'check', 'view']
    for (String prefix : readOnlyPrefixes) {
      if (lower.startsWith(prefix)) {
        return false
      }
    }

    // Check destructive patterns
    String[] destructiveKeywords = ['write', 'delete', 'create', 'update', 'send', 'execute', 'run', 'remove',
                                     'drop', 'insert', 'put', 'post', 'push']
    for (String keyword : destructiveKeywords) {
      if (lower.contains(keyword)) {
        return true
      }
    }

    return false
  }

  /**
   * Checks if a tool requires confirmation before execution.
   *
   * @param toolName the tool name to check
   * @return true if confirmation is required
   */
  boolean requiresConfirmation(String toolName) {
    return confirmDestructive && isDestructive(toolName)
  }

  /**
   * Checks if a tool call requires confirmation before execution.
   * Convenience wrapper for callers (e.g. the REPL layer) that have a StandardToolCall.
   *
   * @param call the tool call to check
   * @return true if confirmation is required
   */
  boolean needsConfirmation(StandardToolCall call) {
    return requiresConfirmation(call.toolName)
  }

  String executeConfirmed(StandardToolCall call) {
    try {
      if (!registry.isHealthy(call.serverName)) {
        return "Error: Server '${call.serverName}' is not healthy"
      }
      log.info("Executing confirmed MCP tool: server={}, tool={}",
        call.serverName, call.toolName)
      Map<String, Object> args = call.arguments != null ? call.arguments : Map.of()
      McpSchema.CallToolResult result = registry.callTool(
        call.serverName, call.toolName, args
      )
      return formatResult(call, result)
    } catch (Exception e) {
      log.error("Error executing tool {}.{}: {}",
        call.serverName, call.toolName, e.message, e)
      return "Error: ${e.message}"
    }
  }

  /**
   * Formats a CallToolResult into a string.
   *
   * @param call the original tool call
   * @param result the result to format
   * @return formatted result string
   */
  private String formatResult(StandardToolCall call, McpSchema.CallToolResult result) {
    if (result.isError()) {
      String errorText = extractText(result)
      return "Error from ${call.serverName}.${call.toolName}: ${errorText}"
    }

    return extractText(result)
  }

  /**
   * Extracts text content from a CallToolResult.
   * Content items may be TextContent (has text() method) or other types (use toString()).
   *
   * @param result the result to extract text from
   * @return extracted text
   */
  private String extractText(McpSchema.CallToolResult result) {
    if (result.content() == null || result.content().isEmpty()) {
      return ''
    }

    StringBuilder sb = new StringBuilder()
    for (Object content : result.content()) {
      if (content instanceof McpSchema.TextContent) {
        McpSchema.TextContent textContent = (McpSchema.TextContent) content
        sb.append(textContent.text())
      } else {
        sb.append(content.toString())
      }
      sb.append('\n')
    }

    return sb.toString().trim()
  }
}
