package se.alipsa.lca.repl

import groovy.transform.CompileStatic
import org.jline.reader.EndOfFileException
import org.jline.reader.Highlighter
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.intent.IntentCommandRouter
import se.alipsa.lca.intent.IntentRoutingOutcome
import se.alipsa.lca.intent.IntentRoutingPlan
import se.alipsa.lca.shell.BangCommandHandler
import se.alipsa.lca.shell.CommandInputNormaliser

import java.nio.file.Paths
import java.util.regex.Pattern

/**
 * JLine-based REPL that provides natural language interaction with the coding assistant.
 * All input is routed through IntentRouterAgent before execution.
 */
@Component
@CompileStatic
class JLineRepl {

  private static final Logger log = LoggerFactory.getLogger(JLineRepl)

  private final IntentCommandRouter intentRouter
  private final CommandExecutor commandExecutor
  private final CommandInputNormaliser commandInputNormaliser
  private final BangCommandHandler bangCommandHandler
  private final Terminal terminal
  private final LineReader lineReader
  private final String prompt
  private final double secondOpinionThreshold
  private final AttributedStyle userInputStyle
  private volatile boolean running = true

  JLineRepl(
    IntentCommandRouter intentRouter,
    CommandExecutor commandExecutor,
    CommandInputNormaliser commandInputNormaliser,
    BangCommandHandler bangCommandHandler,
    Terminal terminal,
    @Value('${lca.repl.prompt:lca> }') String prompt,
    @Value('${lca.repl.history-file:#{null}}') String historyFile,
    @Value('${assistant.intent.second-opinion-threshold:0.6}') double secondOpinionThreshold
  ) {
    this.intentRouter = intentRouter
    this.commandExecutor = commandExecutor
    this.commandInputNormaliser = commandInputNormaliser
    this.bangCommandHandler = bangCommandHandler
    this.terminal = terminal
    this.prompt = prompt
    this.secondOpinionThreshold = secondOpinionThreshold
    this.userInputStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN + AttributedStyle.BRIGHT)

    // Create line reader with history and editing
    def parser = new FencedPasteParser()
    parser.setEofOnUnclosedQuote(true)
    parser.setEofOnEscapedNewLine(true)

    def builder = LineReaderBuilder.builder()
      .terminal(terminal)
      .parser(parser)
      .appName("lca")
      .highlighter(new UserInputHighlighter(userInputStyle))
      .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
      .variable(LineReader.HISTORY_SIZE, 500)
      .variable(LineReader.BELL_STYLE, "none")
      .variable(LineReader.SECONDARY_PROMPT_PATTERN, "paste... ")

    if (historyFile != null && !historyFile.trim().isEmpty()) {
      def historyPath = Paths.get(historyFile.trim())
      // Create parent directory if it doesn't exist
      if (historyPath.parent != null) {
        historyPath.parent.toFile().mkdirs()
      }
      builder.variable(LineReader.HISTORY_FILE, historyPath)
    }

    this.lineReader = builder.build()

    // Verify terminal capabilities
    if (!terminal.type.equals("dumb")) {
      log.debug("Terminal type: {}, size: {}x{}", terminal.type, terminal.width, terminal.height)
    } else {
      log.warn("Running on a dumb terminal - line editing may be limited")
    }
  }

  /**
   * Start the REPL loop.
   */
  void start() {
    log.debug("JLineRepl.start() called - beginning REPL loop")
    printWelcome()

    while (running) {
      log.debug("REPL loop iteration starting, running={}", running)
      try {
        String line = lineReader.readLine(prompt)
        log.debug("Read line from terminal: '{}'", line)
        handleInput(line)
      } catch (UserInterruptException e) {
        // Ctrl+C pressed
        log.debug("User interrupt")
        terminal.writer().println("^C")
      } catch (EndOfFileException e) {
        // Ctrl+D pressed or exit command
        log.debug("End of file - exiting REPL")
        break
      } catch (Exception e) {
        log.error("Error processing input: {}", e.getClass().getName(), e)
        terminal.writer().println("Error: " + e.message)
      }
    }

    shutdown()
  }

  /**
   * Handle one completed line already read from the terminal: built-in
   * commands, closed fenced multi-line blocks, auto-detected bracketed
   * paste candidates, and otherwise ordinary intent-routed input.
   * Package-private so it can be exercised directly in tests without
   * driving a real readLine() call.
   */
  void handleInput(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return
    }
    String trimmed = raw.trim()

    if (handleBuiltInCommand(trimmed)) {
      return
    }

    if (bangCommandHandler.isBang(trimmed)) {
      // Shell command: output streams live to the console; print the concise summary.
      String summary = bangCommandHandler.handle(trimmed, "default", false)
      if (summary != null && !summary.trim().isEmpty()) {
        terminal.writer().println(summary)
        terminal.writer().flush()
      }
      return
    }

    String fencedContent = FencedPasteMarkers.extractContent(raw)
    if (fencedContent != null) {
      dispatchPaste(fencedContent)
      return
    }

    if (commandInputNormaliser.isPasteCandidate(raw)) {
      dispatchPaste(raw)
      return
    }

    if (commandExecutor.isKnownCommand(trimmed)) {
      String result = commandExecutor.execute(trimmed)
      if (result != null && !result.trim().isEmpty()) {
        terminal.writer().println(result)
      }
      return
    }

    processInput(trimmed)
  }

  private void dispatchPaste(String content) {
    if (content.trim().isEmpty()) {
      return
    }
    String result = commandExecutor.executePasteContent(content, true, "default", PersonaMode.CODER)
    if (result != null && !result.trim().isEmpty()) {
      terminal.writer().println(result)
    }
  }

  /**
   * Process user input by routing through IntentRouterAgent and executing.
   */
  private void processInput(String input) {
    try {
      // Route through IntentRouterAgent - get full details
      IntentRoutingOutcome outcome = intentRouter.routeDetails(input)
      IntentRoutingPlan plan = outcome?.plan

      if (plan == null || plan.commands == null || plan.commands.isEmpty()) {
        terminal.writer().println("I couldn't understand that. Try rephrasing or use /help.")
        return
      }

      // If confidence is very low, ask for clarification
      if (plan.confidence < secondOpinionThreshold) {
        boolean shouldProceed = requestClarification(input, plan)
        if (!shouldProceed) {
          return
        }
      }

      // Show routing info for low confidence or multiple commands
      if (plan.confidence < 0.85d || plan.commands.size() > 1) {
        terminal.writer().println("Routing to: " + plan.commands.join(", "))
        if (plan.confidence < 0.85d && plan.commands.size() == 1) {
          String confidencePercent = String.format("%.0f%%", plan.confidence * 100)
          terminal.writer().print("Confidence: ${confidencePercent}")
          if (outcome?.result?.usedSecondOpinion) {
            terminal.writer().println(" (second opinion)")
          } else {
            terminal.writer().println()
          }
        }
      }

      // Execute each command
      for (String command : plan.commands) {
        String result = commandExecutor.execute(command)
        if (result != null && !result.trim().isEmpty()) {
          terminal.writer().println(result)
        }
      }

    } catch (Exception e) {
      log.error("Error processing input: {}", input, e)
      terminal.writer().println("Error: " + e.message)
    }
  }

  /**
   * Request clarification from user when confidence is too low.
   * Returns true if should proceed with original plan, false otherwise.
   */
  private boolean requestClarification(String input, IntentRoutingPlan plan) {
    String confidencePercent = String.format("%.0f%%", plan.confidence * 100)
    terminal.writer().println()
    terminal.writer().println("I'm not very confident about what you want (confidence: ${confidencePercent})")

    if (plan.explanation != null && !plan.explanation.trim().isEmpty()) {
      terminal.writer().println("Reason: ${plan.explanation}")
    }

    terminal.writer().println()
    terminal.writer().println("I think you want: ${plan.commands.join(', ')}")
    terminal.writer().println()
    terminal.writer().println("What would you like to do?")
    terminal.writer().println("  1) Proceed with this command anyway")
    terminal.writer().println("  2) Let me rephrase my request")
    terminal.writer().println("  3) Show me available commands (/help)")
    terminal.writer().println()

    try {
      String choice = lineReader.readLine("Your choice (1-3): ")
      choice = choice?.trim()

      if (choice == "1") {
        terminal.writer().println("Proceeding...")
        return true
      } else if (choice == "2") {
        terminal.writer().println("Please rephrase your request:")
        return false
      } else if (choice == "3") {
        String helpResult = commandExecutor.execute("/help")
        if (helpResult != null && !helpResult.trim().isEmpty()) {
          terminal.writer().println(helpResult)
        }
        terminal.writer().println()
        terminal.writer().println("Please try again with a clearer command:")
        return false
      } else {
        terminal.writer().println("Invalid choice. Please rephrase your request:")
        return false
      }
    } catch (UserInterruptException e) {
      terminal.writer().println("^C")
      return false
    } catch (EndOfFileException e) {
      return false
    }
  }

  /**
   * Handle built-in commands that don't need routing.
   * Returns true if the command was handled.
   */
  private boolean handleBuiltInCommand(String input) {
    String lower = input.toLowerCase().trim()
    log.debug("handleBuiltInCommand called with: '{}' (lower: '{}')", input, lower)

    if (lower.equals("exit") || lower.equals("quit") || lower.equals("/exit") || lower.equals("/quit")) {
      log.debug("EXIT COMMAND MATCHED! Input: '{}', lower: '{}'", input, lower)
      terminal.writer().println("Goodbye!")
      terminal.writer().flush()
      shutdown()
      System.exit(0)
      return true // Never reached
    }

    if (lower.equals("clear") || lower.equals("cls") || lower.equals("/clear") || lower.equals("/cls")) {
      terminal.puts(org.jline.utils.InfoCmp.Capability.clear_screen)
      terminal.flush()
      return true
    }

    log.debug("Not a built-in command '{}', returning false", input)
    return false
  }

  private void printWelcome() {
    log.debug("printWelcome() called - displaying welcome banner")
    terminal.writer().println("Local Coding Assistant")
    terminal.writer().println("Type naturally to interact, or use commands like /plan, /chat, /review")
    terminal.writer().println("Type 'exit' or '/exit' to quit, 'clear' or '/clear' to clear screen")
    terminal.writer().println()
  }

  private void shutdown() {
    log.debug("shutdown() called - closing terminal")
    try {
      terminal.close()
      log.debug("Terminal closed successfully")
    } catch (Exception e) {
      log.warn("Error closing terminal", e)
    }
  }

  Terminal getTerminal() {
    return terminal
  }

  LineReader getLineReader() {
    return lineReader
  }

  /**
   * Highlighter that colors all user input in a specified style (light green by default).
   */
  @CompileStatic
  static class UserInputHighlighter implements Highlighter {
    private final AttributedStyle style

    UserInputHighlighter(AttributedStyle style) {
      this.style = style
    }

    @Override
    AttributedString highlight(LineReader reader, String buffer) {
      // Color the entire input buffer with the specified style
      return new AttributedString(buffer, style)
    }

    @Override
    void setErrorPattern(Pattern errorPattern) {
      // Not used
    }

    @Override
    void setErrorIndex(int errorIndex) {
      // Not used
    }
  }
}
