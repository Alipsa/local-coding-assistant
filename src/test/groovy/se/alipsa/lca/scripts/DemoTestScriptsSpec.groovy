package se.alipsa.lca.scripts

import spock.lang.Specification
import spock.lang.Unroll

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

/**
 * Runs every demo/test_*.sh script as part of ./mvnw test, so a demo test script actually
 * gets executed instead of only being runnable by hand. A hand-authored bash test for
 * _quant_suffix existed and would have caught the set -e/exit-1 regression, but nothing wired
 * it into CI or ./mvnw test, so the bug shipped anyway.
 */
class DemoTestScriptsSpec extends Specification {

  private static final long TIMEOUT_SECONDS = 120L

  @Unroll
  def "demo test script passes: #scriptName"() {
    given:
    Path scriptPath = demoDir().resolve(scriptName)

    when:
    ProcessBuilder processBuilder = new ProcessBuilder("bash", scriptPath.toString())
    processBuilder.directory(demoDir().toFile())
    processBuilder.redirectErrorStream(true)
    Process process = processBuilder.start()
    // Read stdout on a separate thread: some of these scripts resolve tools (opencode,
    // python3, pip) off the ambient PATH and can reach a real, network-touching command
    // on a misconfigured environment. process.inputStream.text blocks until the stream
    // closes, so a hung subprocess would hang here even before any waitFor() timeout is
    // reached - the reader has to run concurrently with the bounded wait below, not before it.
    StringBuilder outputBuffer = new StringBuilder()
    Thread reader = new Thread({ ->
      process.inputStream.eachLine { line -> outputBuffer.append(line).append('\n') }
    } as Runnable)
    reader.daemon = true
    reader.start()

    boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      process.waitFor(5, TimeUnit.SECONDS)
    }
    reader.join(5000)
    String output = outputBuffer.toString()

    then:
    finished
    process.exitValue() == 0
    !output.contains("FAIL:")

    where:
    scriptName << demoTestScriptNames()
  }

  private static Path demoDir() {
    Paths.get("").toAbsolutePath().normalize().resolve("demo")
  }

  private static List<String> demoTestScriptNames() {
    List<String> tracked = gitTrackedDemoTestScripts()
    Files.list(demoDir()).withCloseable { stream ->
      stream.map { it.fileName.toString() }
        .filter { it.startsWith("test_") && it.endsWith(".sh") && it != "test_helpers.sh" }
        .filter { tracked.contains(it) }
        .sorted()
        .collect(Collectors.toList())
    }
  }

  /**
   * Restricts discovery to files git actually tracks under demo/, so an untracked scratch
   * script (e.g. a developer's local demo/test_whatever.sh) doesn't silently join the build.
   * Falls back to "no filtering" (empty exclusion) only if git itself is unavailable, since a
   * missing git binary is an environment problem this spec shouldn't mask a script list over.
   */
  private static List<String> gitTrackedDemoTestScripts() {
    ProcessBuilder processBuilder = new ProcessBuilder("git", "ls-files", "demo")
    processBuilder.directory(demoDir().parent.toFile())
    processBuilder.redirectErrorStream(true)
    Process process = processBuilder.start()
    String output = process.inputStream.text
    boolean finished = process.waitFor(30, TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      return demoTestScriptNamesOnDisk()
    }
    if (process.exitValue() != 0) {
      return demoTestScriptNamesOnDisk()
    }
    output.readLines()
      .findAll { it.startsWith("demo/") }
      .collect { it.substring("demo/".length()) }
  }

  private static List<String> demoTestScriptNamesOnDisk() {
    Files.list(demoDir()).withCloseable { stream ->
      stream.map { it.fileName.toString() }.collect(Collectors.toList())
    }
  }
}
