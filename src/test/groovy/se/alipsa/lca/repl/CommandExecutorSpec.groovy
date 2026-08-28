package se.alipsa.lca.repl

import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.review.ReviewSeverity
import se.alipsa.lca.shell.McpCommands
import se.alipsa.lca.shell.ShellCommands
import spock.lang.Specification
import spock.lang.Unroll

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

  def "execute parses /benchmark flags and forwards them to ShellCommands.benchmark"() {
    given:
    String command = '/benchmark --model qwen3.8-review --prompt-file review-prompt.txt --max-tokens 50'

    when:
    String result = executor.execute(command)

    then:
    1 * shellCommands.benchmark("qwen3.8-review", null, "review-prompt.txt", 50, "default") >> "benchmarked"
    result == "benchmarked"
  }

  def "execute defaults /benchmark's max-tokens and session when not given"() {
    when:
    executor.execute('/benchmark')

    then:
    1 * shellCommands.benchmark(null, null, null, 200, "default") >> "benchmarked"
  }

  def "execute parses /git-push flags and forwards them to ShellCommands.gitPush"() {
    when:
    String result = executor.execute('/git-push --force')

    then:
    1 * shellCommands.gitPush(true, true) >> "pushed"
    result == "pushed"
  }

  def "execute defaults /git-push's force and confirm when not given"() {
    when:
    executor.execute('/git-push')

    then:
    1 * shellCommands.gitPush(false, true) >> "pushed"
  }

  def "execute respects an explicit /git-push --confirm false instead of forcing confirmation back on"() {
    // Regression guard: "parseBoolean(x) ?: true" treats a parsed `false` as absent under
    // Groovy's Elvis operator, silently re-enabling the confirmation prompt even when the
    // caller explicitly asked to skip it.
    when:
    executor.execute('/git-push --confirm false')

    then:
    1 * shellCommands.gitPush(false, false) >> "pushed"
  }

  def "execute parses /gitapply flags and forwards them to ShellCommands.gitApply"() {
    when:
    String result = executor.execute('/gitapply --patch-file p.diff --cached')

    then:
    1 * shellCommands.gitApply(null, "p.diff", true, true, true) >> "applied"
    result == "applied"
  }

  def "execute respects an explicit /gitapply --check false and --confirm false"() {
    when:
    executor.execute('/gitapply --patch-file p.diff --check false --confirm false')

    then:
    1 * shellCommands.gitApply(null, "p.diff", false, false, false) >> "applied"
  }

  def "execute parses /apply flags and forwards them to ShellCommands.applyPatch"() {
    when:
    String result = executor.execute('/apply --patch-file p.diff')

    then:
    1 * shellCommands.applyPatch("", "p.diff", true, true) >> "applied"
    result == "applied"
  }

  def "execute respects an explicit /apply --dry-run false and --confirm false"() {
    when:
    executor.execute('/apply --patch-file p.diff --dry-run false --confirm false')

    then:
    1 * shellCommands.applyPatch("", "p.diff", false, false) >> "applied"
  }

  def "execute parses /model flags and forwards them to ShellCommands.model"() {
    when:
    String result = executor.execute('/model --set gpt-oss:20b --session s1')

    then:
    1 * shellCommands.model("gpt-oss:20b", "s1", false) >> "model set"
    result == "model set"
  }

  def "execute defaults /model's session and list when not given"() {
    when:
    executor.execute('/model --list')

    then:
    1 * shellCommands.model(null, "default", true) >> "models"
  }

  def "execute parses /context flags and forwards them to ShellCommands.context"() {
    when:
    String result = executor.execute('/context --file-path src/Foo.groovy --start 10 --end 20 --padding 5')

    then:
    1 * shellCommands.context("src/Foo.groovy", 10, 20, null, 5) >> "context"
    result == "context"
  }

  def "execute accepts /context's file path as a positional word"() {
    when:
    executor.execute('/context src/Foo.groovy --symbol myMethod')

    then:
    1 * shellCommands.context("src/Foo.groovy", null, null, "myMethod", 2) >> "context"
  }

  def "execute dispatches /version to ShellCommands.version"() {
    when:
    String result = executor.execute('/version')

    then:
    1 * shellCommands.version() >> "lca version: 1.0"
    result == "lca version: 1.0"
  }

  def "execute parses /stage flags and forwards them to ShellCommands.stage"() {
    when:
    String result = executor.execute('/stage --file build.gradle --hunks 1,2 --confirm false')

    then:
    1 * shellCommands.stage(null, "build.gradle", "1,2", false) >> "staged"
    result == "staged"
  }

  def "execute passes /stage's positional words as paths"() {
    when:
    executor.execute('/stage a.txt b.txt')

    then:
    1 * shellCommands.stage(["a.txt", "b.txt"], null, null, true) >> "staged"
  }

  def "execute parses /revert flags and forwards them to ShellCommands.revert"() {
    when:
    String result = executor.execute('/revert --file-path src/Foo.groovy --dry-run true')

    then:
    1 * shellCommands.revert("src/Foo.groovy", true, true) >> "reverted"
    result == "reverted"
  }

  def "execute accepts /revert's file path as a positional word"() {
    when:
    executor.execute('/revert src/Foo.groovy')

    then:
    1 * shellCommands.revert("src/Foo.groovy", false, true) >> "reverted"
  }

  def "execute parses /commit-suggest flags and forwards them to ShellCommands.commitSuggest"() {
    when:
    String result = executor.execute('/commit-suggest --session s1 --hint "fix bug" --allow-secrets true')

    then:
    1 * shellCommands.commitSuggest("s1", null, null, null, "fix bug", true, true) >> "suggested"
    result == "suggested"
  }

  def "execute defaults /commit-suggest's session and flags when not given"() {
    when:
    executor.execute('/commit-suggest')

    then:
    1 * shellCommands.commitSuggest("default", null, null, null, null, true, false) >> "suggested"
  }

  def "execute respects an explicit /commit-suggest --secret-scan false instead of falling back to true"() {
    // Regression guard: Groovy's ?: treats a parsed `false` as absent, so a naive
    // "parseBoolean(x) ?: true" would silently re-enable scanning here (the same
    // truthiness trap fixed for /benchmark's --max-tokens 0).
    when:
    executor.execute('/commit-suggest --secret-scan false')

    then:
    1 * shellCommands.commitSuggest("default", null, null, null, null, false, false) >> "suggested"
  }

  def "execute parses /applyBlocks flags and forwards them to ShellCommands.applyBlocks"() {
    when:
    String result = executor.execute('/applyBlocks --file-path src/Foo.groovy --blocks-file blocks.txt --dry-run false')

    then:
    1 * shellCommands.applyBlocks("src/Foo.groovy", null, "blocks.txt", false, true) >> "applied"
    result == "applied"
  }

  def "execute accepts /applyBlocks's file path as a positional word"() {
    when:
    executor.execute('/applyBlocks src/Foo.groovy --blocks "some blocks"')

    then:
    1 * shellCommands.applyBlocks("src/Foo.groovy", "some blocks", null, true, true) >> "applied"
  }

  @Unroll
  def "'/#name' dispatches instead of falling through to the unknown-command fallback"() {
    // Guards against the asymmetric drift risk this class's KNOWN_COMMANDS set otherwise has:
    // a case added to execute()'s switch but not mirrored into KNOWN_COMMANDS would keep
    // routing that command through the LLM intent classifier instead of dispatching it
    // directly - exactly the bug /benchmark hit. This doesn't prove the converse (a stale
    // KNOWN_COMMANDS entry whose switch case was removed), only that everything the bypass
    // claims to handle actually does.
    when:
    String result = executor.execute("/${name}")

    then:
    result != "Unknown command: /${name}. Type /help for available commands."
    executor.isKnownCommand("/${name}")

    where:
    name << [
      "chat", "plan", "implement", "review", "search", "run", "edit", "paste",
      "gitapply", "git-apply", "git-push", "apply", "status", "diff", "tree", "codesearch",
      "mcp", "reviewlog", "compact", "help", "health", "benchmark",
      "model", "context", "version", "stage", "revert", "commit-suggest", "applyblocks"
    ]
  }

  @Unroll
  def "isKnownCommand('#input') == #expected"() {
    expect:
    executor.isKnownCommand(input) == expected

    where:
    input                                  || expected
    "/health"                              || true
    "/benchmark --model x"                 || true
    "/BENCHMARK --model x"                 || true
    "/review --code \"x\""                 || true
    "/gitapply --patch x"                  || true
    "/git-apply --patch x"                 || true
    "/git-push --force"                    || true
    "/model --set foo"                     || true
    "/frobnicate"                          || false
    "review this please"                   || false
    null                                   || false
    ""                                     || false
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
