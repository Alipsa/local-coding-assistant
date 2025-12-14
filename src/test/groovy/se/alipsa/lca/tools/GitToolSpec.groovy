package se.alipsa.lca.tools

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList

class GitToolSpec extends Specification {

  @TempDir
  Path tempDir

  GitTool gitTool

  def setup() {
    gitTool = new GitTool(tempDir)
  }

  def "status reports missing repository"() {
    when:
    def status = gitTool.status(false)

    then:
    !status.repoPresent
    !status.success
    status.error.toLowerCase().contains("git")
  }

  def "diff and hunk staging work in a repository"() {
    given:
    initRepo()
    Path file = tempDir.resolve("sample.txt")
    Files.writeString(
      file,
      """line1
line2
line3
line4
line5
line6
"""
    )
    runGit("add", "sample.txt")
    runGit("commit", "-m", "init")
    Files.writeString(
      file,
      """line1 updated
line2
line3
line4
line5 updated
line6
"""
    )

    when:
    def diff = gitTool.diff(false, List.of("sample.txt"), 0, false)

    then:
    diff.success
    diff.output.contains("-line1")
    diff.output.contains("+line1 updated")
    diff.output.contains("+line5 updated")

    when:
    def stageResult = gitTool.stageHunks("sample.txt", List.of(1))
    def staged = gitTool.stagedDiff()

    then:
    stageResult.success
    staged.success
    staged.output.contains("+line1 updated")
    !staged.output.contains("+line5 updated")
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
    process.waitFor()
  }
}
