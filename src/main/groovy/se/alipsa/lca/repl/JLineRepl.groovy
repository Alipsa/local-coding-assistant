package se.alipsa.lca.repl

import groovy.transform.CompileStatic
import org.jline.reader.EndOfFileException
import org.jline.reader.Highlighter
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.DefaultParser
import org.jline.terminal.Terminal
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import se.alipsa.lca.intent.IntentCommandRouter
import se.alipsa.lca.intent.IntentRoutingPlan

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
  private final Terminal terminal
  private final LineReader lineReader
  private final String prompt
  private final AttributedStyle userInputStyle
  private volatile boolean running = true

  JLineRepl(
    IntentCommandRouter intentRouter,
    CommandExecutor commandExecutor,
    Terminal terminal,
    @Value('${lca.repl.prompt:lca> }') String prompt,
    @Value('${lca.repl.history-file:#{null}}') String historyFile
  ) {
    this.intentRouter = intentRouter
    this.commandExecutor = commandExecutor
    this.terminal = terminal
    this.prompt = prompt
    this.userInputStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN + AttributedStyle.BRIGHT)

    // Create line reader with history and editing
    def parser = new DefaultParser()
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

    log.info("JLine REPL initialized with prompt: {}", prompt)
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
        if (line == null || line.trim().isEmpty()) {
          continue
        }

        String trimmed = line.trim()
        log.debug("Trimmed input: '{}' (length: {})", trimmed, trimmed.length())

        // Handle built-in commands that don't need routing
        log.debug("About to call handleBuiltInCommand with: '{}'", trimmed)
        if (handleBuiltInCommand(trimmed)) {
          log.debug("Built-in command handler returned true - continuing loop")
          continue
        }
        log.debug("Built-in handler returned false - routing through intent router")

        // Route through IntentRouterAgent
        processInput(trimmed)

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
   * Process user input by routing through IntentRouterAgent and executing.
   */
  private void processInput(String input) {
    try {
      // Route through IntentRouterAgent
      IntentRoutingPlan plan = intentRouter.route(input)

      if (plan == null || plan.commands == null || plan.commands.isEmpty()) {
        terminal.writer().println("I couldn't understand that. Try rephrasing or use /help.")
        return
      }

      // Show routing info for low confidence or multiple commands
      if (plan.confidence < 0.85d || plan.commands.size() > 1) {
        terminal.writer().println("Routing to: " + plan.commands.join(", "))
        if (plan.confidence < 0.85d && plan.commands.size() == 1) {
          String confidencePercent = String.format("%.0f%%", plan.confidence * 100)
          terminal.writer().println("Confidence: ${confidencePercent}")
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
