package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic

/**
 * Represents an MCP tool call with named arguments.
 * Unlike ToolCall (positional args), this uses a Map for JSON-style named parameters.
 */
@Canonical
@CompileStatic
class StandardToolCall {
  String serverName
  String toolName
  Map<String, Object> arguments
}
