package se.alipsa.lca.tools

import spock.lang.Specification
import spock.lang.TempDir

import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class CommandRunnerSpec extends Specification {

  @TempDir
  Path tempDir

  def "run executes command and captures output"() {
    given:
    CommandRunner runner = new CommandRunner(tempDir)

    when:
    CommandRunner.CommandResult result = runner.run("echo hi", 2000L, 200)

    then:
    result.success
    !result.timedOut
    result.output.contains("hi")
    result.logPath != null
    Files.exists(result.logPath)
  }

  def "run respects timeout"() {
    given:
    CommandRunner runner = new CommandRunner(tempDir)

    when:
    CommandRunner.CommandResult result = runner.run("sleep 1", 100L, 100)

    then:
    result.timedOut
    !result.success
    result.exitCode == -1
  }

  def "run truncates output"() {
    given:
    CommandRunner runner = new CommandRunner(tempDir)

    when:
    CommandRunner.CommandResult result = runner.run("printf 'abcdefghij'", 2000L, 5)

    then:
    result.truncated
    result.output.length() <= 5
  }

  def "run returns failure on empty command"() {
    when:
    CommandRunner.CommandResult result = new CommandRunner(tempDir).run("  ", 1000L, 100)

    then:
    !result.success
    result.exitCode == 1
    result.output.contains("No command")
  }

  def "run proceeds when log creation fails"() {
    given:
    CommandRunner runner = new CommandRunner(tempDir) {
      @Override
      protected Path createLogPath() throws IOException {
        throw new IOException("no log")
      }
    }

    when:
    CommandRunner.CommandResult result = runner.run("echo hi", 1000L, 200)

    then:
    result.success
    result.logPath == null
    result.output.contains("hi")
  }

  def "log contains header and footer fields"() {
    given:
    CommandRunner runner = new CommandRunner(tempDir)

    when:
    CommandRunner.CommandResult result = runner.run("echo hi", 2000L, 200)
    List<String> lines = Files.readAllLines(result.logPath)

    then:
    lines.any { it.startsWith("Command: ") }
    lines.any { it.startsWith("Started: ") }
    lines.any { it.startsWith("Completed: ") }
    lines.any { it.startsWith("DurationMillis: ") }
    lines.any { it.startsWith("ExitCode: ") }
    lines.any { it.startsWith("TimedOut: ") }
  }

  def "run marks timeout and cleans up live process"() {
    given:
    FakeProcess fake = new FakeProcess()
    CommandRunner runner = new CommandRunner(tempDir) {
      @Override
      protected Process startProcess(String command) {
        fake
      }
    }

    when:
    CommandRunner.CommandResult result = runner.run("long task", 10L, 100)

    then:
    result.timedOut
    !result.success
    fake.destroyCalled
  }

  private static class FakeProcess extends Process {
    boolean destroyCalled = false

    @Override
    OutputStream getOutputStream() { OutputStream.nullOutputStream() }

    @Override
    InputStream getInputStream() { new ByteArrayInputStream(new byte[0]) }

    @Override
    InputStream getErrorStream() { new ByteArrayInputStream(new byte[0]) }

    @Override
    int waitFor() { 0 }

    @Override
    boolean waitFor(long timeout, TimeUnit unit) { false }

    @Override
    int exitValue() { -1 }

    @Override
    void destroy() { destroyCalled = true }

    @Override
    Process destroyForcibly() { destroyCalled = true; this }

    @Override
    boolean isAlive() { !destroyCalled }
  }
}
