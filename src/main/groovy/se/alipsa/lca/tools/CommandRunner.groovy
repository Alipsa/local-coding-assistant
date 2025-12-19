package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

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
    long effectiveTimeout = timeoutMillis > 0 ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS
    int outputLimit = maxOutputChars > 0 ? maxOutputChars : DEFAULT_OUTPUT_LIMIT
    Path logPath
    try {
      logPath = createLogPath()
    } catch (IOException e) {
      logPath = null
      log.warn("Failed to prepare log path for command {}", command, e)
    }
    Instant started = Instant.now()
    boolean timedOut = false
    Process process = null
    BufferedWriter logWriter = null
    try {
      logWriter = prepareWriter(logPath)
      writeHeader(logWriter, command, started)
      process = startProcess(command)
      AtomicInteger remaining = new AtomicInteger(outputLimit)
      StringBuffer visibleOutput = new StringBuffer()
      StreamCollector outCollector = new StreamCollector(
        process.getInputStream(),
        logWriter,
        "OUT",
        visibleOutput,
        remaining
      )
      StreamCollector errCollector = new StreamCollector(
        process.getErrorStream(),
        logWriter,
        "ERR",
        visibleOutput,
        remaining
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
      try {
        exitCode = process.exitValue()
      } catch (IllegalThreadStateException ignored) {
        exitCode = -1
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
      log.warn("Command execution failed: {}", command, e)
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

  @CompileStatic
  private static class StreamCollector implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(StreamCollector)
    private final InputStream stream
    private final BufferedWriter logWriter
    private final String label
    private final StringBuffer visibleOutput
    private final AtomicInteger remainingVisible
    volatile boolean truncated = false

    StreamCollector(
      InputStream stream,
      BufferedWriter logWriter,
      String label,
      StringBuffer visibleOutput,
      AtomicInteger remainingVisible
    ) {
      this.stream = stream
      this.logWriter = logWriter
      this.label = label
      this.visibleOutput = visibleOutput
      this.remainingVisible = remainingVisible
    }

    @Override
    void run() {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        String line
        while ((line = reader.readLine()) != null) {
          String formatted = "[${label}] ${line}${System.lineSeparator()}".toString()
          try {
            synchronized (logWriter) {
              logWriter.write(formatted)
            }
          } catch (IOException e) {
            log.warn("Failed to write to log file for label '{}': {}", label, e.message)
          }
          appendVisible(formatted)
        }
      } catch (IOException e) {
        log.warn("Stream reading interrupted for label '{}': {}", label, e.message)
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
  }
}
