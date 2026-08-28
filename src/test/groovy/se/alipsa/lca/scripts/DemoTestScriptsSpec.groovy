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
  private static final long GIT_TIMEOUT_SECONDS = 30L

  @Unroll
  def "demo test script passes: #scriptName"() {
    given:
    Path scriptPath = demoDir().resolve(scriptName)
    ProcessBuilder processBuilder = new ProcessBuilder("bash", scriptPath.toString())
    processBuilder.directory(demoDir().toFile())
    processBuilder.redirectErrorStream(true)

    when:
    ProcessResult result = runBounded(processBuilder, TIMEOUT_SECONDS)

    then:
    result.finished
    result.exitCode == 0
    !result.output.contains("FAIL:")

    where:
    scriptName << demoTestScriptNames()
  }

  private static Path demoDir() {
    Paths.get("").toAbsolutePath().normalize().resolve("demo")
  }

  private static List<String> demoTestScriptNames() {
    List<String> tracked = gitTrackedDemoTestScripts()
    List<String> names = Files.list(demoDir()).withCloseable { stream ->
      stream.map { it.fileName.toString() }
        .filter { it.startsWith("test_") && it.endsWith(".sh") && it != "test_helpers.sh" }
        .filter { tracked.contains(it) }
        .sorted()
        .collect(Collectors.toList())
    }
    // Spock already errors ("Data provider has no data") on an empty where: list rather than
    // silently running zero iterations, but that generic message doesn't say WHY the list is
    // empty. Fail with a diagnosis instead: this filters to git-tracked names, so an empty
    // result almost certainly means the tracked-files lookup came back empty (e.g. demo/ not
    // tracked from this checkout), not that the demo/ directory itself has no test scripts.
    assert !names.isEmpty() : "no demo test scripts discovered under ${demoDir()} " +
      "(${tracked.size()} git-tracked demo/ entries) - check gitTrackedDemoTestScripts()"
    names
  }

  /**
   * Restricts discovery to files git actually tracks under demo/, so an untracked scratch
   * script (e.g. a developer's local demo/test_whatever.sh) doesn't silently join the build.
   * Falls back to "no filtering" (unfiltered disk listing) only if the git subprocess itself
   * fails or times out, since a missing/hung git is an environment problem this spec shouldn't
   * mask a script list over.
   */
  private static List<String> gitTrackedDemoTestScripts() {
    ProcessBuilder processBuilder = new ProcessBuilder("git", "ls-files", "demo")
    processBuilder.directory(demoDir().parent.toFile())
    processBuilder.redirectErrorStream(true)
    ProcessResult result = runBounded(processBuilder, GIT_TIMEOUT_SECONDS)
    if (!result.finished || result.exitCode != 0) {
      return demoTestScriptNamesOnDisk()
    }
    result.output.readLines()
      .findAll { it.startsWith("demo/") }
      .collect { it.substring("demo/".length()) }
  }

  private static List<String> demoTestScriptNamesOnDisk() {
    Files.list(demoDir()).withCloseable { stream ->
      stream.map { it.fileName.toString() }.collect(Collectors.toList())
    }
  }

  private static final class ProcessResult {
    final boolean finished
    final int exitCode
    final String output

    ProcessResult(boolean finished, int exitCode, String output) {
      this.finished = finished
      this.exitCode = exitCode
      this.output = output
    }
  }

  /**
   * Runs processBuilder to completion (or forcibly kills it past timeoutSeconds), reading its
   * output concurrently rather than before the bounded wait. process.inputStream.text (or
   * .eachLine on the main thread) blocks until the stream closes, so a hung subprocess would
   * hang there even before any waitFor() timeout is reached - the read has to happen on a
   * separate thread that runs alongside the bounded wait, not sequentially before it, or the
   * timeout is decorative. Uses StringBuffer (not StringBuilder): on the timeout path the
   * reader thread may still be writing when reader.join(5000) returns without the thread
   * having terminated, so the subsequent toString() read has no happens-before guarantee from
   * join() alone and needs the reader's own internal synchronization instead.
   */
  private static ProcessResult runBounded(ProcessBuilder processBuilder, long timeoutSeconds) {
    Process process = processBuilder.start()
    StringBuffer outputBuffer = new StringBuffer()
    Thread reader = new Thread({ ->
      process.inputStream.eachLine { line -> outputBuffer.append(line).append('\n') }
    } as Runnable)
    reader.daemon = true
    reader.start()

    boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      process.waitFor(5, TimeUnit.SECONDS)
    }
    reader.join(5000)
    int exitCode = finished ? process.exitValue() : -1
    new ProcessResult(finished, exitCode, outputBuffer.toString())
  }
}
