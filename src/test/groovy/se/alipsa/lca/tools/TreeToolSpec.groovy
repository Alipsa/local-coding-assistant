package se.alipsa.lca.tools

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class TreeToolSpec extends Specification {

  @TempDir
  Path tempDir

  def "tree respects gitignore when listing files"() {
    given:
    initRepo()
    Files.writeString(tempDir.resolve(".gitignore"), "build/\n")
    Path srcDir = tempDir.resolve("src")
    Files.createDirectories(srcDir)
    Files.writeString(srcDir.resolve("App.groovy"), "println 'hello'")
    Files.writeString(tempDir.resolve("notes.txt"), "todo")
    Path ignoredDir = tempDir.resolve("build")
    Files.createDirectories(ignoredDir)
    Files.writeString(ignoredDir.resolve("ignored.txt"), "ignored")
    runGit("add", "src/App.groovy")
    GitTool gitTool = new GitTool(tempDir)
    TreeTool treeTool = new TreeTool(tempDir, gitTool)

    when:
    def result = treeTool.buildTree(4, false, 0)

    then:
    result.success
    result.treeText.contains("src/")
    result.treeText.contains("App.groovy")
    result.treeText.contains("notes.txt")
    !result.treeText.contains("build/")
    !result.treeText.contains("ignored.txt")
  }

  def "tree returns error when git list fails"() {
    given:
    GitTool gitTool = Stub() {
      isGitRepo() >> true
      listFiles() >> new GitTool.GitResult(false, true, 1, "", "boom")
    }
    TreeTool treeTool = new TreeTool(tempDir, gitTool)

    when:
    def result = treeTool.buildTree(2, false, 0)

    then:
    !result.success
    result.repoPresent
    result.message.contains("boom")
  }

  def "tree returns not-a-repo when git repository is missing"() {
    given:
    GitTool gitTool = Stub() {
      isGitRepo() >> false
    }
    TreeTool treeTool = new TreeTool(tempDir, gitTool)

    when:
    def result = treeTool.buildTree(2, false, 0)

    then:
    !result.success
    !result.repoPresent
    result.message.contains("Not a git repository")
  }

  def "tree limits depth"() {
    given:
    TreeTool treeTool = buildStubTreeTool()

    when:
    def result = treeTool.buildTree(1, false, 0)

    then:
    result.treeText.contains("src/")
    !result.treeText.contains("App.groovy")
    !result.treeText.contains("guide.md")
  }

  def "tree supports directories only"() {
    given:
    TreeTool treeTool = buildStubTreeTool()

    when:
    def result = treeTool.buildTree(3, true, 0)

    then:
    result.treeText.contains("src/")
    result.treeText.contains("docs/")
    !result.treeText.contains("README.md")
    !result.treeText.contains("App.groovy")
  }

  def "tree truncates when max entries is set"() {
    given:
    TreeTool treeTool = buildStubTreeTool()

    when:
    def result = treeTool.buildTree(4, false, 2)

    then:
    result.truncated
    result.entryCount == 2
  }

  private void initRepo() {
    runGit("init")
    runGit("config", "user.email", "test@example.com")
    runGit("config", "user.name", "Test User")
  }

  private TreeTool buildStubTreeTool() {
    GitTool gitTool = Stub() {
      isGitRepo() >> true
      listFiles() >> new GitTool.GitResult(
        true,
        true,
        0,
        "src/App.groovy\nsrc/internal/Util.groovy\nREADME.md\ndocs/guide.md\n",
        ""
      )
    }
    new TreeTool(tempDir, gitTool)
  }

  private void runGit(String... args) {
    List<String> command = ["git"] + args.toList()
    Process process = new ProcessBuilder(command)
      .directory(tempDir.toFile())
      .redirectErrorStream(true)
      .start()
    int exit = process.waitFor()
    if (exit != 0) {
      throw new IllegalStateException("Git command failed: ${command.join(' ')}")
    }
  }
}
