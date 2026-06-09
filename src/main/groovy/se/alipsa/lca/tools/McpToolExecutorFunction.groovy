package se.alipsa.lca.tools

import groovy.transform.CompileStatic

/**
 * Functional interface for executing MCP tool calls.
 * Implementations will integrate with the MCP client to dispatch StandardToolCall instances.
 */
@CompileStatic
@FunctionalInterface
interface McpToolExecutorFunction {
  String execute(StandardToolCall call)
}
