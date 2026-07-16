package se.alipsa.lca.tools

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies that Workspace-backed tools follow a runtime base-dir change (the mechanism behind
 * the GUI header's folder chooser). Tools constructed with a fixed Path (all other specs) are
 * unaffected.
 */
class WorkspaceToolIntegrationSpec extends Specification {

  @TempDir
  Path tempDir

  Workspace workspace = new Workspace()

  def "GitTool follows the workspace base-dir change"() {
    given:
    Path repoA = initRepo("repoA", "branch-a")
    Path repoB = initRepo("repoB", "branch-b")
    GitTool git = new GitTool(workspace)

    when:
    workspace.changeBaseDir(repoA.toString())

    then:
    git.currentBranch() == "branch-a"

    when:
    workspace.changeBaseDir(repoB.toString())

    then:
    git.currentBranch() == "branch-b"
  }

  def "CommandRunner runs in the workspace base dir"() {
    given:
    Path dirA = Files.createDirectories(tempDir.resolve("a"))
    Files.writeString(dirA.resolve("marker-a.txt"), "x")
    Path dirB = Files.createDirectories(tempDir.resolve("b"))
    Files.writeString(dirB.resolve("marker-b.txt"), "y")
    CommandRunner runner = new CommandRunner(workspace)

    when:
    workspace.changeBaseDir(dirA.toString())
    def resultA = runner.run("ls", 10000L, 8000)

    then:
    resultA.output.contains("marker-a.txt")
    !resultA.output.contains("marker-b.txt")

    when:
    workspace.changeBaseDir(dirB.toString())
    def resultB = runner.run("ls", 10000L, 8000)

    then:
    resultB.output.contains("marker-b.txt")
  }

  def "FileEditingTool reads files from the workspace base dir"() {
    given:
    Path dirA = Files.createDirectories(tempDir.resolve("a"))
    Files.writeString(dirA.resolve("marker.txt"), "content-A")
    Path dirB = Files.createDirectories(tempDir.resolve("b"))
    Files.writeString(dirB.resolve("marker.txt"), "content-B")
    FileEditingTool tool = new FileEditingTool(workspace)

    when:
    workspace.changeBaseDir(dirA.toString())

    then:
    tool.readFile("marker.txt") == "content-A"

    when:
    workspace.changeBaseDir(dirB.toString())

    then:
    tool.readFile("marker.txt") == "content-B"
  }

  def "CodeSearchTool resolves its root from the live workspace, not a construction-time snapshot"() {
    given:
    Path dirA = Files.createDirectories(tempDir.resolve("a"))
    Path dirB = Files.createDirectories(tempDir.resolve("b"))
    CodeSearchTool tool = new CodeSearchTool(workspace)

    when:
    workspace.changeBaseDir(dirA.toString())

    then:
    tool.getProjectRoot().getFileName().toString() == "a"

    when:
    workspace.changeBaseDir(dirB.toString())

    then:
    tool.getProjectRoot().getFileName().toString() == "b"
  }

  def "TreeTool lists files from the workspace base dir"() {
    given:
    Path repoA = initRepo("repoA", "branch-a", "only-in-a.txt")
    Path repoB = initRepo("repoB", "branch-b", "only-in-b.txt")
    TreeTool tree = new TreeTool(workspace, new GitTool(workspace))

    when:
    workspace.changeBaseDir(repoA.toString())

    then:
    tree.buildTree(-1, false, 1000).treeText.contains("only-in-a.txt")

    when:
    workspace.changeBaseDir(repoB.toString())

    then:
    def result = tree.buildTree(-1, false, 1000).treeText
    result.contains("only-in-b.txt")
    !result.contains("only-in-a.txt")
  }

  private Path initRepo(String name, String branch, String extraFile = null) {
    Path repo = Files.createDirectories(tempDir.resolve(name))
    runGit(repo, "init")
    runGit(repo, "config", "user.name", "Test User")
    runGit(repo, "config", "user.email", "test@example.com")
    Files.writeString(repo.resolve("f.txt"), "hi")
    if (extraFile != null) {
      Files.writeString(repo.resolve(extraFile), "x")
    }
    runGit(repo, "add", "-A")
    runGit(repo, "commit", "-m", "init")
    runGit(repo, "branch", "-m", branch)
    repo
  }

  private void runGit(Path dir, String... args) {
    List<String> command = new ArrayList<>()
    command.add("git")
    command.addAll(Arrays.asList(args))
    Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start()
    if (process.waitFor() != 0) {
      throw new IllegalStateException("git command failed: ${command.join(' ')}")
    }
  }
}
