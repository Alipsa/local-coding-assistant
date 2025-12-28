package se.alipsa.lca.shell

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.shell.CommandNotCurrentlyAvailable
import org.springframework.shell.CommandNotFound
import org.springframework.shell.ExitRequest
import org.springframework.shell.ShellRunner
import org.springframework.shell.command.CommandHandlingResult
import org.springframework.shell.context.InteractionMode
import org.springframework.shell.context.ShellContext
import org.springframework.shell.result.CommandNotFoundMessageProvider
import org.springframework.stereotype.Component
import se.alipsa.lca.tools.LogSanitizer

import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@CompileStatic
class BatchShellRunner implements ShellRunner {

  private static final Logger log = LoggerFactory.getLogger(BatchShellRunner)
  private static final int SUMMARY_LIMIT = 200
  private static final CommandNotFoundMessageProvider NOT_FOUND_PROVIDER = new CommandNotFoundSuggestionProvider()

  private final BatchCommandExecutor executor
  private final ShellCommands shellCommands
  private final ShellContext shellContext
  private final BatchExitHandler exitHandler

  BatchShellRunner(
    BatchCommandExecutor executor,
    ShellCommands shellCommands,
    ShellContext shellContext,
    BatchExitHandler exitHandler
  ) {
    this.executor = executor
    this.shellCommands = shellCommands
    this.shellContext = shellContext
    this.exitHandler = exitHandler
  }

  @Override
  boolean run(String[] args) throws Exception {
    BatchModeOptions options
    try {
      options = BatchModeOptions.parse(args)
    } catch (IllegalArgumentException e) {
      printError(e.message ?: "Invalid batch mode arguments.")
      exitHandler.exit(1)
      return true
    }
    if (!options.enabled) {
      return false
    }
    shellContext.setInteractionMode(InteractionMode.NONINTERACTIVE)
    shellCommands.configureBatchMode(true, options.assumeYes)
    List<String> commands
    try {
      commands = resolveCommands(options)
    } catch (IllegalArgumentException e) {
      printError(e.message ?: "Failed to load batch commands.")
      exitHandler.exit(1)
      return true
    }
    if (commands.isEmpty()) {
      printError("No commands to execute in batch mode.")
      exitHandler.exit(1)
      return true
    }
    int exitCode = executeCommands(commands, options.batchJson)
    exitHandler.exit(exitCode)
    true
  }

  private List<String> resolveCommands(BatchModeOptions options) {
    if (options.commandText != null && options.commandText.trim()) {
      return BatchCommandParser.splitCommands(options.commandText)
    }
    String fileValue = options.batchFile
    if (fileValue == "-") {
      return BatchCommandParser.readCommandsFromStdIn()
    }
    Path file = Paths.get(fileValue)
    BatchCommandParser.readCommands(file)
  }

  private int executeCommands(List<String> commands, boolean batchJson) {
    for (String command : commands) {
      println("> ${command}")
      Instant started = Instant.now()
      BatchCommandOutcome outcome = executor.execute(command)
      Instant finished = Instant.now()
      int exitCode = resolveExitCode(outcome)
      boolean success = exitCode == 0
      if (batchJson) {
        emitJsonResult(command, started, finished, success, exitCode, summarizeOutcome(outcome))
      }
      if (success) {
        println("[OK] ${command}")
      } else {
        println("[ERROR] ${command} (see above)")
      }
      if (outcome.exitRequested) {
        return exitCode
      }
      if (!success) {
        return exitCode
      }
    }
    0
  }

  private int resolveExitCode(BatchCommandOutcome outcome) {
    if (outcome == null) {
      return 1
    }
    if (outcome.exitRequested && outcome.exitCode != null) {
      return outcome.exitCode
    }
    if (outcome.error != null) {
      return 1
    }
    Object result = outcome.result
    if (result instanceof CommandHandlingResult) {
      Integer code = ((CommandHandlingResult) result).exitCode()
      if (code != null) {
        return code
      }
    }
    if (result instanceof CommandNotFound || result instanceof CommandNotCurrentlyAvailable) {
      return 1
    }
    if (result instanceof ExitRequest) {
      return ((ExitRequest) result).status()
    }
    0
  }

  private String summarizeOutcome(BatchCommandOutcome outcome) {
    if (outcome == null) {
      return "no result"
    }
    if (outcome.error != null) {
      return sanitizeSummary(outcome.error.message ?: outcome.error.class.simpleName)
    }
    Object result = outcome.result
    if (result instanceof CommandHandlingResult) {
      CommandHandlingResult handling = (CommandHandlingResult) result
      if (handling.message() != null && handling.message().trim()) {
        return sanitizeSummary(handling.message())
      }
    }
    if (result instanceof CommandNotFound) {
      return sanitizeSummary(formatNotFound((CommandNotFound) result))
    }
    if (result instanceof CommandNotCurrentlyAvailable) {
      return sanitizeSummary(((CommandNotCurrentlyAvailable) result).message ?: "Command not currently available.")
    }
    if (result instanceof String) {
      String trimmed = result.trim()
      if (trimmed) {
        return sanitizeSummary(trimmed.split("\\R")[0])
      }
    }
    if (result != null) {
      return sanitizeSummary(result.toString())
    }
    "ok"
  }

  private String sanitizeSummary(String value) {
    String sanitized = LogSanitizer.sanitize(value ?: "")
    if (sanitized.length() > SUMMARY_LIMIT) {
      return sanitized.substring(0, SUMMARY_LIMIT)
    }
    sanitized
  }

  private static String formatNotFound(CommandNotFound notFound) {
    if (notFound == null) {
      return "Command not found."
    }
    CommandNotFoundMessageProvider.ProviderContext context = CommandNotFoundMessageProvider.contextOf(
      notFound,
      notFound.words,
      notFound.registrations,
      notFound.text
    )
    String message = NOT_FOUND_PROVIDER.apply(context)
    message ?: notFound.message ?: "Command not found."
  }

  private void emitJsonResult(
    String command,
    Instant started,
    Instant finished,
    boolean success,
    int exitCode,
    String summary
  ) {
    Map<String, Object> payload = new LinkedHashMap<>()
    payload.put("command", command)
    payload.put("start", started.toString())
    payload.put("end", finished.toString())
    payload.put("durationMillis", Duration.between(started, finished).toMillis())
    payload.put("success", success)
    payload.put("exitCode", exitCode)
    payload.put("summary", summary)
    println(JsonOutput.toJson(payload))
  }

  private void printError(String message) {
    String safe = message ?: "Batch mode failed."
    log.warn(safe)
    System.err.println(safe)
  }
}
