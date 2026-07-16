package se.alipsa.lca.gui

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import se.alipsa.lca.intent.IntentCommandRouter
import se.alipsa.lca.intent.IntentRoutingOutcome
import se.alipsa.lca.intent.IntentRoutingPlan
import se.alipsa.lca.repl.CommandExecutor
import se.alipsa.lca.shell.BangCommandHandler

import java.util.Locale
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives one GUI turn through the same intent-routing pipeline the REPL uses
 * ({@link IntentCommandRouter} then {@link CommandExecutor}), but without any terminal I/O so
 * it can be unit-tested and called from a background Swing worker.
 *
 * <p>Unlike the REPL it does not run an interactive clarification menu; a low-confidence
 * route is executed anyway with an explanatory note (mirroring {@code docs/gui.md}).
 */
@Component
@CompileStatic
class GuiTurnController {

  private static final Logger log = LoggerFactory.getLogger(GuiTurnController)
  private static final double SHOW_ROUTING_BELOW = 0.85d
  private static final int MAX_VIEW_LINES = 5000

  private final IntentCommandRouter intentRouter
  private final CommandExecutor commandExecutor
  private final BangCommandHandler bangCommandHandler
  private final double secondOpinionThreshold

  GuiTurnController(
    IntentCommandRouter intentRouter,
    CommandExecutor commandExecutor,
    BangCommandHandler bangCommandHandler,
    @Value('${assistant.intent.second-opinion-threshold:0.6}') double secondOpinionThreshold
  ) {
    this.intentRouter = intentRouter
    this.commandExecutor = commandExecutor
    this.bangCommandHandler = bangCommandHandler
    this.secondOpinionThreshold = secondOpinionThreshold
  }

  TurnResult process(String input, TurnSink sink) {
    if (input == null || input.trim().isEmpty()) {
      return new TurnResult(true)
    }

    if (bangCommandHandler.isBang(input)) {
      String command = bangCommandHandler.strip(input)
      if (command.isEmpty()) {
        sink.note("Usage: ! <shell command>")
        return new TurnResult(true)
      }
      sink.beginBlock()
      try {
        sink.append('$ ' + command)
        String footer = bangCommandHandler.handle(input, "default", true, budgetedForwarder(sink, MAX_VIEW_LINES))
        if (footer != null && !footer.trim().isEmpty()) {
          sink.append(footer)
        }
      } catch (Exception e) {
        log.error("Error running shell command: {}", input, e)
        sink.append("Error: ${e.message}".toString())
      } finally {
        sink.endBlock()
      }
      return new TurnResult(true)
    }

    String trimmed = input.trim()
    String lower = trimmed.toLowerCase(Locale.ROOT)
    if (lower in ["exit", "quit", "/exit", "/quit"]) {
      return new TurnResult(true, GuiAction.EXIT)
    }
    if (lower in ["clear", "cls", "/clear", "/cls"]) {
      return new TurnResult(true, GuiAction.CLEAR)
    }

    if (trimmed.startsWith("/")) {
      try {
        String output = commandExecutor.execute(trimmed)
        if (output != null && !output.trim().isEmpty()) {
          sink.message(output)
        }
        return new TurnResult(true)
      } catch (Exception e) {
        log.error("Error executing command: {}", trimmed, e)
        sink.note("Error: ${e.message}".toString())
        return new TurnResult(true)
      }
    }

    try {
      IntentRoutingOutcome outcome = intentRouter.routeDetails(input)
      IntentRoutingPlan plan = outcome?.plan
      if (plan == null || plan.commands == null || plan.commands.isEmpty()) {
        sink.note("I couldn't understand that. Try rephrasing or use /help.")
        return new TurnResult(false)
      }
      String note = buildNote(plan, outcome)
      if (note != null) {
        sink.note(note)
      }
      for (String command : plan.commands) {
        String result = commandExecutor.execute(command)
        if (result != null && !result.trim().isEmpty()) {
          sink.message(result)
        }
      }
      new TurnResult(true)
    } catch (Exception e) {
      log.error("Error processing GUI input: {}", input, e)
      sink.note("Error: ${e.message}".toString())
      new TurnResult(true)
    }
  }

  /**
   * A thread-safe consumer that forwards up to {@code maxLines} lines to the sink, then emits a
   * single truncation marker. May be called concurrently from the stdout and stderr reader threads.
   */
  static Consumer<String> budgetedForwarder(TurnSink sink, int maxLines) {
    AtomicInteger count = new AtomicInteger()
    AtomicBoolean marked = new AtomicBoolean()
    return { String line ->
      int n = count.incrementAndGet()
      if (n <= maxLines) {
        sink.append(line)
      } else if (marked.compareAndSet(false, true)) {
        sink.append("… output truncated in view …")
      }
    } as Consumer<String>
  }

  private String buildNote(IntentRoutingPlan plan, IntentRoutingOutcome outcome) {
    boolean multi = plan.commands.size() > 1
    boolean lowish = plan.confidence < SHOW_ROUTING_BELOW
    if (!multi && !lowish) {
      return null
    }
    StringBuilder note = new StringBuilder("Routing to: ").append(plan.commands.join(", "))
    if (lowish && !multi) {
      note.append(" (confidence ")
        .append(String.format(Locale.UK, "%.0f%%", plan.confidence * 100))
      if (outcome?.result?.usedSecondOpinion) {
        note.append(", second opinion")
      }
      if (plan.confidence < secondOpinionThreshold) {
        note.append(", low confidence")
      }
      note.append(")")
    }
    note.toString()
  }
}
