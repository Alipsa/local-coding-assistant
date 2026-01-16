package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Parses LLM responses to extract tool calls and execute them.
 * This allows any LLM (even without native function calling support) to invoke tools
 * by generating tool call syntax in their text output.
 */
@Component
@CompileStatic
class ToolCallParser {

  private static final Logger log = LoggerFactory.getLogger(ToolCallParser)

  // Patterns to detect tool calls in LLM output
  private static final Pattern WRITE_FILE_PATTERN = Pattern.compile(
    /writeFile\s*\(\s*["']([^"']+)["']\s*,\s*["']((?:[^"'\\]|\\.)*)["']\s*\)/,
    Pattern.DOTALL
  )

  private static final Pattern REPLACE_PATTERN = Pattern.compile(
    /replace\s*\(\s*["']([^"']+)["']\s*,\s*["']((?:[^"'\\]|\\.)*)["']\s*,\s*["']((?:[^"'\\]|\\.)*)["']\s*\)/,
    Pattern.DOTALL
  )

  private static final Pattern DELETE_FILE_PATTERN = Pattern.compile(
    /deleteFile\s*\(\s*["']([^"']+)["']\s*\)/
  )

  private static final Pattern RUN_COMMAND_PATTERN = Pattern.compile(
    /runCommand\s*\(\s*["']((?:[^"'\\]|\\.)*)["']\s*\)/,
    Pattern.DOTALL
  )

  @Canonical
  @CompileStatic
  static class ToolCall {
    String toolName
    List<String> arguments
  }

  /**
   * Parse LLM response and extract tool calls.
   */
  List<ToolCall> parseToolCalls(String llmResponse) {
    List<ToolCall> calls = []

    // Extract writeFile calls
    Matcher writeMatcher = WRITE_FILE_PATTERN.matcher(llmResponse)
    while (writeMatcher.find()) {
      String filePath = writeMatcher.group(1)
      String content = unescapeString(writeMatcher.group(2))
      calls.add(new ToolCall("writeFile", [filePath, content]))
      log.debug("Detected writeFile call: {}", filePath)
    }

    // Extract replace calls
    Matcher replaceMatcher = REPLACE_PATTERN.matcher(llmResponse)
    while (replaceMatcher.find()) {
      String filePath = replaceMatcher.group(1)
      String oldString = unescapeString(replaceMatcher.group(2))
      String newString = unescapeString(replaceMatcher.group(3))
      calls.add(new ToolCall("replace", [filePath, oldString, newString]))
      log.debug("Detected replace call: {}", filePath)
    }

    // Extract deleteFile calls
    Matcher deleteMatcher = DELETE_FILE_PATTERN.matcher(llmResponse)
    while (deleteMatcher.find()) {
      String filePath = deleteMatcher.group(1)
      calls.add(new ToolCall("deleteFile", [filePath]))
      log.debug("Detected deleteFile call: {}", filePath)
    }

    // Extract runCommand calls
    Matcher runMatcher = RUN_COMMAND_PATTERN.matcher(llmResponse)
    while (runMatcher.find()) {
      String command = unescapeString(runMatcher.group(1))
      calls.add(new ToolCall("runCommand", [command]))
      log.debug("Detected runCommand call: {}", command)
    }

    return calls
  }

  /**
   * Execute detected tool calls.
   */
  String executeToolCalls(List<ToolCall> calls, FileEditingTool fileEditingTool) {
    return executeToolCalls(calls, fileEditingTool, null)
  }

  /**
   * Execute detected tool calls with optional command runner support.
   */
  String executeToolCalls(List<ToolCall> calls, FileEditingTool fileEditingTool, CommandRunner commandRunner) {
    if (calls.isEmpty()) {
      return null
    }

    StringBuilder results = new StringBuilder()
    results.append("\n\n=== Tool Execution Results ===\n")

    for (ToolCall call : calls) {
      try {
        String result = executeSingleTool(call, fileEditingTool, commandRunner)
        results.append("${call.toolName}: ${result}\n")
      } catch (Exception e) {
        log.error("Error executing tool call: {}", call.toolName, e)
        results.append("${call.toolName}: ERROR - ${e.message}\n")
      }
    }

    return results.toString()
  }

  private String executeSingleTool(ToolCall call, FileEditingTool fileEditingTool, CommandRunner commandRunner) {
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

  private static String unescapeString(String str) {
    return str
      .replace("\\n", "\n")
      .replace("\\t", "\t")
      .replace("\\\"", "\"")
      .replace("\\'", "'")
      .replace("\\\\", "\\")
  }
}
