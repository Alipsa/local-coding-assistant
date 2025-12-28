package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import se.alipsa.lca.tools.LogSanitizer

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Component
@CompileStatic
class CommandRunner {

  private static final Logger log = LoggerFactory.getLogger(CommandRunner)
  private static final int DEFAULT_OUTPUT_LIMIT = 8000
  private static final long DEFAULT_TIMEOUT_MILLIS = 60000L
  private static final DateTimeFormatter LOG_TIME = DateTimeFormatter
    .ofPattern("yyyyMMddHHmmssSSS")
    .withZone(ZoneId.systemDefault())

  private final Path projectRoot
  private final Path realProjectRoot

  CommandRunner() {
    this(Paths.get(".").toAbsolutePath().normalize())
  }

  CommandRunner(Path projectRoot) {
    this.projectRoot = projectRoot.toAbsolutePath().normalize()
    try {
      this.realProjectRoot = this.projectRoot.toRealPath()
    } catch (IOException e) {
      throw new IllegalStateException("Failed to resolve project root path", e)
    }
  }

  /**
   * Run a command through bash -lc from the project root.
   * Caller must validate or confirm untrusted commands (especially agent-originated) before calling.
   */
  CommandResult run(String command, long timeoutMillis, int maxOutputChars) {
    if (command == null || command.trim().isEmpty()) {
      return new CommandResult(false, false, 1, "No command provided.", false, null)
    }
    runInternal(command, { startProcess(command) } as ProcessStarter, timeoutMillis, maxOutputChars, null)
  }

  /**
   * Run a command through bash -lc with streaming output via the supplied listener.
   */
  CommandResult runStreaming(
    String command,
    long timeoutMillis,
    int maxOutputChars,
    OutputListener listener
  ) {
    if (command == null || command.trim().isEmpty()) {
      return new CommandResult(false, false, 1, "No command provided.", false, null)
    }
    runInternal(command, { startProcess(command) } as ProcessStarter, timeoutMillis, maxOutputChars, listener)
  }

  /**
   * Run a command directly via ProcessBuilder without invoking a shell.
   */
  CommandResult run(List<String> commandArgs, long timeoutMillis, int maxOutputChars) {
    if (commandArgs == null || commandArgs.isEmpty()) {
      return new CommandResult(false, false, 1, "No command provided.", false, null)
    }
    for (String arg : commandArgs) {
      if (arg == null) {
        return new CommandResult(false, false, 1, "Command arguments cannot be null.", false, null)
      }
    }
    String commandLabel = formatCommand(commandArgs)
    runInternal(commandLabel, { startProcess(commandArgs) } as ProcessStarter, timeoutMillis, maxOutputChars, null)
  }

  private CommandResult runInternal(
    String commandLabel,
    ProcessStarter starter,
    long timeoutMillis,
    int maxOutputChars,
    OutputListener listener
  ) {
    String sanitizedCommand = LogSanitizer.sanitize(commandLabel)
    long effectiveTimeout = timeoutMillis > 0 ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS
    int outputLimit = maxOutputChars > 0 ? maxOutputChars : DEFAULT_OUTPUT_LIMIT
    Path logPath
    try {
      logPath = createLogPath()
    } catch (IOException e) {
      logPath = null
      log.debug("Failed to prepare log path for command {}", sanitizedCommand, e)
    }
    Instant started = Instant.now()
    boolean timedOut = false
    Process process = null
    BufferedWriter logWriter = null
    try {
      logWriter = prepareWriter(logPath)
      writeHeader(logWriter, sanitizedCommand, started)
      process = starter.start()
      AtomicInteger remaining = new AtomicInteger(outputLimit)
      AtomicInteger remainingLogCapacity = new AtomicInteger(outputLimit)
      StringBuffer visibleOutput = new StringBuffer()
      StreamCollector outCollector = new StreamCollector(
        process.getInputStream(),
        logWriter,
        "OUT",
        visibleOutput,
        remaining,
        remainingLogCapacity,
        listener
      )
      StreamCollector errCollector = new StreamCollector(
        process.getErrorStream(),
        logWriter,
        "ERR",
        visibleOutput,
        remaining,
        remainingLogCapacity,
        listener
      )
      Thread outThread = new Thread(outCollector)
      Thread errThread = new Thread(errCollector)
      outThread.start()
      errThread.start()
      boolean finished = process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS)
      if (!finished) {
        timedOut = true
        process.destroyForcibly()
      }
      outThread.join(500)
      errThread.join(500)
      int exitCode = -1
      if (!timedOut) {
        try {
          exitCode = process.exitValue()
        } catch (IllegalThreadStateException ignored) {
          exitCode = -1
        }
      }
      writeFooter(logWriter, started, Instant.now(), exitCode, timedOut)
      boolean truncated = outCollector.truncated || errCollector.truncated
      return new CommandResult(
        !timedOut && exitCode == 0,
        timedOut,
        exitCode,
        visibleOutput.toString().stripTrailing(),
        truncated,
        logPath
      )
    } catch (IOException e) {
      log.warn("Command execution failed: {}", sanitizedCommand, e)
      return new CommandResult(false, timedOut, -1, e.message ?: e.class.simpleName, false, logPath)
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt()
      return new CommandResult(false, timedOut, -1, "Interrupted while running command", false, logPath)
    } finally {
      closeQuietly(logWriter)
      if (process != null && process.isAlive()) {
        process.destroyForcibly()
      }
    }
  }

  protected Path createLogPath() throws IOException {
    Path dir = realProjectRoot.resolve(".lca/run-logs")
    Files.createDirectories(dir)
    dir.resolve("run-${LOG_TIME.format(Instant.now())}.log")
  }

  protected Process startProcess(String command) throws IOException {
    ProcessBuilder pb = new ProcessBuilder(List.of("bash", "-lc", command))
    pb.directory(realProjectRoot.toFile())
    pb.redirectErrorStream(false)
    pb.start()
  }

  protected Process startProcess(List<String> commandArgs) throws IOException {
    ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(commandArgs))
    pb.directory(realProjectRoot.toFile())
    pb.redirectErrorStream(false)
    pb.start()
  }

  private static BufferedWriter prepareWriter(Path logPath) throws IOException {
    if (logPath == null) {
      return new BufferedWriter(new OutputStreamWriter(OutputStream.nullOutputStream(), StandardCharsets.UTF_8))
    }
    Files.newBufferedWriter(
      logPath,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING
    )
  }

  private static void closeQuietly(BufferedWriter writer) {
    if (writer == null) {
      return
    }
    try {
      writer.close()
    } catch (IOException ignored) {
      // ignore
    }
  }

  private static void writeHeader(BufferedWriter writer, String command, Instant started) throws IOException {
    writer.write("Command: ${command}".toString())
    writer.newLine()
    writer.write("Started: ${started}".toString())
    writer.newLine()
    writer.flush()
  }

  private static void writeFooter(BufferedWriter writer, Instant started, Instant ended, int exit, boolean timedOut) throws IOException {
    synchronized (writer) {
      writer.newLine()
      writer.write("Completed: ${ended}".toString())
      writer.newLine()
      writer.write("DurationMillis: ${ended.toEpochMilli() - started.toEpochMilli()}".toString())
      writer.newLine()
      writer.write("ExitCode: ${exit}".toString())
      writer.newLine()
      writer.write("TimedOut: ${timedOut}".toString())
      writer.newLine()
      writer.flush()
    }
  }

  private static String formatCommand(List<String> commandArgs) {
    List<String> parts = new ArrayList<>()
    for (String arg : commandArgs) {
      if (arg == null || arg.isEmpty()) {
        parts.add("''")
        continue
      }
      if (hasWhitespace(arg)) {
        parts.add("\"" + arg.replace("\"", "\\\"") + "\"")
      } else {
        parts.add(arg)
      }
    }
    parts.join(" ")
  }

  private static boolean hasWhitespace(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (Character.isWhitespace(value.charAt(i))) {
        return true
      }
    }
    false
  }

  @CompileStatic
  private static interface ProcessStarter {
    Process start() throws IOException
  }

  @Canonical
  @CompileStatic
  static class CommandResult {
    boolean success
    boolean timedOut
    int exitCode
    String output
    boolean truncated
    Path logPath
  }

  @FunctionalInterface
  @CompileStatic
  static interface OutputListener {
    /**
     * Receives a single output line from the command execution.
     *
     * Notes:
     * - Invoked synchronously on the stream reader thread (avoid slow or blocking work).
     * - Exceptions are caught and logged; they do not stop stream processing.
     * - Listener failures do not affect the command result; output streaming is best-effort.
     * - stream parameter will be either "OUT" for standard output or "ERR" for standard error.
     */
    void onLine(String stream, String line)
  }

  @CompileStatic
  private static class StreamCollector implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(StreamCollector)
    private final InputStream stream
    private final BufferedWriter logWriter
    private final String label
    private final StringBuffer visibleOutput
    private final AtomicInteger remainingVisible
    private final AtomicInteger remainingLogCapacity
    private final OutputListener listener
    volatile boolean truncated = false

    StreamCollector(
      InputStream stream,
      BufferedWriter logWriter,
      String label,
      StringBuffer visibleOutput,
      AtomicInteger remainingVisible,
      AtomicInteger remainingLogCapacity,
      OutputListener listener
    ) {
      this.stream = stream
      this.logWriter = logWriter
      this.label = label
      this.visibleOutput = visibleOutput
      this.remainingVisible = remainingVisible
      this.remainingLogCapacity = remainingLogCapacity
      this.listener = listener
    }

    @Override
    void run() {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        String line
        while ((line = reader.readLine()) != null) {
          if (listener != null) {
            try {
              listener.onLine(label, line)
            } catch (Exception e) {
              log.warn("Output listener failed for label '{}': {}", label, e.getMessage())
            }
          }
          String sanitizedLine = LogSanitizer.sanitize(line)
          String formatted = "[${label}] ${line}${System.lineSeparator()}".toString()
          String sanitized = "[${label}] ${sanitizedLine}${System.lineSeparator()}".toString()
          try {
            synchronized (logWriter) {
              writeLogLimited(sanitized)
            }
          } catch (IOException e) {
            log.warn("Failed to write to log file for label '{}': {}", label, e.getMessage())
          }
          appendVisible(formatted)
        }
      } catch (IOException e) {
        log.warn("Stream reading interrupted for label '{}': {}", label, e.getMessage())
        truncated = true
      }
    }

    private void appendVisible(String formatted) {
      synchronized (visibleOutput) {
        int remaining = remainingVisible.get()
        if (remaining <= 0) {
          truncated = true
          return
        }
        int toTake = Math.min(remaining, formatted.length())
        visibleOutput.append(formatted, 0, toTake)
        int after = remainingVisible.addAndGet(-toTake)
        if (toTake < formatted.length() || after <= 0) {
          truncated = true
        }
      }
    }

    private void writeLogLimited(String formatted) throws IOException {
      if (remainingLogCapacity == null) {
        logWriter.write(formatted)
        return
      }
      int remaining = remainingLogCapacity.get()
      if (remaining <= 0) {
        truncated = true
        return
      }
      int toTake = Math.min(remaining, formatted.length())
      logWriter.write(formatted, 0, toTake)
      int after = remainingLogCapacity.addAndGet(-toTake)
      if (toTake < formatted.length() || after <= 0) {
        truncated = true
      }
    }
  }
}
