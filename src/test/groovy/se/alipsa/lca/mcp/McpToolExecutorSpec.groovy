package se.alipsa.lca.mcp

import io.modelcontextprotocol.spec.McpSchema
import se.alipsa.lca.tools.StandardToolCall
import spock.lang.Specification

/**
 * Tests for McpToolExecutor.
 */
class McpToolExecutorSpec extends Specification {

  McpToolRegistry registry = Mock()
  McpToolExecutor executor

  def setup() {
    executor = new McpToolExecutor(registry, true)
  }

  def "executes tool and returns formatted result"() {
    given:
    StandardToolCall call = new StandardToolCall(
      serverName: 'test-server',
      toolName: 'test-tool',
      arguments: [key: 'value']
    )
    McpSchema.CallToolResult result = new McpSchema.CallToolResult(
      [new McpSchema.TextContent(null, null, 'success result')],
      false
    )

    when:
    String output = executor.execute(call)

    then:
    1 * registry.isHealthy('test-server') >> true
    1 * registry.callTool('test-server', 'test-tool', [key: 'value']) >> result
    output == 'success result'
  }

  def "returns error for unhealthy server"() {
    given:
    StandardToolCall call = new StandardToolCall(
      serverName: 'unhealthy-server',
      toolName: 'test-tool',
      arguments: [:]
    )

    when:
    String output = executor.execute(call)

    then:
    1 * registry.isHealthy('unhealthy-server') >> false
    0 * registry.callTool(_, _, _)
    output == "Error: Server 'unhealthy-server' is not healthy"
  }

  def "classifies destructive tools correctly"() {
    expect:
    McpToolExecutor.isDestructive(toolName) == expectedDestructive

    where:
    toolName              | expectedDestructive
    'list_items'          | false
    'get_details'         | false
    'read_file'           | false
    'search_data'         | false
    'query_database'      | false
    'fetch_records'       | false
    'find_user'           | false
    'describe_table'      | false
    'show_status'         | false
    'count_rows'          | false
    'check_health'        | false
    'view_logs'           | false
    'list_write_logs'     | false  // read-only prefix wins
    'create_user'         | true
    'update_record'       | true
    'delete_file'         | true
    'write_data'          | true
    'send_message'        | true
    'execute_command'     | true
    'run_script'          | true
    'remove_item'         | true
    'drop_table'          | true
    'insert_row'          | true
    'put_object'          | true
    'post_data'           | true
    'push_changes'        | true
    null                  | false
  }

  def "logs tool invocation"() {
    given:
    StandardToolCall call = new StandardToolCall(
      serverName: 'test-server',
      toolName: 'test-tool',
      arguments: [key1: 'value1', key2: 'value2']
    )
    McpSchema.CallToolResult result = new McpSchema.CallToolResult(
      [new McpSchema.TextContent(null, null, 'result')],
      false
    )

    when:
    executor.execute(call)

    then:
    1 * registry.isHealthy('test-server') >> true
    1 * registry.callTool(_, _, _) >> result
    notThrown(Exception)
  }

  def "handles error result from tool"() {
    given:
    StandardToolCall call = new StandardToolCall(
      serverName: 'test-server',
      toolName: 'test-tool',
      arguments: [:]
    )
    McpSchema.CallToolResult result = new McpSchema.CallToolResult(
      [new McpSchema.TextContent(null, null, 'tool error message')],
      true
    )

    when:
    String output = executor.execute(call)

    then:
    1 * registry.isHealthy('test-server') >> true
    1 * registry.callTool(_, _, _) >> result
    output == 'Error from test-server.test-tool: tool error message'
  }

  def "handles exception during tool execution"() {
    given:
    StandardToolCall call = new StandardToolCall(
      serverName: 'test-server',
      toolName: 'test-tool',
      arguments: [:]
    )

    when:
    String output = executor.execute(call)

    then:
    1 * registry.isHealthy('test-server') >> true
    1 * registry.callTool(_, _, _) >> { throw new RuntimeException('test exception') }
    output.startsWith('Error: ')
  }

  def "handles null arguments"() {
    given:
    StandardToolCall call = new StandardToolCall(
      serverName: 'test-server',
      toolName: 'test-tool',
      arguments: null
    )
    McpSchema.CallToolResult result = new McpSchema.CallToolResult(
      [new McpSchema.TextContent(null, null, 'result')],
      false
    )

    when:
    String output = executor.execute(call)

    then:
    1 * registry.isHealthy('test-server') >> true
    1 * registry.callTool('test-server', 'test-tool', [:]) >> result
    output == 'result'
  }

  def "requiresConfirmation returns true for destructive tools when enabled"() {
    given:
    executor = new McpToolExecutor(registry, true)

    expect:
    executor.requiresConfirmation('create_user') == true
    executor.requiresConfirmation('list_users') == false
  }

  def "requiresConfirmation returns false when confirmation disabled"() {
    given:
    executor = new McpToolExecutor(registry, false)

    expect:
    executor.requiresConfirmation('create_user') == false
    executor.requiresConfirmation('list_users') == false
  }
}
