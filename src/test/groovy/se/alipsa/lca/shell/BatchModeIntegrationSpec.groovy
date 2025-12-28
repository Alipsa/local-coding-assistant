package se.alipsa.lca.shell

import groovy.transform.Canonical
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class BatchModeIntegrationSpec extends Specification {

  @TempDir
  Path tempDir

  def "batch mode runs commands in a git repo"() {
    given:
    initRepo()
    Files.writeString(tempDir.resolve("notes.txt"), "hello")

    when:
    ProcessResult result = runBatch(tempDir, "-c", "/status; /tree")

    then:
    result.exitCode == 0
    result.output.contains("> /status")
    result.output.contains("[OK] /status")
    result.output.contains("> /tree")
    result.output.contains("[OK] /tree")
    result.output.contains("notes.txt")
  }

  def "batch file mode executes commands"() {
    given:
    initRepo()
    Files.writeString(tempDir.resolve("alpha.txt"), "alpha")
    Path batchFile = tempDir.resolve("batch.txt")
    Files.writeString(
      batchFile,
      "/status\n" +
        "/codesearch --query alpha --paths alpha.txt\n"
    )

    when:
    ProcessResult result = runBatch(tempDir, "--batch-file", batchFile.toString())

    then:
    result.exitCode == 0
    result.output.contains("> /status")
    result.output.contains("[OK] /status")
    result.output.contains("> /codesearch --query alpha --paths alpha.txt")
    result.output.contains("[OK] /codesearch --query alpha --paths alpha.txt")
    result.output.contains("Code Search")
    result.output.contains("alpha.txt")
  }

  private void initRepo() {
    runGit("init")
    runGit("config", "user.name", "Test User")
    runGit("config", "user.email", "test@example.com")
  }

  private void runGit(String... args) {
    List<String> command = new ArrayList<>()
    command.add("git")
    command.addAll(List.of(args))
    ProcessBuilder pb = new ProcessBuilder(command)
    pb.directory(tempDir.toFile())
    pb.redirectErrorStream(true)
    Process process = pb.start()
    int exit = process.waitFor()
    if (exit != 0) {
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
      throw new IllegalStateException("Git command failed: ${command.join(' ')} (exit ${exit}): ${output}")
    }
  }

  private ProcessResult runBatch(Path workingDir, String... args) {
    List<String> command = new ArrayList<>()
    command.add(resolveJavaBin())
    command.add("-cp")
    command.add(System.getProperty("java.class.path"))
    command.add("se.alipsa.lca.LocalCodingAssistantApplication")
    command.add("--spring.profiles.active=batch-test")
    command.addAll(List.of(args))
    ProcessBuilder pb = new ProcessBuilder(command)
    pb.directory(workingDir.toFile())
    pb.redirectErrorStream(true)
    Path outputFile = Files.createTempFile(workingDir, "batch-output-", ".log")
    pb.redirectOutput(outputFile.toFile())
    Process process = pb.start()
    boolean finished = process.waitFor(90, TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      throw new IllegalStateException("Batch process timed out.")
    }
    String output = Files.readString(outputFile, StandardCharsets.UTF_8)
    new ProcessResult(process.exitValue(), output)
  }

  private String resolveJavaBin() {
    String javaHome = System.getProperty("java.home")
    String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java"
    Paths.get(javaHome, "bin", exe).toString()
  }

  @Canonical
  static class ProcessResult {
    int exitCode
    String output
  }
}
