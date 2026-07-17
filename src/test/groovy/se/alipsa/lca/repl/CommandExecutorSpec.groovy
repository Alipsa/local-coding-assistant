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
}
