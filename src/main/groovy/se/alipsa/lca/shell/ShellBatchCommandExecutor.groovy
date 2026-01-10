package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import se.alipsa.lca.repl.CommandExecutor
import se.alipsa.lca.tools.LogSanitizer

@Component
@CompileStatic
class ShellBatchCommandExecutor implements BatchCommandExecutor {

  private static final Logger log = LoggerFactory.getLogger(ShellBatchCommandExecutor)

  private final CommandExecutor commandExecutor
  private final CommandInputNormaliser normaliser

  ShellBatchCommandExecutor(
    CommandExecutor commandExecutor,
    CommandInputNormaliser normaliser
  ) {
    this.commandExecutor = commandExecutor
    this.normaliser = normaliser != null ? normaliser : new CommandInputNormaliser(new ShellSettings(true))
  }

  @Override
  BatchCommandOutcome execute(String command) {
    BatchCommandOutcome outcome = new BatchCommandOutcome()

    if (command == null || command.trim().isEmpty()) {
      outcome.error = new IllegalArgumentException("Command must not be empty.")
      return outcome
    }

    String trimmedCommand = command.trim()

    // Handle exit/quit commands
    String lowerCommand = trimmedCommand.toLowerCase()
    if (lowerCommand == "exit" || lowerCommand == "quit" ||
        lowerCommand == "/exit" || lowerCommand == "/quit") {
      outcome.exitRequested = true
      outcome.exitCode = 0
      return outcome
    }

    try {
      // Normalize the command (add / prefix if needed)
      String normalizedCommand = normaliser.normalise(trimmedCommand) ?: trimmedCommand
      if (!normalizedCommand.startsWith("/")) {
        normalizedCommand = "/" + normalizedCommand
      }

      // Execute via CommandExecutor (unified execution path)
      String result = commandExecutor.execute(normalizedCommand)
      outcome.result = result

      // Print the result to stdout (like the old Spring Shell handler did)
      if (result != null && !result.trim().isEmpty()) {
        println(result)
      }

    } catch (Exception e) {
      String safe = LogSanitizer.sanitize(command)
      log.error("Failed to execute batch command: {}", safe, e)
      outcome.error = e
      // Print error message to stderr
      if (e.message != null) {
        System.err.println("Error: " + e.message)
      }
    }

    return outcome
  }

}
