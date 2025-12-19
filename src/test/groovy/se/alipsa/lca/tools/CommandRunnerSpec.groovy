package se.alipsa.lca.tools

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

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
}
