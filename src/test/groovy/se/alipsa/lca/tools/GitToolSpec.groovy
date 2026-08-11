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

  def "getRealProjectRoot falls back to the uncanonicalised root when it can't be resolved"() {
    given:
    Path missing = tempDir.resolve("does-not-exist")
    GitTool tool = new GitTool(missing)

    expect:
    tool.getRealProjectRoot() == missing
  }

  def "getRealProjectRoot canonicalises and caches an existing root"() {
    expect:
    gitTool.getRealProjectRoot() == tempDir.toRealPath()
    gitTool.getRealProjectRoot() == gitTool.getRealProjectRoot()
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

  def "listFiles includes tracked and untracked but not ignored"() {
    given:
    initRepo()
    Files.writeString(tempDir.resolve(".gitignore"), "ignored/\n")
    Files.createDirectories(tempDir.resolve("ignored"))
    Files.writeString(tempDir.resolve("ignored/skip.txt"), "skip")
    Files.writeString(tempDir.resolve("tracked.txt"), "tracked")
    Files.writeString(tempDir.resolve("notes.txt"), "notes")
    runGit("add", "tracked.txt")

    when:
    def result = gitTool.listFiles()

    then:
    result.success
    result.output.contains("tracked.txt")
    result.output.contains("notes.txt")
    !result.output.contains("ignored/skip.txt")
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

  def "prDiff returns error when gh is not available"() {
    given:
    initRepo()

    when:
    def result = gitTool.prDiff(999)

    then:
    !result.success
    result.error != null && !result.error.isEmpty()
  }

  def "prChangedFiles returns error when gh is not available"() {
    given:
    initRepo()

    when:
    def result = gitTool.prChangedFiles(999)

    then:
    !result.success
    result.error != null && !result.error.isEmpty()
  }

  def "prDiff rejects non-positive PR number"() {
    when:
    def result = gitTool.prDiff(0)

    then:
    !result.success
    result.error.contains("PR number must be positive")
  }

  def "prChangedFiles rejects non-positive PR number"() {
    when:
    def result = gitTool.prChangedFiles(0)

    then:
    !result.success
    result.error.contains("PR number must be positive")
  }

  def "prHeadCommit rejects non-positive PR number"() {
    when:
    def result = gitTool.prHeadCommit(0)

    then:
    !result.success
    result.error.contains("PR number must be positive")
  }

  def "prHeadCommit returns error when gh is not available"() {
    given:
    initRepo()

    when:
    def result = gitTool.prHeadCommit(999)

    then:
    !result.success
    result.error != null && !result.error.isEmpty()
  }

  def "showFileAtCommit returns file content at a given commit"() {
    given:
    initRepo()
    Path file = tempDir.resolve("sample.txt")
    Files.writeString(file, "original content\n")
    runGit("add", "sample.txt")
    runGit("commit", "-m", "init")
    String sha = runGitCapture("rev-parse", "HEAD").trim()
    Files.writeString(file, "changed content\n")

    when:
    def result = gitTool.showFileAtCommit(sha, "sample.txt")

    then:
    result.success
    result.output.contains("original content")
    !result.output.contains("changed content")
  }

  def "showFileAtCommit fails for a path that does not exist at that commit"() {
    given:
    initRepo()
    Files.writeString(tempDir.resolve("sample.txt"), "content\n")
    runGit("add", "sample.txt")
    runGit("commit", "-m", "init")
    String sha = runGitCapture("rev-parse", "HEAD").trim()

    when:
    def result = gitTool.showFileAtCommit(sha, "missing.txt")

    then:
    !result.success
  }

  def "fetchPullRequestRef reports failure for a nonexistent PR ref"() {
    given:
    initRepo()
    Files.writeString(tempDir.resolve("a.txt"), "hello\n")
    runGit("add", "a.txt")
    runGit("commit", "-m", "init")

    when:
    def result = gitTool.fetchPullRequestRef(999)

    then:
    !result.success
  }

  def "openPullRequestsForCurrentBranch reports missing repository"() {
    when:
    def result = gitTool.openPullRequestsForCurrentBranch()

    then:
    !result.success
    !result.repoPresent
  }

  def "openPullRequestsForCurrentBranch returns error when gh is not available"() {
    given:
    initRepo()
    Files.writeString(tempDir.resolve("a.txt"), "hello\n")
    runGit("add", "a.txt")
    runGit("commit", "-m", "init")

    when:
    def result = gitTool.openPullRequestsForCurrentBranch()

    then:
    !result.success
    result.repoPresent
    result.error != null && !result.error.isEmpty()
  }

  def "openPullRequestsForCurrentBranch caches its result for the same branch within the TTL"() {
    given: "a repeated call would otherwise re-hit gh/GitHub on every poll, e.g. after every chat turn"
    initRepo()
    Files.writeString(tempDir.resolve("a.txt"), "hello\n")
    runGit("add", "a.txt")
    runGit("commit", "-m", "init")

    when:
    def first = gitTool.openPullRequestsForCurrentBranch()
    def second = gitTool.openPullRequestsForCurrentBranch()

    then: "the exact same result instance is reused, proving the second call didn't re-fetch"
    first.is(second)
  }

  def "openPullRequestsForCurrentBranch does not reuse the cache across a branch switch"() {
    given:
    initRepo()
    Files.writeString(tempDir.resolve("a.txt"), "hello\n")
    runGit("add", "a.txt")
    runGit("commit", "-m", "init")
    runGit("branch", "-m", "main")

    when:
    def onMain = gitTool.openPullRequestsForCurrentBranch()
    runGit("checkout", "-b", "feature-x")
    def onFeature = gitTool.openPullRequestsForCurrentBranch()

    then:
    !onMain.is(onFeature)
  }

  def "parsePullRequestJson parses a gh pr list JSON array"() {
    given:
    String json = '[{"number":7,"title":"Add feature","url":"https://example/pull/7",' +
      '"headRefName":"feature/x","state":"OPEN"}]'

    expect:
    GitTool.parsePullRequestJson(json).size() == 1
    GitTool.parsePullRequestJson(json)[0].number == 7
    GitTool.parsePullRequestJson(json)[0].title == "Add feature"
  }

  def "parsePullRequestJson returns an empty list for blank or invalid input"() {
    expect:
    GitTool.parsePullRequestJson(null).isEmpty()
    GitTool.parsePullRequestJson("").isEmpty()
    GitTool.parsePullRequestJson("not json").isEmpty()
  }

  def "currentBranch returns null when not a repository"() {
    expect:
    gitTool.currentBranch() == null
  }

  def "currentBranch reports the checked-out branch"() {
    given:
    initRepo()
    Files.writeString(tempDir.resolve("a.txt"), "hello\n")
    runGit("add", "a.txt")
    runGit("commit", "-m", "init")
    runGit("branch", "-m", "feature-xyz")

    expect:
    gitTool.currentBranch() == "feature-xyz"
  }

  def "listLocalBranches returns the local branch names"() {
    given:
    initRepo()
    Files.writeString(tempDir.resolve("a.txt"), "hello\n")
    runGit("add", "a.txt")
    runGit("commit", "-m", "init")
    runGit("branch", "-m", "main")
    runGit("branch", "feature-a")
    runGit("branch", "feature-b")

    expect:
    gitTool.listLocalBranches().toSet() == ["main", "feature-a", "feature-b"].toSet()
  }

  def "listLocalBranches returns an empty list when not a repository"() {
    expect:
    gitTool.listLocalBranches().isEmpty()
  }

  def "listRemoteBranches returns tracking branches and excludes HEAD"() {
    given:
    Path originDir = Files.createTempDirectory("gittool-origin")
    runGitIn(originDir, "init")
    runGitIn(originDir, "config", "user.name", "Origin User")
    runGitIn(originDir, "config", "user.email", "origin@example.com")
    Files.writeString(originDir.resolve("a.txt"), "hello\n")
    runGitIn(originDir, "add", "a.txt")
    runGitIn(originDir, "commit", "-m", "init")
    runGitIn(originDir, "branch", "-m", "main")
    runGitIn(originDir, "branch", "release-1")

    and:
    initRepo()
    runGit("remote", "add", "origin", originDir.toString())
    runGit("fetch", "origin")

    when:
    List<String> remotes = gitTool.listRemoteBranches()

    then:
    remotes.contains("origin/main")
    remotes.contains("origin/release-1")
    remotes.every { !it.endsWith("/HEAD") }

    cleanup:
    originDir.toFile().deleteDir()
  }

  private void runGitIn(Path dir, String... args) {
    List<String> command = new ArrayList<>()
    command.add("git")
    command.addAll(List.of(args))
    ProcessBuilder pb = new ProcessBuilder(command)
    pb.directory(dir.toFile())
    pb.redirectErrorStream(true)
    Process process = pb.start()
    int exit = process.waitFor()
    if (exit != 0) {
      throw new IllegalStateException("Git command failed: ${command.join(' ')} (exit ${exit})")
    }
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
