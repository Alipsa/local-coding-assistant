package se.alipsa.lca.gui

import se.alipsa.lca.intent.IntentCommandRouter
import se.alipsa.lca.intent.IntentRouterResult
import se.alipsa.lca.intent.IntentRoutingOutcome
import se.alipsa.lca.intent.IntentRoutingPlan
import se.alipsa.lca.repl.CommandExecutor
import se.alipsa.lca.shell.BangCommandHandler
import spock.lang.Specification

class GuiTurnControllerSpec extends Specification {

  IntentCommandRouter router = Mock()
  CommandExecutor executor = Mock()
  BangCommandHandler bangCommandHandler = Mock()
  GuiTurnController controller = new GuiTurnController(router, executor, bangCommandHandler, 0.6d)

  def "executes a high-confidence single command with no routing note"() {
    given:
    router.routeDetails("do it") >> new IntentRoutingOutcome(new IntentRoutingPlan(["/chat"], 0.95d, "clear"), null)
    executor.execute("/chat") >> "hello"

    when:
    TurnResult result = controller.process("do it")

    then:
    result.understood
    result.output == "hello"
    result.note == null
  }

  def "reports not understood when routing yields no commands"() {
    given:
    router.routeDetails(_ as String) >> new IntentRoutingOutcome(new IntentRoutingPlan([], 0.0d, ""), null)

    when:
    TurnResult result = controller.process("gibberish")

    then:
    !result.understood
    result.note.toLowerCase().contains("couldn't understand")
    result.output == null
  }

  def "adds a routing note and aggregates output for multiple commands"() {
    given:
    router.routeDetails("both") >> new IntentRoutingOutcome(new IntentRoutingPlan(["/plan", "/review"], 0.9d, "x"), null)
    executor.execute("/plan") >> "planned"
    executor.execute("/review") >> "reviewed"

    when:
    TurnResult result = controller.process("both")

    then:
    result.note.startsWith("Routing to: /plan, /review")
    result.output.contains("planned")
    result.output.contains("reviewed")
  }

  def "low-confidence single command note shows confidence, second opinion and low-confidence flag"() {
    given:
    IntentRouterResult routerResult = new IntentRouterResult()
    routerResult.usedSecondOpinion = true
    router.routeDetails("maybe") >> new IntentRoutingOutcome(new IntentRoutingPlan(["/chat"], 0.5d, "meh"), routerResult)
    executor.execute("/chat") >> "answer"

    when:
    TurnResult result = controller.process("maybe")

    then:
    result.output == "answer"
    result.note.contains("50%")
    result.note.contains("second opinion")
    result.note.contains("low confidence")
  }

  def "blank input short-circuits without routing"() {
    when:
    TurnResult result = controller.process("   ")

    then:
    0 * router.routeDetails(_)
    result.understood
    result.output == null
  }

  def "wraps routing failures in an error note"() {
    given:
    router.routeDetails("boom") >> { throw new RuntimeException("kaboom") }

    when:
    TurnResult result = controller.process("boom")

    then:
    result.note.contains("kaboom")
  }

  def "bang input delegates to the shell handler and skips routing"() {
    given:
    bangCommandHandler.isBang("! ls") >> true
    bangCommandHandler.handle("! ls", "default", true) >> '$ ls\nfoo\n[exit 0]'

    when:
    TurnResult result = controller.process("! ls")

    then:
    0 * router.routeDetails(_)
    result.understood
    result.output.startsWith("```")
    result.output.contains("[exit 0]")
  }

  def "an explicit slash command is executed directly without routing"() {
    given:
    executor.execute("/status") >> "on branch main"

    when:
    TurnResult result = controller.process("/status")

    then:
    0 * router.routeDetails(_)
    1 * executor.execute("/status") >> "on branch main"
    result.understood
    result.output == "on branch main"
    result.action == GuiAction.NONE
  }

  def "#input requests an exit without routing or executing"() {
    when:
    TurnResult result = controller.process(input)

    then:
    0 * router.routeDetails(_)
    0 * executor.execute(_)
    result.action == GuiAction.EXIT

    where:
    input << ["exit", "quit", "/exit", "/quit", "  /Quit  "]
  }

  def "#input clears the transcript without routing or executing"() {
    when:
    TurnResult result = controller.process(input)

    then:
    0 * router.routeDetails(_)
    0 * executor.execute(_)
    result.action == GuiAction.CLEAR

    where:
    input << ["clear", "cls", "/clear", "/cls"]
  }
}
