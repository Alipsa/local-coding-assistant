package se.alipsa.lca.tools

import groovy.json.JsonSlurper
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.PrintWriter
import java.io.StringWriter

@Component
@CompileStatic
class SastTool {

  private static final Logger log = LoggerFactory.getLogger(SastTool)

  private final CommandRunner commandRunner
  private final CommandPolicy commandPolicy
  private final String command
  private final long timeoutMillis
  private final int maxOutputChars

  SastTool(
    CommandRunner commandRunner,
    CommandPolicy commandPolicy,
    @Value('${assistant.sast.command:}') String command,
    @Value('${assistant.sast.timeout-millis:60000}') long timeoutMillis,
    @Value('${assistant.sast.max-output-chars:8000}') int maxOutputChars
  ) {
    this.commandRunner = commandRunner
    this.commandPolicy = commandPolicy
    this.command = command != null ? command.trim() : ""
    this.timeoutMillis = timeoutMillis > 0 ? timeoutMillis : 60000L
    this.maxOutputChars = maxOutputChars > 0 ? maxOutputChars : 8000
  }

  SastResult run(List<String> paths) {
    if (!command) {
      return new SastResult(false, false, "SAST command not configured.", List.of())
    }
    List<String> targets = paths != null ? paths.findAll { it != null && it.trim() } : List.of()
    if (targets.isEmpty()) {
      return new SastResult(false, false, "No paths provided for SAST scan.", List.of())
    }
    String finalCommand
    try {
      finalCommand = resolveCommand(command, targets)
    } catch (IllegalArgumentException e) {
      String reason = e.message ?: "Unsafe argument provided to SAST command."
      return new SastResult(false, false, reason, List.of())
    }
    CommandPolicy.Decision decision = commandPolicy.evaluate(finalCommand)
    if (!decision.allowed) {
      return new SastResult(false, false, decision.message ?: "SAST command blocked by policy.", List.of())
    }
    CommandRunner.CommandResult result = commandRunner.run(finalCommand, timeoutMillis, maxOutputChars)
    if (!result.success) {
      String message = result.output ?: "SAST command failed."
      return new SastResult(false, true, message, List.of())
    }
    List<SastFinding> findings = parseFindings(result.output ?: "")
    new SastResult(true, true, null, findings)
  }

  private static String resolveCommand(String command, List<String> targets) {
    // Escape each target so that any shell metacharacters are treated as literal path characters
    List<String> safeTargets = targets.collect { String t -> escapeShellArg(t) }
    String joined = safeTargets.join(" ")
    if (command.contains("{paths}")) {
      return command.replace("{paths}", joined)
    }
    return command + " " + joined
  }

  /**
   * Escape a single value so it can be safely used as a POSIX shell argument.
   * Uses single-quoting and rejects control characters to avoid command injection.
   * Null bytes (Unicode U+0000) are rejected since they cannot be represented safely on a shell command line.
   */
  private static String escapeShellArg(String arg) {
    final String controlMessage = "Shell arguments cannot contain control characters (including null bytes)"
    if (arg == null) {
      return "''"
    }
    if (arg.indexOf((int)('\u0000' as char)) >= 0) {
      throw new IllegalArgumentException(controlMessage)
    }
    for (int i = 0; i < arg.length(); i++) {
      char ch = arg.charAt(i)
      if (ch < 0x20 || ch == 0x7F) {
        throw new IllegalArgumentException(controlMessage)
      }
    }
    // Single-quote wrapping is safe for POSIX shells; escape single quotes by closing, escaping, and reopening.
    String escaped = arg.replace("'", "'\"'\"'")
    return "'" + escaped + "'"
  }
  private static List<SastFinding> parseFindings(String output) {
    String trimmed = output != null ? output.trim() : ""
    if (!trimmed) {
      return List.of()
    }
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      try {
        def parsed = new JsonSlurper().parseText(trimmed)
        List<Map> results = extractResults(parsed)
        if (!results.isEmpty()) {
          return results.collect { Map entry ->
            String checkId = entry.check_id ?: "rule"
            String path = entry.path ?: "unknown"
            def start = entry.start instanceof Map ? entry.start : [:]
            Integer line = start.line instanceof Number ? ((Number) start.line).intValue() : null
            def extra = entry.extra instanceof Map ? entry.extra : [:]
            String severity = extra.severity ?: "LOW"
            new SastFinding(severity.toString().toUpperCase(), path, line, checkId)
          }
        }
      } catch (Exception e) {
        StringWriter sw = new StringWriter()
        e.printStackTrace(new PrintWriter(sw))
        String summary = e.message != null ? e.message.split("\\R")[0] : e.getClass().getSimpleName()
        log.warn("Failed to parse SAST output as JSON (falling back to line parsing). Error: {}", summary)
        log.debug("SAST parse stacktrace\n{}", sw.toString())
      }
    }
    return trimmed.readLines()
      .findAll { it != null && it.trim() }
      .collect { new SastFinding("INFO", "unknown", null, it.trim()) }
  }

  @Canonical
  @CompileStatic
  static class SastFinding {
    String severity
    String path
    Integer line
    String rule
  }

  @Canonical
  @CompileStatic
  static class SastResult {
    boolean success
    boolean ran
    String message
    List<SastFinding> findings
  }

  private static List<Map> extractResults(Object parsed) {
    if (parsed instanceof Map) {
      Object rawResults = ((Map) parsed).get("results")
      if (rawResults instanceof List) {
        return ((List) rawResults).findAll { it instanceof Map } as List<Map>
      }
      return List.of()
    }
    if (parsed instanceof List) {
      return ((List) parsed).findAll { it instanceof Map } as List<Map>
    }
    List.of()
  }
}
