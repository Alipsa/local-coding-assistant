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

  def "tree respects aiexclude patterns"() {
    given:
    Files.writeString(tempDir.resolve(".aiexclude"), "docs/\n")
    TreeTool treeTool = buildStubTreeTool()

    when:
    def result = treeTool.buildTree(3, false, 0)

    then:
    // Verify the excluded directory is not shown
    !result.treeText.contains("docs/")
    // Verify descendant files within the excluded directory are also blocked
    !result.treeText.contains("guide.md")
    // Verify non-excluded content is still included
    result.treeText.contains("src/")
    result.treeText.contains("README.md")
  }

  def "tree blocks files within excluded directories"() {
    given:
    // Create stub that returns multiple files in docs/ directory
    Files.writeString(tempDir.resolve(".aiexclude"), "docs/\n")
    GitTool gitTool = Stub() {
      isGitRepo() >> true
      listFiles() >> new GitTool.GitResult(
        true,
        true,
        0,
        "src/App.groovy\ndocs/guide.md\ndocs/tutorial.md\ndocs/api/reference.md\nREADME.md\n",
        ""
      )
    }
    TreeTool treeTool = new TreeTool(tempDir, gitTool)

    when:
    def result = treeTool.buildTree(5, false, 0)

    then:
    result.success
    // Verify the excluded directory itself is not shown
    !result.treeText.contains("docs/")
    // Verify all descendant files within the excluded directory are blocked
    !result.treeText.contains("guide.md")
    !result.treeText.contains("tutorial.md")
    !result.treeText.contains("reference.md")
    !result.treeText.contains("api/")
    // Verify non-excluded content is still present
    result.treeText.contains("src/")
    result.treeText.contains("App.groovy")
    result.treeText.contains("README.md")
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
