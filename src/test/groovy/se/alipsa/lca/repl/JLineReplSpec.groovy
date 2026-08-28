package se.alipsa.lca.repl

import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.intent.IntentCommandRouter
import se.alipsa.lca.intent.IntentRoutingOutcome
import se.alipsa.lca.intent.IntentRoutingPlan
import se.alipsa.lca.shell.BangCommandHandler
import se.alipsa.lca.shell.CommandInputNormaliser
import se.alipsa.lca.shell.ShellSettings
import spock.lang.Specification

class JLineReplSpec extends Specification {

  IntentCommandRouter intentRouter = Mock()
  CommandExecutor commandExecutor = Mock()
  CommandInputNormaliser normaliser = new CommandInputNormaliser(new ShellSettings(true))
  BangCommandHandler bangCommandHandler = Mock()
  Terminal terminal = TerminalBuilder.builder()
    .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
    .system(false)
    .dumb(true)
    .build()

  JLineRepl repl = new JLineRepl(intentRouter, commandExecutor, normaliser, bangCommandHandler, terminal, "lca> ", null, 0.6d)

  def cleanup() {
    terminal.close()
  }

  def "a fenced /paste block dispatches directly, bypassing intent routing"() {
    when:
    repl.handleInput("/paste\nline one\nline two\n/end")

    then:
    1 * commandExecutor.executePasteContent("line one\nline two", true, "default", PersonaMode.CODER) >> "sent"
    0 * intentRouter.routeDetails(_)
  }

  def "a fenced ^^^ block dispatches directly"() {
    when:
    repl.handleInput("^^^\nfoo\nbar\n^^^")

    then:
    1 * commandExecutor.executePasteContent("foo\nbar", true, "default", PersonaMode.CODER) >> "sent"
    0 * intentRouter.routeDetails(_)
  }

  def "an empty fenced block is a no-op"() {
    when:
    repl.handleInput("/paste\n/end")

    then:
    0 * commandExecutor.executePasteContent(_, _, _, _)
    0 * intentRouter.routeDetails(_)
  }

  def "raw multi-line text with no leading slash auto-dispatches as a paste candidate"() {
    when:
    repl.handleInput("first line\nsecond line")

    then:
    1 * commandExecutor.executePasteContent("first line\nsecond line", true, "default", PersonaMode.CODER) >> "sent"
    0 * intentRouter.routeDetails(_)
  }

  def "single-line input still routes through the intent classifier as before"() {
    given:
    def plan = new IntentRoutingPlan(commands: [], confidence: 1.0d, explanation: null)

    when:
    repl.handleInput("what does this project do")

    then:
    1 * intentRouter.routeDetails("what does this project do") >> new IntentRoutingOutcome(plan: plan, result: null)
  }

  def "a bang-prefixed line runs as a shell command, bypassing intent routing"() {
    when:
    repl.handleInput("! git status")

    then:
    1 * bangCommandHandler.isBang("! git status") >> true
    1 * bangCommandHandler.handle("! git status", "default", false) >> "Command: git status\nExit: 0 (success)"
    0 * intentRouter.routeDetails(_)
  }

  def "a literal known slash command dispatches directly, bypassing intent routing"() {
    when:
    repl.handleInput("/benchmark --model qwen3.8-review --prompt-file x.groovy")

    then:
    1 * commandExecutor.isKnownCommand("/benchmark --model qwen3.8-review --prompt-file x.groovy") >> true
    1 * commandExecutor.execute("/benchmark --model qwen3.8-review --prompt-file x.groovy") >> "Model: qwen3.8-review"
    0 * intentRouter.routeDetails(_)
  }

  def "an unrecognized slash command still routes through the intent classifier"() {
    given:
    def plan = new IntentRoutingPlan(commands: [], confidence: 1.0d, explanation: null)

    when:
    repl.handleInput("/frobnicate")

    then:
    1 * commandExecutor.isKnownCommand("/frobnicate") >> false
    1 * intentRouter.routeDetails("/frobnicate") >> new IntentRoutingOutcome(plan: plan, result: null)
  }
}
