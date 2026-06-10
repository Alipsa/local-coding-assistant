package se.alipsa.lca.mcp

import io.modelcontextprotocol.spec.McpSchema
import spock.lang.Specification

class McpToolPromptBuilderSpec extends Specification {

  def "builds tool descriptions for single server"() {
    given:
    McpToolPromptBuilder builder = new McpToolPromptBuilder(3000)

    Map<String, Object> properties = [
      name: [type: 'string', description: 'The name parameter'],
      count: [type: 'integer', description: 'How many items']
    ]

    McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
      'object',
      properties,
      ['name'],
      null,
      null,
      null
    )

    McpSchema.Tool tool = new McpSchema.Tool(
      'test-tool',
      null,
      'A test tool for demonstration',
      inputSchema,
      null,
      null,
      null
    )

    Map<String, List<McpSchema.Tool>> toolsByServer = [
      'test-server': [tool]
    ]

    when:
    String prompt = builder.buildToolPrompt(toolsByServer)

    then:
    prompt.contains('mcp_test-server_test-tool')
    prompt.contains('A test tool for demonstration')
    prompt.contains('Parameters:')
    prompt.contains('name (required)')
    prompt.contains('The name parameter')
    prompt.contains('[string]')
    prompt.contains('count')
    prompt.contains('How many items')
    prompt.contains('[integer]')
    prompt.contains('Call MCP tools with JSON arguments: mcp_server_tool({"param": "value"})')
  }

  def "truncates to name-only when over budget"() {
    given:
    McpToolPromptBuilder builder = new McpToolPromptBuilder(500)

    Map<String, Object> properties = [
      param1: [type: 'string', description: 'First parameter with a long description'],
      param2: [type: 'integer', description: 'Second parameter with another long description'],
      param3: [type: 'boolean', description: 'Third parameter with yet another long description']
    ]

    McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
      'object',
      properties,
      ['param1', 'param2'],
      null,
      null,
      null
    )

    List<McpSchema.Tool> tools = []
    for (int i = 1; i <= 10; i++) {
      tools.add(new McpSchema.Tool(
        "tool-${i}".toString(),
        null,
        "This is tool number ${i} with a detailed description that explains what it does".toString(),
        inputSchema,
        null,
        null,
        null
      ))
    }

    Map<String, List<McpSchema.Tool>> toolsByServer = [
      'test-server': tools
    ]

    when:
    String prompt = builder.buildToolPrompt(toolsByServer)

    then:
    prompt.contains('mcp_test-server_tool-1')
    prompt.contains('Parameters:') // first few tools should have full descriptions

    // Later tools should be truncated to name-only
    def lines = prompt.split('\n')
    def compactEntries = lines.findAll { it.matches(/^\s*- mcp_test-server_tool-\d+$/) }
    compactEntries.size() > 0 // some tools should be compact

    prompt.contains('Call MCP tools with JSON arguments')
  }

  def "returns empty string when no tools"() {
    given:
    McpToolPromptBuilder builder = new McpToolPromptBuilder(3000)

    when:
    String prompt = builder.buildToolPrompt([:])

    then:
    prompt == ''
  }

  def "returns empty string for null tools map"() {
    given:
    McpToolPromptBuilder builder = new McpToolPromptBuilder(3000)

    when:
    String prompt = builder.buildToolPrompt(null)

    then:
    prompt == ''
  }

  def "returns empty string when tools map has empty lists"() {
    given:
    McpToolPromptBuilder builder = new McpToolPromptBuilder(3000)
    Map<String, List<McpSchema.Tool>> toolsByServer = [
      'server-a': [],
      'server-b': []
    ]

    when:
    String prompt = builder.buildToolPrompt(toolsByServer)

    then:
    prompt == ''
  }

  def "includes resource descriptions when resources provided"() {
    given:
    McpToolPromptBuilder builder = new McpToolPromptBuilder(3000)

    List<McpSchema.Resource> resources = [
      new McpSchema.Resource('file:///test.txt', 'test.txt', 'A test file', null, null),
      new McpSchema.Resource('http://example.com/data', 'remote data', 'Remote resource', null, null),
      new McpSchema.Resource('file:///config.json', null, null, null, null) // no name or description
    ]

    when:
    String prompt = builder.buildResourcePrompt(resources)

    then:
    prompt.contains('file:///test.txt (test.txt): A test file')
    prompt.contains('http://example.com/data (remote data): Remote resource')
    prompt.contains('file:///config.json')
    prompt.contains('Read a resource: mcp_read_resource("<uri>")')
  }

  def "returns empty string when no resources"() {
    given:
    McpToolPromptBuilder builder = new McpToolPromptBuilder(3000)

    when:
    String prompt = builder.buildResourcePrompt([])

    then:
    prompt == ''
  }

  def "returns empty string for null resources list"() {
    given:
    McpToolPromptBuilder builder = new McpToolPromptBuilder(3000)

    when:
    String prompt = builder.buildResourcePrompt(null)

    then:
    prompt == ''
  }

  def "handles tools with no input schema"() {
    given:
    McpToolPromptBuilder builder = new McpToolPromptBuilder(3000)

    McpSchema.Tool tool = new McpSchema.Tool(
      'simple-tool',
      null,
      'A simple tool with no parameters',
      null,
      null,
      null,
      null
    )

    Map<String, List<McpSchema.Tool>> toolsByServer = [
      'server': [tool]
    ]

    when:
    String prompt = builder.buildToolPrompt(toolsByServer)

    then:
    prompt.contains('mcp_server_simple-tool')
    prompt.contains('A simple tool with no parameters')
    !prompt.contains('Parameters:')
  }

  def "handles tools with empty input schema"() {
    given:
    McpToolPromptBuilder builder = new McpToolPromptBuilder(3000)

    McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
      'object',
      [:],
      [],
      null,
      null,
      null
    )

    McpSchema.Tool tool = new McpSchema.Tool(
      'empty-schema-tool',
      null,
      'Tool with empty schema',
      inputSchema,
      null,
      null,
      null
    )

    Map<String, List<McpSchema.Tool>> toolsByServer = [
      'server': [tool]
    ]

    when:
    String prompt = builder.buildToolPrompt(toolsByServer)

    then:
    prompt.contains('mcp_server_empty-schema-tool')
    prompt.contains('Tool with empty schema')
    !prompt.contains('Parameters:')
  }

  def "uses default budget when budget <= 0"() {
    given:
    McpToolPromptBuilder builder = new McpToolPromptBuilder(0)

    McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
      'object',
      [param: [type: 'string', description: 'A parameter']],
      [],
      null,
      null,
      null
    )

    McpSchema.Tool tool = new McpSchema.Tool(
      'test-tool',
      null,
      'A test tool',
      inputSchema,
      null,
      null,
      null
    )

    Map<String, List<McpSchema.Tool>> toolsByServer = [
      'server': [tool]
    ]

    when:
    String prompt = builder.buildToolPrompt(toolsByServer)

    then:
    prompt.contains('mcp_server_test-tool')
    prompt.contains('Parameters:')
    prompt.contains('param')
  }

  def "truncates long descriptions"() {
    given:
    McpToolPromptBuilder builder = new McpToolPromptBuilder(3000)

    String longDescription = 'a' * 300

    McpSchema.Tool tool = new McpSchema.Tool(
      'long-desc-tool',
      null,
      longDescription,
      null,
      null,
      null,
      null
    )

    Map<String, List<McpSchema.Tool>> toolsByServer = [
      'server': [tool]
    ]

    when:
    String prompt = builder.buildToolPrompt(toolsByServer)

    then:
    prompt.contains('mcp_server_long-desc-tool')
    prompt.contains('...')
    !prompt.contains('a' * 300)
  }

  def "handles multiple servers in order"() {
    given:
    McpToolPromptBuilder builder = new McpToolPromptBuilder(3000)

    McpSchema.Tool toolA = new McpSchema.Tool('tool-a', null, 'Tool from server A', null, null, null, null)
    McpSchema.Tool toolB = new McpSchema.Tool('tool-b', null, 'Tool from server B', null, null, null, null)
    McpSchema.Tool toolC = new McpSchema.Tool('tool-c', null, 'Tool from server C', null, null, null, null)

    Map<String, List<McpSchema.Tool>> toolsByServer = new LinkedHashMap<>()
    toolsByServer.put('server-a', [toolA])
    toolsByServer.put('server-b', [toolB])
    toolsByServer.put('server-c', [toolC])

    when:
    String prompt = builder.buildToolPrompt(toolsByServer)

    then:
    def toolAIndex = prompt.indexOf('mcp_server-a_tool-a')
    def toolBIndex = prompt.indexOf('mcp_server-b_tool-b')
    def toolCIndex = prompt.indexOf('mcp_server-c_tool-c')

    toolAIndex > -1
    toolBIndex > -1
    toolCIndex > -1
    toolAIndex < toolBIndex
    toolBIndex < toolCIndex
  }

  def "handles parameters without descriptions"() {
    given:
    McpToolPromptBuilder builder = new McpToolPromptBuilder(3000)

    Map<String, Object> properties = [
      param1: [type: 'string'],
      param2: [type: 'integer']
    ]

    McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
      'object',
      properties,
      ['param1'],
      null,
      null,
      null
    )

    McpSchema.Tool tool = new McpSchema.Tool(
      'minimal-params',
      null,
      'Tool with minimal param info',
      inputSchema,
      null,
      null,
      null
    )

    Map<String, List<McpSchema.Tool>> toolsByServer = [
      'server': [tool]
    ]

    when:
    String prompt = builder.buildToolPrompt(toolsByServer)

    then:
    prompt.contains('mcp_server_minimal-params')
    prompt.contains('param1 (required)')
    prompt.contains('[string]')
    prompt.contains('param2')
    prompt.contains('[integer]')
  }
}
