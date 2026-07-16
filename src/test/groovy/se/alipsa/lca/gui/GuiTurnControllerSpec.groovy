package se.alipsa.lca.gui

import se.alipsa.lca.intent.IntentCommandRouter
import se.alipsa.lca.intent.IntentRouterResult
import se.alipsa.lca.intent.IntentRoutingOutcome
import se.alipsa.lca.intent.IntentRoutingPlan
import se.alipsa.lca.repl.CommandExecutor
import se.alipsa.lca.shell.BangCommandHandler
import spock.lang.Specification

import java.util.function.Consumer

class GuiTurnControllerSpec extends Specification {

  IntentCommandRouter router = Mock()
  CommandExecutor executor = Mock()
  BangCommandHandler bangCommandHandler = Mock()
  GuiTurnController controller = new GuiTurnController(router, executor, bangCommandHandler, 0.6d)

  /** Records sink calls as strings so ordering can be asserted. */
  static class RecordingSink implements TurnSink {
    List<String> events = []
    void note(String t) { events << "note:${t}".toString() }
    void beginBlock() { events << "begin" }
    void append(String line) { events << "append:${line}".toString() }
    void endBlock() { events << "end" }
    void message(String m) { events << "msg:${m}".toString() }
  }

  RecordingSink sink = new RecordingSink()

  def "high-confidence single command emits one message and no note"() {
    given:
    router.routeDetails("do it") >> new IntentRoutingOutcome(new IntentRoutingPlan(["/chat"], 0.95d, "clear"), null)
    executor.execute("/chat") >> "hello"

    when:
    TurnResult result = controller.process("do it", sink)

    then:
    result.understood
    sink.events == ["msg:hello"]
  }

  def "not-understood routing emits a note and reports understood=false"() {
    given:
    router.routeDetails(_ as String) >> new IntentRoutingOutcome(new IntentRoutingPlan([], 0.0d, ""), null)

    when:
    TurnResult result = controller.process("gibberish", sink)

    then:
    !result.understood
    sink.events.size() == 1
    sink.events[0].toLowerCase().contains("couldn't understand")
  }

  def "multiple commands emit a routing note then one message each, in order"() {
    given:
    router.routeDetails("both") >> new IntentRoutingOutcome(new IntentRoutingPlan(["/plan", "/review"], 0.9d, "x"), null)
    executor.execute("/plan") >> "planned"
    executor.execute("/review") >> "reviewed"

    when:
    controller.process("both", sink)

    then:
    sink.events == ["note:Routing to: /plan, /review", "msg:planned", "msg:reviewed"]
  }

  def "low-confidence single command note shows confidence, second opinion and low-confidence flag"() {
    given:
    IntentRouterResult routerResult = new IntentRouterResult()
    routerResult.usedSecondOpinion = true
    router.routeDetails("maybe") >> new IntentRoutingOutcome(new IntentRoutingPlan(["/chat"], 0.5d, "meh"), routerResult)
    executor.execute("/chat") >> "answer"

    when:
    controller.process("maybe", sink)

    then:
    sink.events[0].contains("50%")
    sink.events[0].contains("second opinion")
    sink.events[0].contains("low confidence")
    sink.events[1] == "msg:answer"
  }

  def "blank input does nothing"() {
    when:
    TurnResult result = controller.process("   ", sink)

    then:
    0 * router.routeDetails(_)
    result.understood
    sink.events.isEmpty()
  }

  def "a bang command streams a block: begin, header, body lines, footer, end"() {
    given:
    bangCommandHandler.isBang("! ls") >> true
    bangCommandHandler.strip("! ls") >> "ls"
    bangCommandHandler.handle("! ls", "default", true, _ as Consumer) >> { args ->
      Consumer<String> c = args[3] as Consumer
      c.accept("foo")
      c.accept("bar")
      "[exit 0]"
    }

    when:
    controller.process("! ls", sink)

    then:
    0 * router.routeDetails(_)
    sink.events == ["begin", 'append:$ ls', "append:foo", "append:bar", "append:[exit 0]", "end"]
  }

  def "a bare bang reports usage without opening a block"() {
    given:
    bangCommandHandler.isBang("!") >> true
    bangCommandHandler.strip("!") >> ""

    when:
    controller.process("!", sink)

    then:
    sink.events == ["note:Usage: ! <shell command>"]
  }

  def "an explicit slash command executes directly and emits its output as a message"() {
    given:
    executor.execute("/status") >> "on branch main"

    when:
    TurnResult result = controller.process("/status", sink)

    then:
    0 * router.routeDetails(_)
    1 * executor.execute("/status") >> "on branch main"
    result.understood
    sink.events == ["msg:on branch main"]
    result.action == GuiAction.NONE
  }

  def "#input requests exit without routing or executing"() {
    when:
    TurnResult result = controller.process(input, sink)

    then:
    0 * router.routeDetails(_)
    0 * executor.execute(_)
    result.action == GuiAction.EXIT
    sink.events.isEmpty()

    where:
    input << ["exit", "quit", "/exit", "/quit", "  /Quit  "]
  }

  def "#input clears without routing or executing"() {
    when:
    TurnResult result = controller.process(input, sink)

    then:
    0 * router.routeDetails(_)
    0 * executor.execute(_)
    result.action == GuiAction.CLEAR
    sink.events.isEmpty()

    where:
    input << ["clear", "cls", "/clear", "/cls"]
  }

  def "budgetedForwarder forwards up to the budget then emits one truncation marker"() {
    given:
    Consumer<String> fwd = GuiTurnController.budgetedForwarder(sink, 3)

    when:
    (1..6).each { fwd.accept("line-${it}".toString()) }

    then:
    sink.events == ["append:line-1", "append:line-2", "append:line-3", "append:… output truncated in view …"]
  }
}
