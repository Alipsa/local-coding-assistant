package se.alipsa.lca.scripts

import spock.lang.Specification
import spock.lang.Unroll

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * Runs every demo/test_*.sh script as part of ./mvnw test, so a demo test script actually
 * gets executed instead of only being runnable by hand. A hand-authored bash test for
 * _quant_suffix existed and would have caught the set -e/exit-1 regression, but nothing wired
 * it into CI or ./mvnw test, so the bug shipped anyway.
 */
class DemoTestScriptsSpec extends Specification {

  @Unroll
  def "demo test script passes: #scriptName"() {
    given:
    Path scriptPath = demoDir().resolve(scriptName)

    when:
    ProcessBuilder processBuilder = new ProcessBuilder("bash", scriptPath.toString())
    processBuilder.directory(demoDir().toFile())
    processBuilder.redirectErrorStream(true)
    Process process = processBuilder.start()
    String output = process.inputStream.text
    int exitCode = process.waitFor()

    then:
    exitCode == 0
    !output.contains("FAIL:")

    where:
    scriptName << demoTestScriptNames()
  }

  private static Path demoDir() {
    Paths.get("").toAbsolutePath().normalize().resolve("demo")
  }

  private static List<String> demoTestScriptNames() {
    Files.list(demoDir()).withCloseable { stream ->
      stream.map { it.fileName.toString() }
        .filter { it.startsWith("test_") && it.endsWith(".sh") && it != "test_helpers.sh" }
        .sorted()
        .collect(Collectors.toList())
    }
  }
}
