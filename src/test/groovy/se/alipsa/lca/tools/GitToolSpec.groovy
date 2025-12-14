package se.alipsa.lca.tools

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

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

  def "stageHunks error cases are reported"() {
    when:
    def missingFile = gitTool.stageHunks("", List.of(1))

    then:
    !missingFile.success
    missingFile.error.contains("File path is required.")

    when:
    def noHunks = gitTool.stageHunks("file.txt", List.of())

    then:
    !noHunks.success
    noHunks.error.contains("Provide at least one hunk")

    when:
    initRepo()
    Path file = tempDir.resolve("nodiff.txt")
    Files.writeString(file, "line")
    runGit("add", "nodiff.txt")
    runGit("commit", "-m", "base")
    def noDiff = gitTool.stageHunks("nodiff.txt", List.of(1))

    then:
    !noDiff.success
    noDiff.error.contains("No diff available")

    when:
    Files.writeString(file, "line changed")
    def noMatch = gitTool.stageHunks("nodiff.txt", List.of(2))

    then:
    !noMatch.success
    noMatch.error.contains("No matching hunks")
  }

  def "stageHunks supports second and multiple hunks"() {
    given:
    initRepo()
    Path file = tempDir.resolve("multi.txt")
    Files.writeString(
      file,
      """one
two
three
four
five
six
"""
    )
    runGit("add", "multi.txt")
    runGit("commit", "-m", "init multi")
    Files.writeString(
      file,
      """one updated
two
three
four
five updated
six
"""
    )

    when:
    def secondOnly = gitTool.stageHunks("multi.txt", List.of(2))
    def staged = gitTool.stagedDiff()

    then:
    secondOnly.success
    staged.output.contains("five updated")
    !staged.output.contains("one updated")

    when:
    runGit("reset", "HEAD")
    def both = gitTool.stageHunks("multi.txt", List.of(2, 1))
    def stagedBoth = gitTool.stagedDiff()

    then:
    both.success
    stagedBoth.output.contains("one updated")
    stagedBoth.output.contains("five updated")
  }

  def "stageFiles stages multiple files"() {
    given:
    initRepo()
    Files.writeString(tempDir.resolve("a.txt"), "a")
    Files.writeString(tempDir.resolve("b.txt"), "b")

    when:
    def result = gitTool.stageFiles(List.of("a.txt", "b.txt"))
    String staged = runGitCapture("diff", "--cached", "--name-only")

    then:
    result.success
    staged.contains("a.txt")
    staged.contains("b.txt")
  }

  def "stageFiles rejects traversal outside project"() {
    when:
    gitTool.stageFiles(["../../etc/passwd"])

    then:
    thrown(IllegalArgumentException)
  }

  def "stageFiles rejects absolute path outside project"() {
    given:
    Path outside = tempDir.getParent().resolve("outside.txt")
    Files.writeString(outside, "content")

    when:
    gitTool.stageFiles([outside.toString()])

    then:
    thrown(IllegalArgumentException)
  }

  def "applyPatch supports check and cached"() {
    given:
    initRepo()
    Path sample = tempDir.resolve("sample.txt")
    Path cachedFile = tempDir.resolve("cached.txt")
    Files.writeString(sample, "hello\nstay\n")
    Files.writeString(cachedFile, "cached\nstay\n")
    runGit("add", "sample.txt", "cached.txt")
    runGit("commit", "-m", "baseline")
    String patch = """--- a/sample.txt
+++ b/sample.txt
@@ -1,2 +1,2 @@
-hello
+hello world
 stay
"""
    String cachedPatch = """--- a/cached.txt
+++ b/cached.txt
@@ -1,2 +1,2 @@
-cached
+cached staged
 stay
"""

    when:
    def checkResult = gitTool.applyPatch(patch, false, true)
    String before = Files.readString(sample)

    then:
    checkResult.success
    before.contains("hello\nstay")

    when:
    def applyResult = gitTool.applyPatch(patch, false, false)
    String updated = Files.readString(sample)

    then:
    applyResult.success
    updated.contains("hello world")

    when:
    def cachedResult = gitTool.applyPatch(cachedPatch, true, false)
    String cachedContent = Files.readString(cachedFile)
    String cachedDiff = runGitCapture("diff", "--cached")

    then:
    cachedResult.success
    cachedContent.contains("cached\nstay")
    cachedDiff.contains("cached staged")
  }

  def "push succeeds to local bare remote"() {
    given:
    initRepo()
    Path file = tempDir.resolve("push.txt")
    Files.writeString(file, "push")
    runGit("add", "push.txt")
    runGit("commit", "-m", "push it")
    Path remote = tempDir.resolve("remote.git")
    runGit("init", "--bare", remote.toString())
    runGit("remote", "add", "origin", remote.toString())
    runGit("config", "push.default", "current")

    when:
    def result = gitTool.push(false)

    then:
    result.success
    result.repoPresent
    !result.error.toLowerCase().contains("fatal")
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
      throw new IllegalStateException("Git command failed: ${command.join(' ')} (exit ${exit})")
    }
  }

  private String runGitCapture(String... args) {
    List<String> command = new ArrayList<>()
    command.add("git")
    command.addAll(List.of(args))
    ProcessBuilder pb = new ProcessBuilder(command)
    pb.directory(tempDir.toFile())
    pb.redirectErrorStream(true)
    Process process = pb.start()
    String output = new String(process.getInputStream().readAllBytes())
    process.waitFor()
    output
  }
}
