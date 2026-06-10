# MCP Client Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add MCP client support to LCA so Claude-compatible MCP servers work out of the box — tools, resources, and prompts via stdio transport.

**Architecture:** Spring AI MCP Client Starter (already on classpath via Embabel 0.3.5) handles server lifecycle and protocol. LCA adds a config loader for Claude's JSON format, a tool registry, a dispatcher that routes both built-in and MCP tool calls, and `/mcp` slash commands. Built-in tool calling is left unchanged — MCP tools get their own `StandardToolCall` type.

**Tech Stack:** Groovy 5.0.3, Spring Boot 3.5.x, Embabel 0.3.5, spring-ai-starter-mcp-client 1.1.4, mcp-sdk 0.17.0, Spock 2.4

**Spec:** `docs/superpowers/specs/2026-06-09-mcp-support-design.md`

**Conventions (from AGENTS.md):**
- `@CompileStatic` on all classes
- 2-space indent, 120-char max line
- British English spelling
- Spock 2.4 tests for all new code
- Run `./mvnw test` after each task

---

## File Map

### New files

| File | Responsibility |
|---|---|
| `src/main/groovy/se/alipsa/lca/mcp/McpConfigLoader.groovy` | Read Claude-format JSON configs, merge, write consolidated file for starter |
| `src/main/groovy/se/alipsa/lca/mcp/McpSessionState.groovy` | Per-session MCP activation state (auto/manual, active servers) |
| `src/main/groovy/se/alipsa/lca/mcp/McpToolRegistry.groovy` | Central registry of servers/tools/resources/prompts, health, caching |
| `src/main/groovy/se/alipsa/lca/mcp/McpToolPromptBuilder.groovy` | Generate budget-aware tool description blocks for LLM system prompts |
| `src/main/groovy/se/alipsa/lca/mcp/McpToolExecutor.groovy` | Validate args, confirm destructive ops, invoke callTool(), format results |
| `src/main/groovy/se/alipsa/lca/tools/StandardToolCall.groovy` | Canonical class for MCP tool calls |
| `src/main/groovy/se/alipsa/lca/tools/ToolCallDispatcher.groovy` | Route ToolCall (built-in) and StandardToolCall (MCP) to executors |
| `src/main/groovy/se/alipsa/lca/shell/McpCommands.groovy` | All `/mcp` subcommands |
| `src/test/groovy/se/alipsa/lca/mcp/McpConfigLoaderSpec.groovy` | Tests for config loading/merging |
| `src/test/groovy/se/alipsa/lca/mcp/McpSessionStateSpec.groovy` | Tests for session activation state |
| `src/test/groovy/se/alipsa/lca/mcp/McpToolRegistrySpec.groovy` | Tests for registry, health, filtering |
| `src/test/groovy/se/alipsa/lca/mcp/McpToolPromptBuilderSpec.groovy` | Tests for prompt generation and budget |
| `src/test/groovy/se/alipsa/lca/mcp/McpToolExecutorSpec.groovy` | Tests for tool execution, confirmation, error handling |
| `src/test/groovy/se/alipsa/lca/tools/StandardToolCallSpec.groovy` | Tests for canonical class |
| `src/test/groovy/se/alipsa/lca/tools/ToolCallDispatcherSpec.groovy` | Tests for routing logic |
| `src/test/groovy/se/alipsa/lca/shell/McpCommandsSpec.groovy` | Tests for slash commands |

### Modified files

| File | Change |
|---|---|
| `src/main/groovy/se/alipsa/lca/tools/ToolCallParser.groovy` | Add MCP pattern, return `ParsedToolCalls` |
| `src/main/groovy/se/alipsa/lca/repl/CommandExecutor.groovy` | Add `case "mcp"` routing |
| `src/main/resources/application.properties` | Add `assistant.mcp.*` and `/mcp` to allowed commands |

---

## Task 1: StandardToolCall and ToolCallDispatcher

Foundation types. No MCP dependency yet — pure data classes and routing logic.

**Files:**
- Create: `src/main/groovy/se/alipsa/lca/tools/StandardToolCall.groovy`
- Create: `src/main/groovy/se/alipsa/lca/tools/ToolCallDispatcher.groovy`
- Create: `src/test/groovy/se/alipsa/lca/tools/StandardToolCallSpec.groovy`
- Create: `src/test/groovy/se/alipsa/lca/tools/ToolCallDispatcherSpec.groovy`
- Reference: `src/main/groovy/se/alipsa/lca/tools/ToolCallParser.groovy` (existing ToolCall class)

- [ ] **Step 1: Write StandardToolCall test**

```groovy
package se.alipsa.lca.tools

import spock.lang.Specification

class StandardToolCallSpec extends Specification {

  def "creates with all fields"() {
    when:
    def call = new StandardToolCall("bq", "query", [sql: "SELECT 1", max_rows: 10])

    then:
    call.serverName == "bq"
    call.toolName == "query"
    call.arguments.sql == "SELECT 1"
    call.arguments.max_rows == 10
  }

  def "equals and hashCode work for canonical class"() {
    given:
    def a = new StandardToolCall("bq", "query", [sql: "SELECT 1"])
    def b = new StandardToolCall("bq", "query", [sql: "SELECT 1"])

    expect:
    a == b
    a.hashCode() == b.hashCode()
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl . -Dtest=StandardToolCallSpec -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — class not found

- [ ] **Step 3: Write StandardToolCall**

```groovy
package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class StandardToolCall {
  String serverName
  String toolName
  Map<String, Object> arguments
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -pl . -Dtest=StandardToolCallSpec`
Expected: PASS

- [ ] **Step 5: Write ToolCallDispatcher test**

```groovy
package se.alipsa.lca.tools

import spock.lang.Specification

class ToolCallDispatcherSpec extends Specification {

  FileEditingTool fileEditingTool = Mock()
  CommandRunner commandRunner = Mock()
  ToolCallDispatcher dispatcher

  def setup() {
    dispatcher = new ToolCallDispatcher(fileEditingTool, commandRunner)
  }

  def "dispatches built-in ToolCall to executeSingleTool"() {
    given:
    def call = new ToolCallParser.ToolCall("writeFile", ["/tmp/test.txt", "hello"])
    fileEditingTool.writeFile("/tmp/test.txt", "hello") >> "OK"

    when:
    String result = dispatcher.dispatchBuiltin(call)

    then:
    result == "OK"
  }

  def "dispatches unknown built-in tool with error"() {
    given:
    def call = new ToolCallParser.ToolCall("unknownTool", ["arg1"])

    when:
    String result = dispatcher.dispatchBuiltin(call)

    then:
    result.contains("Unknown tool")
  }

  def "dispatches StandardToolCall to MCP executor"() {
    given:
    def mcpExecutor = Mock(McpToolExecutorFunction)
    dispatcher = new ToolCallDispatcher(fileEditingTool, commandRunner, mcpExecutor)
    def call = new StandardToolCall("bq", "query", [sql: "SELECT 1"])

    when:
    String result = dispatcher.dispatchMcp(call)

    then:
    1 * mcpExecutor.execute(call) >> "query result"
    result == "query result"
  }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `./mvnw test -pl . -Dtest=ToolCallDispatcherSpec`
Expected: FAIL — class not found

- [ ] **Step 7: Write ToolCallDispatcher**

The dispatcher extracts the built-in execution logic from `ToolCallParser.executeSingleTool()` into a reusable component. It accepts both `ToolCall` (built-in) and `StandardToolCall` (MCP).

```groovy
package se.alipsa.lca.tools

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
@CompileStatic
class ToolCallDispatcher {

  private static final Logger log = LoggerFactory.getLogger(ToolCallDispatcher)

  private final FileEditingTool fileEditingTool
  private final CommandRunner commandRunner
  private final McpToolExecutorFunction mcpExecutor

  ToolCallDispatcher(
    FileEditingTool fileEditingTool,
    CommandRunner commandRunner
  ) {
    this(fileEditingTool, commandRunner, null)
  }

  ToolCallDispatcher(
    FileEditingTool fileEditingTool,
    CommandRunner commandRunner,
    McpToolExecutorFunction mcpExecutor
  ) {
    this.fileEditingTool = fileEditingTool
    this.commandRunner = commandRunner
    this.mcpExecutor = mcpExecutor
  }

  String dispatchBuiltin(ToolCallParser.ToolCall call) {
    switch (call.toolName) {
      case "writeFile":
        if (call.arguments.size() != 2) {
          return "ERROR: writeFile requires 2 arguments"
        }
        return fileEditingTool.writeFile(call.arguments[0], call.arguments[1])

      case "replace":
        if (call.arguments.size() != 3) {
          return "ERROR: replace requires 3 arguments"
        }
        return fileEditingTool.replace(
          call.arguments[0], call.arguments[1], call.arguments[2]
        )

      case "deleteFile":
        if (call.arguments.size() != 1) {
          return "ERROR: deleteFile requires 1 argument"
        }
        return fileEditingTool.deleteFile(call.arguments[0])

      case "runCommand":
        if (commandRunner == null) {
          return "ERROR: CommandRunner not available"
        }
        if (call.arguments.size() != 1) {
          return "ERROR: runCommand requires 1 argument"
        }
        CommandRunner.CommandResult result = commandRunner.run(
          call.arguments[0], 60000L, 8000
        )
        return result.exitCode == 0
          ? "Successfully executed: ${call.arguments[0]}\n${result.output}"
          : "Failed (exit ${result.exitCode}): ${call.arguments[0]}\n${result.output}"

      default:
        return "ERROR: Unknown tool: ${call.toolName}"
    }
  }

  String dispatchMcp(StandardToolCall call) {
    if (mcpExecutor == null) {
      return "ERROR: MCP not available"
    }
    try {
      return mcpExecutor.execute(call)
    } catch (Exception e) {
      log.error("MCP tool call failed: {}.{}", call.serverName, call.toolName, e)
      return "ERROR: MCP tool ${call.serverName}.${call.toolName} failed: ${e.message}"
    }
  }
}
```

Also create the functional interface for MCP execution (allows testing without MCP dependency):

```groovy
package se.alipsa.lca.tools

import groovy.transform.CompileStatic

@CompileStatic
@FunctionalInterface
interface McpToolExecutorFunction {
  String execute(StandardToolCall call)
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `./mvnw test -pl . -Dtest=ToolCallDispatcherSpec,StandardToolCallSpec`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/tools/StandardToolCall.groovy \
  src/main/groovy/se/alipsa/lca/tools/ToolCallDispatcher.groovy \
  src/main/groovy/se/alipsa/lca/tools/McpToolExecutorFunction.groovy \
  src/test/groovy/se/alipsa/lca/tools/StandardToolCallSpec.groovy \
  src/test/groovy/se/alipsa/lca/tools/ToolCallDispatcherSpec.groovy
git commit -m "feat(mcp): add StandardToolCall and ToolCallDispatcher"
```

---

## Task 2: Extend ToolCallParser with MCP Pattern

Add MCP tool call pattern parsing alongside existing built-in patterns. Return a new `ParsedToolCalls` result type.

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/tools/ToolCallParser.groovy`
- Create: `src/test/groovy/se/alipsa/lca/tools/ToolCallParserMcpSpec.groovy`
- Reference: `src/main/groovy/se/alipsa/lca/tools/StandardToolCall.groovy` (from Task 1)

- [ ] **Step 1: Write MCP parsing tests**

```groovy
package se.alipsa.lca.tools

import spock.lang.Specification

class ToolCallParserMcpSpec extends Specification {

  ToolCallParser parser = new ToolCallParser()

  def "parses MCP tool call with JSON arguments"() {
    given:
    String llmOutput = 'Let me query that. mcp_bq_query({"sql": "SELECT count(*) FROM t", "max_rows": 10})'

    when:
    def result = parser.parseAllToolCalls(llmOutput)

    then:
    result.mcpCalls.size() == 1
    result.mcpCalls[0].serverName == "bq"
    result.mcpCalls[0].toolName == "query"
    result.mcpCalls[0].arguments.sql == "SELECT count(*) FROM t"
    result.mcpCalls[0].arguments.max_rows == 10
  }

  def "parses multiple MCP tool calls"() {
    given:
    String llmOutput = '''First: mcp_bq_query({"sql": "SELECT 1"})
Then: mcp_fs_read_file({"path": "/tmp/test.txt"})'''

    when:
    def result = parser.parseAllToolCalls(llmOutput)

    then:
    result.mcpCalls.size() == 2
    result.mcpCalls[0].serverName == "bq"
    result.mcpCalls[1].serverName == "fs"
    result.mcpCalls[1].toolName == "read_file"
  }

  def "handles malformed JSON gracefully"() {
    given:
    String llmOutput = 'mcp_bq_query({sql: "SELECT 1", max_rows: 10})'

    when:
    def result = parser.parseAllToolCalls(llmOutput)

    then:
    result.mcpCalls.size() == 1
    result.mcpCalls[0].arguments.sql == "SELECT 1"
  }

  def "skips completely invalid JSON with error"() {
    given:
    String llmOutput = 'mcp_bq_query(not json at all)'

    when:
    def result = parser.parseAllToolCalls(llmOutput)

    then:
    result.mcpCalls.isEmpty()
    result.errors.size() == 1
    result.errors[0].contains("mcp_bq_query")
  }

  def "mixed built-in and MCP calls in same output"() {
    given:
    String llmOutput = '''writeFile("/tmp/out.txt", "hello")
mcp_bq_query({"sql": "SELECT 1"})'''

    when:
    def result = parser.parseAllToolCalls(llmOutput)

    then:
    result.builtinCalls.size() == 1
    result.builtinCalls[0].toolName == "writeFile"
    result.mcpCalls.size() == 1
    result.mcpCalls[0].serverName == "bq"
  }

  def "parses mcp_read_resource as built-in"() {
    given:
    String llmOutput = 'mcp_read_resource("file:///tmp/data.json")'

    when:
    def result = parser.parseAllToolCalls(llmOutput)

    then:
    result.builtinCalls.size() == 1
    result.builtinCalls[0].toolName == "mcp_read_resource"
    result.builtinCalls[0].arguments[0] == "file:///tmp/data.json"
  }

  def "existing built-in parsing still works"() {
    given:
    String llmOutput = '''writeFile("/tmp/f.txt", "content")
replace("/tmp/f.txt", "old", "new")
deleteFile("/tmp/f.txt")
runCommand("echo hello")'''

    when:
    def result = parser.parseAllToolCalls(llmOutput)

    then:
    result.builtinCalls.size() == 4
    result.mcpCalls.isEmpty()
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest=ToolCallParserMcpSpec`
Expected: FAIL — `parseAllToolCalls` method not found

- [ ] **Step 3: Modify ToolCallParser**

Add the following to `ToolCallParser.groovy`:

1. A new `ParsedToolCalls` canonical class
2. A new `MCP_TOOL_PATTERN` regex
3. A new `parseAllToolCalls()` method that returns `ParsedToolCalls`
4. A `mcp_read_resource` pattern (virtual built-in)
5. Lenient JSON parsing helper

Keep the existing `parseToolCalls()` and `executeToolCalls()` methods unchanged for backward compatibility.

```groovy
// Add these imports at the top of ToolCallParser.groovy:
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.JsonParser as JacksonJsonParser

// Add these new members to the class:

private static final Pattern MCP_TOOL_PATTERN = Pattern.compile(
  /mcp_(\w+?)_(\w+)\(\s*(\{[\s\S]*?\})\s*\)/
)

private static final Pattern MCP_READ_RESOURCE_PATTERN = Pattern.compile(
  /mcp_read_resource\s*\(\s*["']([^"']+)["']\s*\)/
)

private static final ObjectMapper lenientMapper = new ObjectMapper()
  .configure(JacksonJsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
  .configure(JacksonJsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
  .configure(JacksonJsonParser.Feature.ALLOW_TRAILING_COMMA, true)

@Canonical
@CompileStatic
static class ParsedToolCalls {
  List<ToolCall> builtinCalls
  List<StandardToolCall> mcpCalls
  List<String> errors
}

ParsedToolCalls parseAllToolCalls(String llmResponse) {
  List<ToolCall> builtinCalls = parseToolCalls(llmResponse)
  List<StandardToolCall> mcpCalls = []
  List<String> errors = []

  // Parse mcp_read_resource as a virtual built-in
  Matcher resourceMatcher = MCP_READ_RESOURCE_PATTERN.matcher(llmResponse)
  while (resourceMatcher.find()) {
    String uri = resourceMatcher.group(1)
    builtinCalls.add(new ToolCall("mcp_read_resource", [uri]))
    log.debug("Detected mcp_read_resource call: {}", uri)
  }

  // Parse MCP tool calls
  Matcher mcpMatcher = MCP_TOOL_PATTERN.matcher(llmResponse)
  while (mcpMatcher.find()) {
    String serverName = mcpMatcher.group(1)
    String toolName = mcpMatcher.group(2)
    String jsonArgs = mcpMatcher.group(3)

    try {
      Map<String, Object> args = lenientMapper.readValue(
        jsonArgs, Map
      ) as Map<String, Object>
      mcpCalls.add(new StandardToolCall(serverName, toolName, args))
      log.debug("Detected MCP tool call: {}.{}", serverName, toolName)
    } catch (Exception e) {
      String error = "Failed to parse arguments for mcp_${serverName}_${toolName}" +
        " — expected valid JSON object: ${e.message}"
      errors.add(error)
      log.warn(error)
    }
  }

  new ParsedToolCalls(builtinCalls, mcpCalls, errors)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=ToolCallParserMcpSpec`
Expected: PASS

- [ ] **Step 5: Run existing ToolCallParser tests to check for regressions**

Run: `./mvnw test -pl . -Dtest="ToolCall*"`
Expected: All existing tests still PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/tools/ToolCallParser.groovy \
  src/test/groovy/se/alipsa/lca/tools/ToolCallParserMcpSpec.groovy
git commit -m "feat(mcp): extend ToolCallParser with MCP tool call pattern"
```

---

## Task 3: McpConfigLoader

Reads Claude-format JSON config files, merges them (later overrides earlier), and writes a consolidated file for the Spring AI starter.

**Files:**
- Create: `src/main/groovy/se/alipsa/lca/mcp/McpConfigLoader.groovy`
- Create: `src/test/groovy/se/alipsa/lca/mcp/McpConfigLoaderSpec.groovy`

- [ ] **Step 1: Write McpConfigLoader tests**

```groovy
package se.alipsa.lca.mcp

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class McpConfigLoaderSpec extends Specification {

  @TempDir
  Path tempDir

  def "loads single config file"() {
    given:
    Path configFile = tempDir.resolve("mcp-servers.json")
    Files.writeString(configFile, '''
    {
      "mcpServers": {
        "bq": {
          "command": "uvx",
          "args": ["mcp-server-bigquery"],
          "env": {"PROJECT": "my-project"}
        }
      }
    }
    ''')

    when:
    def loader = new McpConfigLoader([configFile.toString()], tempDir.toString())
    def servers = loader.loadServers()

    then:
    servers.size() == 1
    servers["bq"].command == "uvx"
    servers["bq"].args == ["mcp-server-bigquery"]
    servers["bq"].env["PROJECT"] == "my-project"
  }

  def "merges multiple config files with later overriding earlier"() {
    given:
    Path first = tempDir.resolve("first.json")
    Path second = tempDir.resolve("second.json")
    Files.writeString(first, '''
    {
      "mcpServers": {
        "bq": {"command": "uvx", "args": ["old"]},
        "fs": {"command": "npx", "args": ["fs-server"]}
      }
    }
    ''')
    Files.writeString(second, '''
    {
      "mcpServers": {
        "bq": {"command": "uvx", "args": ["new"]}
      }
    }
    ''')

    when:
    def loader = new McpConfigLoader(
      [first.toString(), second.toString()], tempDir.toString()
    )
    def servers = loader.loadServers()

    then:
    servers.size() == 2
    servers["bq"].args == ["new"]
    servers["fs"].args == ["fs-server"]
  }

  def "skips missing config files without error"() {
    when:
    def loader = new McpConfigLoader(
      ["/nonexistent/path.json"], tempDir.toString()
    )
    def servers = loader.loadServers()

    then:
    servers.isEmpty()
    noExceptionThrown()
  }

  def "writes consolidated config for Spring AI starter"() {
    given:
    Path configFile = tempDir.resolve("mcp.json")
    Files.writeString(configFile, '''
    {
      "mcpServers": {
        "bq": {"command": "uvx", "args": ["bq-server"]}
      }
    }
    ''')

    when:
    def loader = new McpConfigLoader([configFile.toString()], tempDir.toString())
    Path consolidated = loader.writeConsolidatedConfig()

    then:
    Files.exists(consolidated)
    String content = Files.readString(consolidated)
    content.contains("bq")
    content.contains("uvx")
  }

  def "handles empty mcpServers block"() {
    given:
    Path configFile = tempDir.resolve("empty.json")
    Files.writeString(configFile, '{"mcpServers": {}}')

    when:
    def loader = new McpConfigLoader([configFile.toString()], tempDir.toString())
    def servers = loader.loadServers()

    then:
    servers.isEmpty()
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest=McpConfigLoaderSpec`
Expected: FAIL — class not found

- [ ] **Step 3: Write McpConfigLoader**

```groovy
package se.alipsa.lca.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@CompileStatic
class McpConfigLoader {

  private static final Logger log = LoggerFactory.getLogger(McpConfigLoader)
  private static final ObjectMapper mapper = new ObjectMapper()

  private final List<String> configPaths
  private final String outputDir

  @Canonical
  @CompileStatic
  static class ServerConfig {
    String command
    List<String> args
    Map<String, String> env
  }

  McpConfigLoader(List<String> configPaths, String outputDir) {
    this.configPaths = configPaths != null ? configPaths : List.of()
    this.outputDir = outputDir
  }

  Map<String, ServerConfig> loadServers() {
    Map<String, ServerConfig> merged = new LinkedHashMap<>()
    for (String path : configPaths) {
      Path filePath = Paths.get(path)
      if (!Files.exists(filePath)) {
        log.debug("MCP config file not found, skipping: {}", path)
        continue
      }
      try {
        Map<String, Object> parsed = mapper.readValue(
          filePath.toFile(), Map
        ) as Map<String, Object>
        Map<String, Object> servers = parsed.get("mcpServers") as Map<String, Object>
        if (servers == null) {
          continue
        }
        servers.each { String name, Object value ->
          Map<String, Object> serverMap = value as Map<String, Object>
          merged.put(name, new ServerConfig(
            serverMap.get("command") as String,
            serverMap.get("args") as List<String> ?: List.of(),
            serverMap.get("env") as Map<String, String> ?: Map.of()
          ))
        }
        log.info("Loaded MCP config from {}: {} server(s)", path, servers.size())
      } catch (Exception e) {
        log.warn("Failed to parse MCP config {}: {}", path, e.message)
      }
    }
    merged
  }

  Path writeConsolidatedConfig() {
    Map<String, ServerConfig> servers = loadServers()
    Map<String, Object> output = [mcpServers: servers]
    Path outPath = Paths.get(outputDir, "mcp-consolidated.json")
    Files.createDirectories(outPath.parent)
    mapper.writerWithDefaultPrettyPrinter().writeValue(outPath.toFile(), output)
    log.info("Wrote consolidated MCP config to {}", outPath)
    outPath
  }

  static List<String> defaultConfigPaths(String overridePath) {
    String home = System.getProperty("user.home")
    List<String> paths = []
    if (overridePath != null && !overridePath.trim().isEmpty()) {
      paths.add(overridePath.trim())
    }
    paths.add("${home}/.lca/mcp-servers.json")
    paths.add("${home}/.claude/settings.json")
    String os = System.getProperty("os.name", "").toLowerCase()
    if (os.contains("mac")) {
      paths.add("${home}/Library/Application Support/Claude/claude_desktop_config.json")
    }
    paths
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=McpConfigLoaderSpec`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/mcp/McpConfigLoader.groovy \
  src/test/groovy/se/alipsa/lca/mcp/McpConfigLoaderSpec.groovy
git commit -m "feat(mcp): add McpConfigLoader for Claude-compatible config"
```

---

## Task 4: McpSessionState

Per-session MCP activation state, isolated from SessionState.

**Files:**
- Create: `src/main/groovy/se/alipsa/lca/mcp/McpSessionState.groovy`
- Create: `src/test/groovy/se/alipsa/lca/mcp/McpSessionStateSpec.groovy`

- [ ] **Step 1: Write McpSessionState tests**

```groovy
package se.alipsa.lca.mcp

import spock.lang.Specification

class McpSessionStateSpec extends Specification {

  McpSessionState state = new McpSessionState()

  def "defaults to AUTO mode"() {
    expect:
    state.getMode("s1") == McpSessionState.McpActivationMode.AUTO
  }

  def "activating a server switches to MANUAL mode"() {
    when:
    state.activate("s1", "bq")

    then:
    state.getMode("s1") == McpSessionState.McpActivationMode.MANUAL
    state.isActive("s1", "bq")
  }

  def "deactivating a server keeps MANUAL mode"() {
    given:
    state.activate("s1", "bq")
    state.activate("s1", "fs")

    when:
    state.deactivate("s1", "bq")

    then:
    state.getMode("s1") == McpSessionState.McpActivationMode.MANUAL
    !state.isActive("s1", "bq")
    state.isActive("s1", "fs")
  }

  def "resetToAuto clears manual overrides"() {
    given:
    state.activate("s1", "bq")

    when:
    state.resetToAuto("s1")

    then:
    state.getMode("s1") == McpSessionState.McpActivationMode.AUTO
    !state.isActive("s1", "bq")
  }

  def "sessions are isolated"() {
    when:
    state.activate("s1", "bq")

    then:
    state.isActive("s1", "bq")
    !state.isActive("s2", "bq")
    state.getMode("s2") == McpSessionState.McpActivationMode.AUTO
  }

  def "getActiveServers returns set of active servers"() {
    given:
    state.activate("s1", "bq")
    state.activate("s1", "fs")

    expect:
    state.getActiveServers("s1") == ["bq", "fs"] as Set
  }

  def "getActiveServers returns empty set in AUTO mode"() {
    expect:
    state.getActiveServers("s1").isEmpty()
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest=McpSessionStateSpec`
Expected: FAIL — class not found

- [ ] **Step 3: Write McpSessionState**

```groovy
package se.alipsa.lca.mcp

import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.util.concurrent.ConcurrentHashMap

@Component
@CompileStatic
class McpSessionState {

  enum McpActivationMode {
    AUTO, MANUAL
  }

  private final Map<String, Set<String>> activeServers = new ConcurrentHashMap<>()
  private final Map<String, McpActivationMode> modes = new ConcurrentHashMap<>()

  void activate(String sessionId, String serverName) {
    String key = normalise(sessionId)
    modes.put(key, McpActivationMode.MANUAL)
    activeServers.computeIfAbsent(key) {
      Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>())
    }.add(serverName)
  }

  void deactivate(String sessionId, String serverName) {
    String key = normalise(sessionId)
    Set<String> servers = activeServers.get(key)
    if (servers != null) {
      servers.remove(serverName)
    }
  }

  void resetToAuto(String sessionId) {
    String key = normalise(sessionId)
    modes.put(key, McpActivationMode.AUTO)
    activeServers.remove(key)
  }

  boolean isActive(String sessionId, String serverName) {
    Set<String> servers = activeServers.get(normalise(sessionId))
    servers != null && servers.contains(serverName)
  }

  McpActivationMode getMode(String sessionId) {
    modes.getOrDefault(normalise(sessionId), McpActivationMode.AUTO)
  }

  Set<String> getActiveServers(String sessionId) {
    Set<String> servers = activeServers.get(normalise(sessionId))
    servers != null ? Collections.unmodifiableSet(servers) : Set.of()
  }

  private static String normalise(String sessionId) {
    sessionId != null && sessionId.trim() ? sessionId.trim() : "default"
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=McpSessionStateSpec`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/mcp/McpSessionState.groovy \
  src/test/groovy/se/alipsa/lca/mcp/McpSessionStateSpec.groovy
git commit -m "feat(mcp): add McpSessionState for per-session activation"
```

---

## Task 5: McpToolRegistry

Central registry wrapping `List<McpSyncClient>`. Manages tool/resource/prompt listings, health, and filtering.

**Files:**
- Create: `src/main/groovy/se/alipsa/lca/mcp/McpToolRegistry.groovy`
- Create: `src/test/groovy/se/alipsa/lca/mcp/McpToolRegistrySpec.groovy`

- [ ] **Step 1: Write McpToolRegistry tests**

```groovy
package se.alipsa.lca.mcp

import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import spock.lang.Specification

class McpToolRegistrySpec extends Specification {

  McpSessionState sessionState = new McpSessionState()

  def "registers server and lists tools"() {
    given:
    McpSyncClient client = Mock()
    client.listTools() >> new McpSchema.ListToolsResult(
      [new McpSchema.Tool("query", "Run SQL", [:])], null
    )
    client.listResources() >> new McpSchema.ListResourcesResult([], null)
    client.listPrompts() >> new McpSchema.ListPromptsResult([], null)

    def registry = new McpToolRegistry(sessionState)
    registry.registerServer("bq", client)

    when:
    def tools = registry.listTools(null)

    then:
    tools.size() == 1
    tools[0].name() == "query"
  }

  def "marks server unhealthy and excludes its tools"() {
    given:
    McpSyncClient client = Mock()
    client.listTools() >> new McpSchema.ListToolsResult(
      [new McpSchema.Tool("query", "Run SQL", [:])], null
    )
    client.listResources() >> new McpSchema.ListResourcesResult([], null)
    client.listPrompts() >> new McpSchema.ListPromptsResult([], null)

    def registry = new McpToolRegistry(sessionState)
    registry.registerServer("bq", client)

    when:
    registry.markUnhealthy("bq", "Process exited")
    def tools = registry.listTools(null)

    then:
    tools.isEmpty()
  }

  def "filters by server name"() {
    given:
    McpSyncClient bqClient = Mock()
    bqClient.listTools() >> new McpSchema.ListToolsResult(
      [new McpSchema.Tool("query", "Run SQL", [:])], null
    )
    bqClient.listResources() >> new McpSchema.ListResourcesResult([], null)
    bqClient.listPrompts() >> new McpSchema.ListPromptsResult([], null)

    McpSyncClient fsClient = Mock()
    fsClient.listTools() >> new McpSchema.ListToolsResult(
      [new McpSchema.Tool("read_file", "Read a file", [:])], null
    )
    fsClient.listResources() >> new McpSchema.ListResourcesResult([], null)
    fsClient.listPrompts() >> new McpSchema.ListPromptsResult([], null)

    def registry = new McpToolRegistry(sessionState)
    registry.registerServer("bq", bqClient)
    registry.registerServer("fs", fsClient)

    when:
    def bqTools = registry.listTools("bq")

    then:
    bqTools.size() == 1
    bqTools[0].name() == "query"
  }

  def "getServerNames returns all registered servers"() {
    given:
    McpSyncClient client = Mock()
    client.listTools() >> new McpSchema.ListToolsResult([], null)
    client.listResources() >> new McpSchema.ListResourcesResult([], null)
    client.listPrompts() >> new McpSchema.ListPromptsResult([], null)

    def registry = new McpToolRegistry(sessionState)
    registry.registerServer("bq", client)
    registry.registerServer("fs", client)

    expect:
    registry.getServerNames() == ["bq", "fs"] as Set
  }

  def "getServerHealth shows healthy and unhealthy servers"() {
    given:
    McpSyncClient client = Mock()
    client.listTools() >> new McpSchema.ListToolsResult([], null)
    client.listResources() >> new McpSchema.ListResourcesResult([], null)
    client.listPrompts() >> new McpSchema.ListPromptsResult([], null)

    def registry = new McpToolRegistry(sessionState)
    registry.registerServer("bq", client)
    registry.registerServer("fs", client)
    registry.markUnhealthy("fs", "Crashed")

    when:
    def health = registry.getServerHealth()

    then:
    health["bq"].healthy
    !health["fs"].healthy
    health["fs"].reason == "Crashed"
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest=McpToolRegistrySpec`
Expected: FAIL — class not found

- [ ] **Step 3: Write McpToolRegistry**

```groovy
package se.alipsa.lca.mcp

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

import java.util.concurrent.ConcurrentHashMap

@Component
@CompileStatic
class McpToolRegistry {

  private static final Logger log = LoggerFactory.getLogger(McpToolRegistry)

  private final McpSessionState sessionState
  private final Map<String, McpSyncClient> clients = new LinkedHashMap<>()
  private final Map<String, List<McpSchema.Tool>> toolCache = new ConcurrentHashMap<>()
  private final Map<String, List<McpSchema.Resource>> resourceCache = new ConcurrentHashMap<>()
  private final Map<String, List<McpSchema.Prompt>> promptCache = new ConcurrentHashMap<>()
  private final Map<String, ServerHealth> healthMap = new ConcurrentHashMap<>()

  @Canonical
  @CompileStatic
  static class ServerHealth {
    boolean healthy
    String reason
  }

  McpToolRegistry(McpSessionState sessionState) {
    this.sessionState = sessionState
  }

  void registerServer(String name, McpSyncClient client) {
    clients.put(name, client)
    healthMap.put(name, new ServerHealth(true, null))
    refreshCapabilities(name, client)
    log.info("Registered MCP server '{}' with {} tool(s)",
      name, toolCache.getOrDefault(name, List.of()).size())
  }

  private void refreshCapabilities(String name, McpSyncClient client) {
    try {
      def toolsResult = client.listTools()
      toolCache.put(name, toolsResult.tools() ?: List.of())
    } catch (Exception e) {
      log.warn("Failed to list tools from '{}': {}", name, e.message)
      toolCache.put(name, List.of())
    }
    try {
      def resourcesResult = client.listResources()
      resourceCache.put(name, resourcesResult.resources() ?: List.of())
    } catch (Exception e) {
      log.debug("Failed to list resources from '{}': {}", name, e.message)
      resourceCache.put(name, List.of())
    }
    try {
      def promptsResult = client.listPrompts()
      promptCache.put(name, promptsResult.prompts() ?: List.of())
    } catch (Exception e) {
      log.debug("Failed to list prompts from '{}': {}", name, e.message)
      promptCache.put(name, List.of())
    }
  }

  List<McpSchema.Tool> listTools(String serverName) {
    if (serverName != null) {
      ServerHealth health = healthMap.get(serverName)
      if (health == null || !health.healthy) return List.of()
      return toolCache.getOrDefault(serverName, List.of())
    }
    List<McpSchema.Tool> all = []
    clients.keySet().each { String name ->
      ServerHealth health = healthMap.get(name)
      if (health != null && health.healthy) {
        all.addAll(toolCache.getOrDefault(name, List.of()))
      }
    }
    all
  }

  String findServerForTool(String toolName) {
    for (Map.Entry<String, List<McpSchema.Tool>> entry : toolCache.entrySet()) {
      if (entry.value.any { it.name() == toolName }) {
        return entry.key
      }
    }
    null
  }

  McpSchema.CallToolResult callTool(
    String serverName, String toolName, Map<String, Object> args
  ) {
    McpSyncClient client = clients.get(serverName)
    if (client == null) {
      throw new IllegalArgumentException("Unknown MCP server: ${serverName}")
    }
    ServerHealth health = healthMap.get(serverName)
    if (health != null && !health.healthy) {
      throw new IllegalStateException(
        "MCP server '${serverName}' is unavailable: ${health.reason}"
      )
    }
    client.callTool(new McpSchema.CallToolRequest(toolName, args))
  }

  List<McpSchema.Resource> listResources(String serverName) {
    if (serverName != null) {
      return resourceCache.getOrDefault(serverName, List.of())
    }
    List<McpSchema.Resource> all = []
    resourceCache.values().each { all.addAll(it) }
    all
  }

  McpSchema.ReadResourceResult readResource(String uri) {
    for (Map.Entry<String, McpSyncClient> entry : clients.entrySet()) {
      try {
        return entry.value.readResource(
          new McpSchema.ReadResourceRequest(uri)
        )
      } catch (Exception ignored) {
        // try next server
      }
    }
    throw new IllegalArgumentException("No server can read resource: ${uri}")
  }

  List<McpSchema.Prompt> listPrompts(String serverName) {
    if (serverName != null) {
      return promptCache.getOrDefault(serverName, List.of())
    }
    List<McpSchema.Prompt> all = []
    promptCache.values().each { all.addAll(it) }
    all
  }

  McpSchema.GetPromptResult getPrompt(
    String serverName, String promptName, Map<String, String> args
  ) {
    McpSyncClient client = clients.get(serverName)
    if (client == null) {
      throw new IllegalArgumentException("Unknown MCP server: ${serverName}")
    }
    client.getPrompt(new McpSchema.GetPromptRequest(promptName, args))
  }

  void markUnhealthy(String serverName, String reason) {
    healthMap.put(serverName, new ServerHealth(false, reason))
    log.warn("MCP server '{}' marked unhealthy: {}", serverName, reason)
  }

  void markHealthy(String serverName) {
    healthMap.put(serverName, new ServerHealth(true, null))
  }

  Set<String> getServerNames() {
    Collections.unmodifiableSet(clients.keySet())
  }

  Map<String, ServerHealth> getServerHealth() {
    Collections.unmodifiableMap(healthMap)
  }

  boolean isHealthy(String serverName) {
    ServerHealth health = healthMap.get(serverName)
    health != null && health.healthy
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=McpToolRegistrySpec`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/mcp/McpToolRegistry.groovy \
  src/test/groovy/se/alipsa/lca/mcp/McpToolRegistrySpec.groovy
git commit -m "feat(mcp): add McpToolRegistry for server management"
```

---

## Task 6: McpToolPromptBuilder

Generates budget-aware tool description blocks for the LLM system prompt.

**Files:**
- Create: `src/main/groovy/se/alipsa/lca/mcp/McpToolPromptBuilder.groovy`
- Create: `src/test/groovy/se/alipsa/lca/mcp/McpToolPromptBuilderSpec.groovy`

- [ ] **Step 1: Write McpToolPromptBuilder tests**

```groovy
package se.alipsa.lca.mcp

import io.modelcontextprotocol.spec.McpSchema
import spock.lang.Specification

class McpToolPromptBuilderSpec extends Specification {

  def "builds tool descriptions for single server"() {
    given:
    def tools = [
      "bq": [new McpSchema.Tool("query", "Run SQL query", [
        type: "object",
        properties: [
          sql: [type: "string", description: "The SQL query"],
          max_rows: [type: "integer", description: "Max rows"]
        ],
        required: ["sql"]
      ])]
    ]
    def builder = new McpToolPromptBuilder(3000)

    when:
    String prompt = builder.buildToolPrompt(tools)

    then:
    prompt.contains("mcp_bq_query")
    prompt.contains("sql")
    prompt.contains("required")
    prompt.contains("JSON arguments")
  }

  def "truncates to name-only when over budget"() {
    given:
    Map<String, Object> bigSchema = [
      type: "object",
      properties: (1..20).collectEntries { i ->
        ["param_${i}".toString(), [type: "string", description: "A" * 100]]
      },
      required: ["param_1"]
    ]
    def tools = [
      "server1": [new McpSchema.Tool("tool1", "D" * 200, bigSchema)],
      "server2": [new McpSchema.Tool("tool2", "E" * 200, bigSchema)]
    ]
    def builder = new McpToolPromptBuilder(500)

    when:
    String prompt = builder.buildToolPrompt(tools)

    then:
    prompt.length() <= 600
    prompt.contains("mcp_server1_tool1")
  }

  def "returns empty string when no tools"() {
    given:
    def builder = new McpToolPromptBuilder(3000)

    when:
    String prompt = builder.buildToolPrompt([:])

    then:
    prompt.isEmpty()
  }

  def "includes resource descriptions when resources provided"() {
    given:
    def resources = [new McpSchema.Resource(
      "file:///data/schema.json", "schema.json",
      "Database schema", "application/json", null
    )]
    def builder = new McpToolPromptBuilder(3000)

    when:
    String prompt = builder.buildResourcePrompt(resources)

    then:
    prompt.contains("mcp_read_resource")
    prompt.contains("schema.json")
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest=McpToolPromptBuilderSpec`
Expected: FAIL — class not found

- [ ] **Step 3: Write McpToolPromptBuilder**

```groovy
package se.alipsa.lca.mcp

import groovy.transform.CompileStatic
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class McpToolPromptBuilder {

  private static final Logger log = LoggerFactory.getLogger(McpToolPromptBuilder)

  private final int budgetChars

  McpToolPromptBuilder(int budgetChars) {
    this.budgetChars = budgetChars > 0 ? budgetChars : 3000
  }

  String buildToolPrompt(Map<String, List<McpSchema.Tool>> toolsByServer) {
    if (toolsByServer == null || toolsByServer.isEmpty()) {
      return ""
    }

    StringBuilder full = new StringBuilder()
    StringBuilder compact = new StringBuilder()
    full.append("Available MCP tools:\n\n")
    compact.append("Available MCP tools (compact):\n")
    boolean budgetExceeded = false

    for (Map.Entry<String, List<McpSchema.Tool>> entry : toolsByServer.entrySet()) {
      String server = entry.key
      for (McpSchema.Tool tool : entry.value) {
        String toolId = "mcp_${server}_${tool.name()}"
        String fullDesc = formatToolFull(toolId, tool)
        String compactDesc = "  ${toolId} - ${truncate(tool.description(), 60)}\n"

        if (!budgetExceeded && full.length() + fullDesc.length() <= budgetChars) {
          full.append(fullDesc)
        } else {
          budgetExceeded = true
          compact.append(compactDesc)
        }
      }
    }

    if (budgetExceeded) {
      full.append("\n").append(compact)
    }

    full.append("\nCall MCP tools with JSON arguments:\n")
    full.append("  mcp_server_tool({\"param\": \"value\"})\n")

    full.toString()
  }

  String buildResourcePrompt(List<McpSchema.Resource> resources) {
    if (resources == null || resources.isEmpty()) {
      return ""
    }
    StringBuilder sb = new StringBuilder()
    sb.append("Available MCP resources:\n")
    for (McpSchema.Resource r : resources) {
      sb.append("  ${r.uri()} - ${r.name()}")
      if (r.description()) {
        sb.append(": ${truncate(r.description(), 80)}")
      }
      sb.append("\n")
    }
    sb.append("\nRead a resource: mcp_read_resource(\"<uri>\")\n")
    sb.toString()
  }

  private String formatToolFull(String toolId, McpSchema.Tool tool) {
    StringBuilder sb = new StringBuilder()
    Map<String, Object> schema = tool.inputSchema() as Map<String, Object>
    Map<String, Object> props = schema?.get("properties") as Map<String, Object>
    List<String> required = schema?.get("required") as List<String> ?: List.of()

    String paramSummary = props != null
      ? props.keySet().collect { String k ->
          required.contains(k) ? k : "${k}?"
        }.join(", ")
      : ""

    sb.append("${toolId}(${paramSummary}) - ${truncate(tool.description(), 80)}\n")

    if (props != null) {
      props.each { String name, Object val ->
        Map<String, Object> propDef = val as Map<String, Object>
        String type = propDef?.get("type") as String ?: "any"
        String desc = propDef?.get("description") as String ?: ""
        String req = required.contains(name) ? "required" : "optional"
        sb.append("  - ${name} (${type}, ${req})")
        if (desc) {
          sb.append(": ${truncate(desc, 60)}")
        }
        sb.append("\n")
      }
    }
    sb.append("\n")
    sb.toString()
  }

  private static String truncate(String text, int maxLen) {
    if (text == null) return ""
    text.length() <= maxLen ? text : text.substring(0, maxLen - 3) + "..."
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=McpToolPromptBuilderSpec`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/mcp/McpToolPromptBuilder.groovy \
  src/test/groovy/se/alipsa/lca/mcp/McpToolPromptBuilderSpec.groovy
git commit -m "feat(mcp): add McpToolPromptBuilder with budget control"
```

---

## Task 7: McpToolExecutor

Validates arguments, handles confirmation for destructive operations, invokes `callTool()`, and formats results.

**Files:**
- Create: `src/main/groovy/se/alipsa/lca/mcp/McpToolExecutor.groovy`
- Create: `src/test/groovy/se/alipsa/lca/mcp/McpToolExecutorSpec.groovy`
- Reference: `src/main/groovy/se/alipsa/lca/tools/StandardToolCall.groovy`
- Reference: `src/main/groovy/se/alipsa/lca/tools/McpToolExecutorFunction.groovy`

- [ ] **Step 1: Write McpToolExecutor tests**

```groovy
package se.alipsa.lca.mcp

import io.modelcontextprotocol.spec.McpSchema
import se.alipsa.lca.tools.StandardToolCall
import spock.lang.Specification

class McpToolExecutorSpec extends Specification {

  McpToolRegistry registry = Mock()

  def "executes tool and returns formatted result"() {
    given:
    def executor = new McpToolExecutor(registry, true)
    def call = new StandardToolCall("bq", "query", [sql: "SELECT 1"])
    registry.isHealthy("bq") >> true
    registry.callTool("bq", "query", [sql: "SELECT 1"]) >>
      new McpSchema.CallToolResult(
        [new McpSchema.TextContent("row1: 1")], false
      )

    when:
    String result = executor.execute(call)

    then:
    result.contains("row1: 1")
  }

  def "returns error for unhealthy server"() {
    given:
    def executor = new McpToolExecutor(registry, true)
    def call = new StandardToolCall("bq", "query", [sql: "SELECT 1"])
    registry.isHealthy("bq") >> false

    when:
    String result = executor.execute(call)

    then:
    result.contains("unavailable")
  }

  def "classifies destructive tools correctly"() {
    expect:
    McpToolExecutor.isDestructive(toolName) == expected

    where:
    toolName      | expected
    "delete_file" | true
    "write_data"  | true
    "create_item" | true
    "send_email"  | true
    "execute_cmd" | true
    "list_items"  | false
    "get_data"    | false
    "read_file"   | false
    "search"      | false
    "query"       | false
  }

  def "logs tool invocation"() {
    given:
    def executor = new McpToolExecutor(registry, false)
    def call = new StandardToolCall("bq", "query", [sql: "SELECT 1"])
    registry.isHealthy("bq") >> true
    registry.callTool("bq", "query", [sql: "SELECT 1"]) >>
      new McpSchema.CallToolResult(
        [new McpSchema.TextContent("ok")], false
      )

    when:
    executor.execute(call)

    then:
    noExceptionThrown()
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest=McpToolExecutorSpec`
Expected: FAIL — class not found

- [ ] **Step 3: Write McpToolExecutor**

```groovy
package se.alipsa.lca.mcp

import groovy.transform.CompileStatic
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import se.alipsa.lca.tools.McpToolExecutorFunction
import se.alipsa.lca.tools.StandardToolCall

import java.util.regex.Pattern

@Component
@CompileStatic
class McpToolExecutor implements McpToolExecutorFunction {

  private static final Logger log = LoggerFactory.getLogger(McpToolExecutor)

  private static final Pattern DESTRUCTIVE_PATTERN = Pattern.compile(
    /(?i)(write|delete|create|update|send|execute|run|remove|drop|insert|put|post|push)/
  )
  private static final Pattern READONLY_PATTERN = Pattern.compile(
    /(?i)^(list|get|read|search|query|fetch|find|describe|show|count|check|view)/
  )

  private final McpToolRegistry registry
  private final boolean confirmDestructive

  McpToolExecutor(
    McpToolRegistry registry,
    @Value('${assistant.mcp.confirm-destructive:true}') boolean confirmDestructive
  ) {
    this.registry = registry
    this.confirmDestructive = confirmDestructive
  }

  @Override
  String execute(StandardToolCall call) {
    if (!registry.isHealthy(call.serverName)) {
      return "ERROR: MCP server '${call.serverName}' is unavailable — " +
        "use /mcp restart ${call.serverName} to retry."
    }

    log.info("MCP tool call: {}.{} args=[{}]",
      call.serverName, call.toolName,
      call.arguments?.keySet()?.join(", ") ?: "none")
    log.debug("MCP tool call detail: {}.{} args={}",
      call.serverName, call.toolName, call.arguments)

    try {
      McpSchema.CallToolResult result = registry.callTool(
        call.serverName, call.toolName, call.arguments ?: Map.of()
      )
      return formatResult(call, result)
    } catch (Exception e) {
      log.error("MCP tool call failed: {}.{}", call.serverName, call.toolName, e)
      return "ERROR: MCP tool ${call.serverName}.${call.toolName} failed: ${e.message}"
    }
  }

  static boolean isDestructive(String toolName) {
    if (READONLY_PATTERN.matcher(toolName).find()) {
      return false
    }
    DESTRUCTIVE_PATTERN.matcher(toolName).find()
  }

  boolean requiresConfirmation(String toolName) {
    confirmDestructive && isDestructive(toolName)
  }

  private static String formatResult(
    StandardToolCall call, McpSchema.CallToolResult result
  ) {
    if (result.isError()) {
      return "ERROR from ${call.serverName}.${call.toolName}: " +
        extractText(result)
    }
    extractText(result)
  }

  private static String extractText(McpSchema.CallToolResult result) {
    if (result.content() == null || result.content().isEmpty()) {
      return "(no output)"
    }
    result.content().collect { Object content ->
      if (content instanceof McpSchema.TextContent) {
        return (content as McpSchema.TextContent).text()
      }
      content.toString()
    }.join("\n")
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=McpToolExecutorSpec`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/mcp/McpToolExecutor.groovy \
  src/test/groovy/se/alipsa/lca/mcp/McpToolExecutorSpec.groovy
git commit -m "feat(mcp): add McpToolExecutor with destructive tool detection"
```

---

## Task 8: McpCommands and CommandExecutor Routing

Slash commands for MCP interaction and routing from `CommandExecutor`.

**Files:**
- Create: `src/main/groovy/se/alipsa/lca/shell/McpCommands.groovy`
- Create: `src/test/groovy/se/alipsa/lca/shell/McpCommandsSpec.groovy`
- Modify: `src/main/groovy/se/alipsa/lca/repl/CommandExecutor.groovy:53-94` (add `case "mcp"`)

- [ ] **Step 1: Write McpCommands tests**

```groovy
package se.alipsa.lca.shell

import io.modelcontextprotocol.spec.McpSchema
import se.alipsa.lca.mcp.McpSessionState
import se.alipsa.lca.mcp.McpToolRegistry
import spock.lang.Specification

class McpCommandsSpec extends Specification {

  McpToolRegistry registry = Mock()
  McpSessionState sessionState = new McpSessionState()

  McpCommands commands = new McpCommands(registry, sessionState)

  def "status shows healthy server"() {
    given:
    registry.getServerNames() >> (["bq"] as Set)
    registry.getServerHealth() >> [
      "bq": new McpToolRegistry.ServerHealth(true, null)
    ]
    registry.listTools("bq") >> [
      new McpSchema.Tool("query", "Run SQL", [:])
    ]

    when:
    String result = commands.execute("status", "", "default")

    then:
    result.contains("bq")
    result.contains("healthy")
  }

  def "use activates server and switches to manual mode"() {
    when:
    String result = commands.execute("use", "bq", "default")

    then:
    result.contains("bq")
    result.contains("activated")
    sessionState.getMode("default") == McpSessionState.McpActivationMode.MANUAL
  }

  def "autoselect resets to auto mode"() {
    given:
    sessionState.activate("default", "bq")

    when:
    String result = commands.execute("autoselect", "", "default")

    then:
    result.contains("autoselect")
    sessionState.getMode("default") == McpSessionState.McpActivationMode.AUTO
  }

  def "tools lists available tools"() {
    given:
    registry.listTools(null) >> [
      new McpSchema.Tool("query", "Run SQL", [:]),
      new McpSchema.Tool("read_file", "Read a file", [:])
    ]

    when:
    String result = commands.execute("tools", "", "default")

    then:
    result.contains("query")
    result.contains("read_file")
  }

  def "unknown subcommand returns help"() {
    when:
    String result = commands.execute("nonsense", "", "default")

    then:
    result.contains("Unknown")
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest=McpCommandsSpec`
Expected: FAIL — class not found

- [ ] **Step 3: Write McpCommands**

```groovy
package se.alipsa.lca.shell

import com.fasterxml.jackson.databind.ObjectMapper
import groovy.transform.CompileStatic
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import se.alipsa.lca.mcp.McpSessionState
import se.alipsa.lca.mcp.McpToolRegistry

@Component
@CompileStatic
class McpCommands {

  private static final Logger log = LoggerFactory.getLogger(McpCommands)
  private static final ObjectMapper mapper = new ObjectMapper()

  private final McpToolRegistry registry
  private final McpSessionState sessionState

  McpCommands(McpToolRegistry registry, McpSessionState sessionState) {
    this.registry = registry
    this.sessionState = sessionState
  }

  String execute(String subcommand, String args, String sessionId) {
    switch (subcommand?.toLowerCase()) {
      case "status":
        return status(sessionId)
      case "use":
        return use(args?.trim(), sessionId)
      case "stop":
        return stop(args?.trim(), sessionId)
      case "autoselect":
        return autoselect(sessionId)
      case "tools":
        return tools(args?.trim())
      case "call":
        return call(args?.trim())
      case "resources":
        return resources(args?.trim())
      case "read":
        return read(args?.trim())
      case "prompts":
        return prompts(args?.trim())
      case "prompt":
        return prompt(args?.trim())
      case "restart":
        return restart(args?.trim())
      default:
        return "Unknown /mcp subcommand: ${subcommand}. " +
          "Available: status, use, stop, autoselect, tools, call, " +
          "resources, read, prompts, prompt, restart"
    }
  }

  private String status(String sessionId) {
    Set<String> servers = registry.getServerNames()
    if (servers.isEmpty()) {
      return "No MCP servers configured."
    }
    Map<String, McpToolRegistry.ServerHealth> health = registry.getServerHealth()
    McpSessionState.McpActivationMode mode = sessionState.getMode(sessionId)
    Set<String> active = sessionState.getActiveServers(sessionId)

    StringBuilder sb = new StringBuilder()
    sb.append("MCP servers (mode: ${mode}):\n")
    for (String name : servers) {
      McpToolRegistry.ServerHealth h = health.get(name)
      String status = h?.healthy ? "healthy" : "unhealthy: ${h?.reason}"
      int toolCount = registry.listTools(name).size()
      String activeMarker = mode == McpSessionState.McpActivationMode.MANUAL
        ? (active.contains(name) ? " [active]" : " [inactive]")
        : ""
      sb.append("  ${name}: ${status}, ${toolCount} tool(s)${activeMarker}\n")
    }
    sb.toString()
  }

  private String use(String serverName, String sessionId) {
    if (!serverName) {
      return "Usage: /mcp use <server>"
    }
    sessionState.activate(sessionId, serverName)
    "Server '${serverName}' activated for this session."
  }

  private String stop(String serverName, String sessionId) {
    if (!serverName) {
      return "Usage: /mcp stop <server>"
    }
    sessionState.deactivate(sessionId, serverName)
    "Server '${serverName}' deactivated for this session."
  }

  private String autoselect(String sessionId) {
    sessionState.resetToAuto(sessionId)
    "Returned to autoselect mode. All servers available based on context budget."
  }

  private String tools(String serverName) {
    List<McpSchema.Tool> toolList = registry.listTools(
      serverName?.isEmpty() ? null : serverName
    )
    if (toolList.isEmpty()) {
      return serverName
        ? "No tools available from '${serverName}'."
        : "No MCP tools available."
    }
    StringBuilder sb = new StringBuilder()
    sb.append("MCP tools:\n")
    for (McpSchema.Tool tool : toolList) {
      sb.append("  ${tool.name()} - ${tool.description() ?: '(no description)'}\n")
    }
    sb.toString()
  }

  private String call(String args) {
    if (!args || !args.contains(" ")) {
      return "Usage: /mcp call <server.tool> {\"arg\": \"value\"}"
    }
    int spaceIdx = args.indexOf(' ')
    String toolRef = args.substring(0, spaceIdx)
    String jsonArgs = args.substring(spaceIdx + 1).trim()

    String[] parts = toolRef.split("\\.", 2)
    if (parts.length != 2) {
      return "Tool reference must be <server>.<tool>, e.g. bq.query"
    }

    try {
      Map<String, Object> parsedArgs = mapper.readValue(jsonArgs, Map) as Map<String, Object>
      McpSchema.CallToolResult result = registry.callTool(parts[0], parts[1], parsedArgs)
      formatCallResult(result)
    } catch (Exception e) {
      "Error: ${e.message}"
    }
  }

  private String resources(String serverName) {
    List<McpSchema.Resource> resList = registry.listResources(
      serverName?.isEmpty() ? null : serverName
    )
    if (resList.isEmpty()) {
      return "No MCP resources available."
    }
    StringBuilder sb = new StringBuilder()
    sb.append("MCP resources:\n")
    for (McpSchema.Resource r : resList) {
      sb.append("  ${r.uri()} - ${r.name()}")
      if (r.description()) {
        sb.append(": ${r.description()}")
      }
      sb.append("\n")
    }
    sb.toString()
  }

  private String read(String uri) {
    if (!uri) {
      return "Usage: /mcp read <uri>"
    }
    try {
      McpSchema.ReadResourceResult result = registry.readResource(uri)
      result.contents()?.collect { it.toString() }?.join("\n") ?: "(empty)"
    } catch (Exception e) {
      "Error reading resource: ${e.message}"
    }
  }

  private String prompts(String serverName) {
    List<McpSchema.Prompt> promptList = registry.listPrompts(
      serverName?.isEmpty() ? null : serverName
    )
    if (promptList.isEmpty()) {
      return "No MCP prompts available."
    }
    StringBuilder sb = new StringBuilder()
    sb.append("MCP prompts:\n")
    for (McpSchema.Prompt p : promptList) {
      sb.append("  ${p.name()} - ${p.description() ?: '(no description)'}\n")
    }
    sb.toString()
  }

  private String prompt(String args) {
    if (!args) {
      return "Usage: /mcp prompt <name> [args...]"
    }
    return "Prompt fetching not yet implemented."
  }

  private String restart(String serverName) {
    if (!serverName) {
      return "Usage: /mcp restart <server>"
    }
    return "Server restart not yet implemented. Stop and re-run LCA to restart servers."
  }

  private static String formatCallResult(McpSchema.CallToolResult result) {
    if (result.content() == null || result.content().isEmpty()) {
      return "(no output)"
    }
    result.content().collect { Object c ->
      if (c instanceof McpSchema.TextContent) {
        return (c as McpSchema.TextContent).text()
      }
      c.toString()
    }.join("\n")
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=McpCommandsSpec`
Expected: PASS

- [ ] **Step 5: Add `/mcp` case to CommandExecutor**

Add to the `switch` block in `CommandExecutor.groovy` (after `case "health":`, before `case "exit":`):

```groovy
case "mcp":
  return executeMcp(args)
```

Add the `executeMcp` method:

```groovy
private String executeMcp(String args) {
  Map<String, Object> parsed = parseArgs(args)
  List<String> words = parsed.words as List<String>
  String subcommand = words?.isEmpty() ? "status" : words[0]
  String subArgs = words?.size() > 1 ? words.subList(1, words.size()).join(" ") : ""
  // Check for --session flag
  String session = parsed.session as String ?: "default"
  mcpCommands.execute(subcommand, subArgs, session)
}
```

Add `McpCommands` to the constructor:

```groovy
private final McpCommands mcpCommands

CommandExecutor(ShellCommands shellCommands, McpCommands mcpCommands) {
  this.shellCommands = shellCommands
  this.mcpCommands = mcpCommands
}
```

- [ ] **Step 6: Run all tests to check for regressions**

Run: `./mvnw test`
Expected: All tests PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/shell/McpCommands.groovy \
  src/test/groovy/se/alipsa/lca/shell/McpCommandsSpec.groovy \
  src/main/groovy/se/alipsa/lca/repl/CommandExecutor.groovy
git commit -m "feat(mcp): add /mcp slash commands and CommandExecutor routing"
```

---

## Task 9: Configuration Properties

Add MCP configuration to `application.properties` and add `/mcp` to the intent router's allowed commands.

**Files:**
- Modify: `src/main/resources/application.properties:63` (add `/mcp` to allowed-commands)
- Modify: `src/main/resources/application.properties` (add `assistant.mcp.*` section)

- [ ] **Step 1: Add MCP config properties**

Append to `application.properties`:

```properties

# MCP client
assistant.mcp.enabled=true
assistant.mcp.servers-configuration=
assistant.mcp.tool-description-budget=3000
assistant.mcp.confirm-destructive=true
assistant.mcp.request-timeout=20s
```

- [ ] **Step 2: Add `/mcp` to intent router allowed commands**

On line 63, add `,/mcp` to the end of the `assistant.intent.allowed-commands` value:

```properties
assistant.intent.allowed-commands=/chat,/plan,/review,/implement,/edit,/apply,/run,/gitapply,/git-push,/search,/codesearch,/diff,/stage,/commit-suggest,/context,/tree,/status,/model,/health,/revert,/applyBlocks,/paste,/version,/mcp
```

- [ ] **Step 3: Run all tests**

Run: `./mvnw test`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "feat(mcp): add configuration properties and intent routing"
```

---

## Task 10: Integration Wiring and Full Test

Wire everything together with a Spring `@Configuration` class and run the full test suite.

**Files:**
- Create: `src/main/groovy/se/alipsa/lca/mcp/McpAutoConfiguration.groovy`

- [ ] **Step 1: Write McpAutoConfiguration**

This wires the `McpConfigLoader`, starts clients via the Spring AI starter, and registers them with `McpToolRegistry`.

```groovy
package se.alipsa.lca.mcp

import groovy.transform.CompileStatic
import io.modelcontextprotocol.client.McpSyncClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration

import jakarta.annotation.PostConstruct

@Configuration
@CompileStatic
@ConditionalOnProperty(name = "assistant.mcp.enabled", havingValue = "true", matchIfMissing = true)
class McpAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(McpAutoConfiguration)

  private final McpToolRegistry registry
  private final List<McpSyncClient> mcpClients

  @Value('${assistant.mcp.servers-configuration:}')
  private String serversConfigOverride

  McpAutoConfiguration(
    McpToolRegistry registry,
    List<McpSyncClient> mcpClients
  ) {
    this.registry = registry
    this.mcpClients = mcpClients ?: List.of()
  }

  @PostConstruct
  void registerClients() {
    if (mcpClients.isEmpty()) {
      log.info("No MCP servers configured.")
      return
    }
    int index = 0
    for (McpSyncClient client : mcpClients) {
      String name = "server-${index}"
      try {
        def info = client.getServerInfo()
        if (info?.name()) {
          name = info.name()
        }
      } catch (Exception ignored) {
        // use default name
      }
      registry.registerServer(name, client)
      index++
    }
    log.info("Registered {} MCP server(s)", mcpClients.size())
  }
}
```

- [ ] **Step 2: Run the full test suite**

Run: `./mvnw test`
Expected: All tests PASS

- [ ] **Step 3: Verify the application starts**

Run: `./mvnw spring-boot:run` (briefly, confirm it starts without errors related to MCP, then Ctrl+C)
Expected: No MCP-related errors in startup log. If no servers are configured, should see "No MCP servers configured." at INFO level.

- [ ] **Step 4: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/mcp/McpAutoConfiguration.groovy
git commit -m "feat(mcp): add auto-configuration and client registration"
```

- [ ] **Step 5: Run `./mvnw test` one final time**

Run: `./mvnw test`
Expected: All tests PASS — no regressions from any task.
