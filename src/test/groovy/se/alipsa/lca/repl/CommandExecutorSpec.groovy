package se.alipsa.lca.repl

import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.review.ReviewSeverity
import se.alipsa.lca.shell.McpCommands
import se.alipsa.lca.shell.ShellCommands
import spock.lang.Specification

class CommandExecutorSpec extends Specification {

  ShellCommands shellCommands = Mock()
  McpCommands mcpCommands = Mock()
  CommandExecutor executor = new CommandExecutor(shellCommands, mcpCommands)

  def "execute matches a slash command whose argument text spans multiple lines"() {
    given:
    String command = '/review --code "line one\nline two"'

    when:
    String result = executor.execute(command)

    then:
    result != "Invalid command format. Expected: /command [args]"
    1 * shellCommands.review("line one\nline two", "", "default", null, null, null, null, null, false,
      ReviewSeverity.LOW, false, true, false, false, false, null) >> "reviewed"
    result == "reviewed"
  }

  def "execute still rejects genuinely malformed input"() {
    when:
    String result = executor.execute("not a slash command")

    then:
    result == "Invalid command format. Expected: /command [args]"
  }

  def "execute forwards the --command flag text to ShellCommands.runCommand"() {
    given:
    String command = '/run --command "gh pr list --state open"'

    when:
    String result = executor.execute(command)

    then:
    1 * shellCommands.runCommand("gh pr list --state open", 60000L, 8000, "default", true, false) >> "ran"
    result == "ran"
  }

  def "executePasteContent forwards directly to ShellCommands.paste without re-parsing"() {
    given:
    String content = "/review --code \"whatever\"\nmore lines that would break COMMAND_PATTERN reparsing"

    when:
    String result = executor.executePasteContent(content, true, "default", PersonaMode.CODER)

    then:
    1 * shellCommands.paste(content, "/end", true, "default", PersonaMode.CODER) >> "sent"
    result == "sent"
  }

  def "execute binds hyphenated flags like --no-color and --min-severity"() {
    given:
    String command = '/review --code "x" --no-color --min-severity HIGH'

    when:
    String result = executor.execute(command)

    then:
    1 * shellCommands.review("x", "", "default", null, null, null, null, null, false,
      ReviewSeverity.HIGH, true, true, false, false, false, null) >> "reviewed"
    result == "reviewed"
  }

  def "execute binds --show-reasoning and --with-thinking on /chat"() {
    when:
    executor.execute('/chat hello --show-reasoning')

    then:
    1 * shellCommands.chat(["hello"] as String[], "default", PersonaMode.CODER, null, null, null,
      null, null, false, true) >> "chatted"

    when:
    executor.execute('/chat hello --with-thinking')

    then:
    1 * shellCommands.chat(["hello"] as String[], "default", PersonaMode.CODER, null, null, null,
      null, null, false, true) >> "chatted"
  }

  def "execute binds --case-insensitive on /codesearch"() {
    when:
    String result = executor.execute('/codesearch --query foo --case-insensitive')

    then:
    1 * shellCommands.codeSearch("foo", null, 2, 20, false, 8000, 0, true) >> "searched"
    result == "searched"
  }

  def "execute dispatches /reviewlog to ShellCommands.reviewLog"() {
    given:
    String command = '/reviewlog --min-severity MEDIUM --path-filter src --limit 10 --page 2 --since 2026-01-01T00:00:00Z --no-color'

    when:
    String result = executor.execute(command)

    then:
    1 * shellCommands.reviewLog(ReviewSeverity.MEDIUM, "src", 10, 2, "2026-01-01T00:00:00Z", true) >> "log"
    result == "log"
  }

  def "execute dispatches /reviewlog with defaults when no flags are given"() {
    when:
    String result = executor.execute('/reviewlog')

    then:
    1 * shellCommands.reviewLog(ReviewSeverity.LOW, null, 5, 1, null, false) >> "log"
    result == "log"
  }

  def "execute dispatches /compact with the default session when no flags are given"() {
    when:
    String result = executor.execute('/compact')

    then:
    1 * shellCommands.compact("default") >> "compacted"
    result == "compacted"
  }

  def "execute dispatches /compact with an explicit --session"() {
    when:
    String result = executor.execute('/compact --session s1')

    then:
    1 * shellCommands.compact("s1") >> "compacted"
    result == "compacted"
  }
}
