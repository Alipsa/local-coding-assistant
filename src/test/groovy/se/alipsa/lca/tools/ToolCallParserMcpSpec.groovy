package se.alipsa.lca.tools

import spock.lang.Specification

class ToolCallParserMcpSpec extends Specification {

  ToolCallParser parser = new ToolCallParser()

  def "parses MCP tool call with valid JSON arguments"() {
    given:
    String response = 'mcp_myserver_mytool({"param1": "value1", "param2": 42})'

    when:
    ToolCallParser.ParsedToolCalls result = parser.parseAllToolCalls(response)

    then:
    result.mcpCalls.size() == 1
    result.mcpCalls[0].serverName == 'myserver'
    result.mcpCalls[0].toolName == 'mytool'
    result.mcpCalls[0].arguments == [param1: 'value1', param2: 42]
    result.errors.isEmpty()
  }

  def "parses multiple MCP tool calls from same output"() {
    given:
    String response = '''Here are the results:
mcp_server1_toolA({"key": "val1"})
Some text in between.
mcp_server2_toolB({"key": "val2", "num": 10})'''

    when:
    ToolCallParser.ParsedToolCalls result = parser.parseAllToolCalls(response)

    then:
    result.mcpCalls.size() == 2
    result.mcpCalls[0].serverName == 'server1'
    result.mcpCalls[0].toolName == 'toolA'
    result.mcpCalls[0].arguments == [key: 'val1']
    result.mcpCalls[1].serverName == 'server2'
    result.mcpCalls[1].toolName == 'toolB'
    result.mcpCalls[1].arguments == [key: 'val2', num: 10]
    result.errors.isEmpty()
  }

  def "handles malformed JSON leniently - unquoted keys"() {
    given:
    String response = 'mcp_srv_tool({unquoted_key: "value", another: 123})'

    when:
    ToolCallParser.ParsedToolCalls result = parser.parseAllToolCalls(response)

    then:
    result.mcpCalls.size() == 1
    result.mcpCalls[0].arguments == [unquoted_key: 'value', another: 123]
    result.errors.isEmpty()
  }

  def "handles malformed JSON leniently - single quotes"() {
    given:
    String response = "mcp_srv_tool({'key': 'value'})"

    when:
    ToolCallParser.ParsedToolCalls result = parser.parseAllToolCalls(response)

    then:
    result.mcpCalls.size() == 1
    result.mcpCalls[0].arguments == [key: 'value']
    result.errors.isEmpty()
  }

  def "handles malformed JSON leniently - trailing comma"() {
    given:
    String response = 'mcp_srv_tool({"key": "value", "num": 1,})'

    when:
    ToolCallParser.ParsedToolCalls result = parser.parseAllToolCalls(response)

    then:
    result.mcpCalls.size() == 1
    result.mcpCalls[0].arguments == [key: 'value', num: 1]
    result.errors.isEmpty()
  }

  def "skips completely invalid JSON and adds error"() {
    given:
    String response = 'mcp_badserver_badtool({this is not json at all!!!})'

    when:
    ToolCallParser.ParsedToolCalls result = parser.parseAllToolCalls(response)

    then:
    result.mcpCalls.isEmpty()
    result.errors.size() == 1
    result.errors[0].contains('mcp_badserver_badtool')
  }

  def "mixed built-in and MCP calls in same output"() {
    given:
    String response = '''writeFile("/tmp/test.txt", "hello")
mcp_bq_query({"sql": "SELECT 1"})
deleteFile("/tmp/old.txt")'''

    when:
    ToolCallParser.ParsedToolCalls result = parser.parseAllToolCalls(response)

    then:
    result.builtinCalls.size() == 2
    result.builtinCalls[0].toolName == 'writeFile'
    result.builtinCalls[1].toolName == 'deleteFile'
    result.mcpCalls.size() == 1
    result.mcpCalls[0].serverName == 'bq'
    result.mcpCalls[0].toolName == 'query'
    result.mcpCalls[0].arguments == [sql: 'SELECT 1']
    result.errors.isEmpty()
  }

  def "parses mcp_read_resource as StandardToolCall"() {
    given:
    String response = 'mcp_read_resource("file:///tmp/data.json")'

    when:
    ToolCallParser.ParsedToolCalls result = parser.parseAllToolCalls(response)

    then:
    result.mcpCalls.size() == 1
    result.mcpCalls[0].serverName == '_resource'
    result.mcpCalls[0].toolName == 'read_resource'
    result.mcpCalls[0].arguments.uri == 'file:///tmp/data.json'
    result.errors.isEmpty()
  }

  def "parses mcp_read_resource with single quotes"() {
    given:
    String response = "mcp_read_resource('https://example.com/resource')"

    when:
    ToolCallParser.ParsedToolCalls result = parser.parseAllToolCalls(response)

    then:
    result.mcpCalls.size() == 1
    result.mcpCalls[0].arguments.uri == 'https://example.com/resource'
    result.errors.isEmpty()
  }

  def "existing built-in parsing still works - writeFile"() {
    given:
    String response = 'writeFile("/path/to/file.txt", "file content")'

    when:
    List<ToolCallParser.ToolCall> calls = parser.parseToolCalls(response)

    then:
    calls.size() == 1
    calls[0].toolName == 'writeFile'
    calls[0].arguments == ['/path/to/file.txt', 'file content']
  }

  def "existing built-in parsing still works - replace"() {
    given:
    String response = 'replace("/path/to/file.txt", "old text", "new text")'

    when:
    List<ToolCallParser.ToolCall> calls = parser.parseToolCalls(response)

    then:
    calls.size() == 1
    calls[0].toolName == 'replace'
    calls[0].arguments == ['/path/to/file.txt', 'old text', 'new text']
  }

  def "existing built-in parsing still works - deleteFile"() {
    given:
    String response = 'deleteFile("/path/to/file.txt")'

    when:
    List<ToolCallParser.ToolCall> calls = parser.parseToolCalls(response)

    then:
    calls.size() == 1
    calls[0].toolName == 'deleteFile'
    calls[0].arguments == ['/path/to/file.txt']
  }

  def "existing built-in parsing still works - runCommand"() {
    given:
    String response = 'runCommand("echo hello")'

    when:
    List<ToolCallParser.ToolCall> calls = parser.parseToolCalls(response)

    then:
    calls.size() == 1
    calls[0].toolName == 'runCommand'
    calls[0].arguments == ['echo hello']
  }

  def "returns empty results when no tool calls present"() {
    given:
    String response = 'Just some plain text with no tool calls at all.'

    when:
    ToolCallParser.ParsedToolCalls result = parser.parseAllToolCalls(response)

    then:
    result.builtinCalls.isEmpty()
    result.mcpCalls.isEmpty()
    result.errors.isEmpty()
  }

  def "parses MCP call with nested JSON arguments"() {
    given:
    String response = 'mcp_looker_query({"model": "mymodel", "filters": {"date": "7 days"}})'

    when:
    ToolCallParser.ParsedToolCalls result = parser.parseAllToolCalls(response)

    then:
    result.mcpCalls.size() == 1
    result.mcpCalls[0].arguments['model'] == 'mymodel'
    result.mcpCalls[0].arguments['filters'] == [date: '7 days']
    result.errors.isEmpty()
  }

  def "valid MCP calls alongside invalid ones produces partial results with errors"() {
    given:
    String response = '''mcp_good_tool({"key": "val"})
mcp_bad_tool({not valid json!!!})
mcp_good2_tool2({"other": "data"})'''

    when:
    ToolCallParser.ParsedToolCalls result = parser.parseAllToolCalls(response)

    then:
    result.mcpCalls.size() == 2
    result.mcpCalls[0].serverName == 'good'
    result.mcpCalls[1].serverName == 'good2'
    result.errors.size() == 1
    result.errors[0].contains('mcp_bad_tool')
  }

  def "parses multi-word tool name correctly"() {
    given:
    String response = 'mcp_bq_list_tables({"dataset": "my_dataset"})'

    when:
    ToolCallParser.ParsedToolCalls result = parser.parseAllToolCalls(response)

    then:
    result.mcpCalls.size() == 1
    result.mcpCalls[0].serverName == 'bq'
    result.mcpCalls[0].toolName == 'list_tables'
    result.mcpCalls[0].arguments.dataset == 'my_dataset'
  }

  def "parses hyphenated server name"() {
    given:
    String response = 'mcp_looker-admin_get_dashboard({"id": "123"})'

    when:
    ToolCallParser.ParsedToolCalls result = parser.parseAllToolCalls(response)

    then:
    result.mcpCalls.size() == 1
    result.mcpCalls[0].serverName == 'looker-admin'
    result.mcpCalls[0].toolName == 'get_dashboard'
  }
}
