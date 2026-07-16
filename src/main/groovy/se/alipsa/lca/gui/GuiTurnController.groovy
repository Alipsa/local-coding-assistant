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

  TurnResult process(String input) {
    if (input == null || input.trim().isEmpty()) {
      return new TurnResult(null, null, true)
    }
    if (bangCommandHandler.isBang(input)) {
      // Shell command: capture output (GUI stdout is not visible) and show it as a code block.
      String output = bangCommandHandler.handle(input, "default", true)
      String block = "```\n${output ?: ''}\n```".toString()
      return new TurnResult(null, block, true, GuiAction.NONE)
    }

    String trimmed = input.trim()
    String lower = trimmed.toLowerCase(Locale.ROOT)
    if (lower in ["exit", "quit", "/exit", "/quit"]) {
      return new TurnResult(null, null, true, GuiAction.EXIT)
    }
    if (lower in ["clear", "cls", "/clear", "/cls"]) {
      return new TurnResult(null, null, true, GuiAction.CLEAR)
    }

    // Explicit slash command: execute directly (same as the CLI), bypassing the LLM router.
    if (trimmed.startsWith("/")) {
      try {
        String output = commandExecutor.execute(trimmed)
        return new TurnResult(null, output, true, GuiAction.NONE)
      } catch (Exception e) {
        log.error("Error executing command: {}", trimmed, e)
        return new TurnResult("Error: ${e.message}".toString(), null, true, GuiAction.NONE)
      }
    }

    try {
      IntentRoutingOutcome outcome = intentRouter.routeDetails(input)
      IntentRoutingPlan plan = outcome?.plan
      if (plan == null || plan.commands == null || plan.commands.isEmpty()) {
        return new TurnResult("I couldn't understand that. Try rephrasing or use /help.", null, false)
      }
      String note = buildNote(plan, outcome)
      StringBuilder output = new StringBuilder()
      for (String command : plan.commands) {
        String result = commandExecutor.execute(command)
        if (result != null && !result.trim().isEmpty()) {
          if (output.length() > 0) {
            output.append("\n\n")
          }
          output.append(result)
        }
      }
      new TurnResult(note, output.toString(), true)
    } catch (Exception e) {
      log.error("Error processing GUI input: {}", input, e)
      new TurnResult("Error: ${e.message}".toString(), null, true)
    }
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
