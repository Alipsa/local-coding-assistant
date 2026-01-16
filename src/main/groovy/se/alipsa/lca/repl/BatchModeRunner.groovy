package se.alipsa.lca.repl

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import se.alipsa.lca.shell.BatchModeOptions

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Handles batch mode execution via -c or --batch-file arguments.
 * Runs with highest priority to intercept batch mode before REPL starts.
 */
@Component
@CompileStatic
@Order(Ordered.HIGHEST_PRECEDENCE)
class BatchModeRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BatchModeRunner)

  private final CommandExecutor commandExecutor

  BatchModeRunner(CommandExecutor commandExecutor) {
    this.commandExecutor = commandExecutor
  }

  @Override
  void run(ApplicationArguments args) throws Exception {
    BatchModeOptions options
    try {
      options = BatchModeOptions.parse(args.sourceArgs)
    } catch (IllegalArgumentException e) {
      System.err.println("Error: " + (e.message ?: "Invalid batch mode arguments."))
      System.exit(1)
      return
    }

    // If not in batch mode, let ReplRunner handle it
    if (!options.enabled) {
      log.debug("Batch mode not enabled, skipping BatchModeRunner")
      return
    }

    log.debug("Batch mode enabled, executing commands")

    List<String> commands
    try {
      commands = resolveCommands(options)
    } catch (Exception e) {
      System.err.println("Error: " + (e.message ?: "Failed to load batch commands."))
      System.exit(1)
      return
    }

    if (commands.isEmpty()) {
      System.err.println("Error: No commands to execute in batch mode.")
      System.exit(1)
      return
    }

    int exitCode = executeCommands(commands)
    System.exit(exitCode)
  }

  private List<String> resolveCommands(BatchModeOptions options) {
    if (options.commandText != null && options.commandText.trim()) {
      return splitCommands(options.commandText)
    }

    String fileValue = options.batchFile
    if (fileValue == "-") {
      return readCommandsFromStdIn()
    }

    Path file = Paths.get(fileValue)
    return readCommands(file)
  }

  private List<String> splitCommands(String commandText) {
    List<String> commands = []
    String[] parts = commandText.split(";")
    for (String part : parts) {
      String trimmed = part.trim()
      if (!trimmed.isEmpty()) {
        commands.add(trimmed)
      }
    }
    return commands
  }

  private List<String> readCommandsFromStdIn() {
    List<String> commands = []
    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))
    String line
    while ((line = reader.readLine()) != null) {
      String trimmed = line.trim()
      if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
        commands.add(trimmed)
      }
    }
    return commands
  }

  private List<String> readCommands(Path file) {
    if (!Files.exists(file)) {
      throw new IllegalArgumentException("Batch file not found: " + file)
    }
    if (!Files.isReadable(file)) {
      throw new IllegalArgumentException("Batch file not readable: " + file)
    }

    List<String> commands = []
    List<String> lines = Files.readAllLines(file)
    for (String line : lines) {
      String trimmed = line.trim()
      if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
        commands.add(trimmed)
      }
    }
    return commands
  }

  private int executeCommands(List<String> commands) {
    for (String command : commands) {
      println("> ${command}")
      try {
        String result = commandExecutor.execute(command)
        if (result != null && !result.trim().isEmpty()) {
          println(result)
        }
        println("[OK] ${command}")
      } catch (Exception e) {
        System.err.println("Error executing command: " + command)
        System.err.println(e.message ?: e.class.simpleName)
        println("[ERROR] ${command} (see above)")
        return 1
      }
    }
    return 0
  }
}
