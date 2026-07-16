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

  private Path initRepo(String name, String branch) {
    Path repo = Files.createDirectories(tempDir.resolve(name))
    runGit(repo, "init")
    runGit(repo, "config", "user.name", "Test User")
    runGit(repo, "config", "user.email", "test@example.com")
    Files.writeString(repo.resolve("f.txt"), "hi")
    runGit(repo, "add", "f.txt")
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
