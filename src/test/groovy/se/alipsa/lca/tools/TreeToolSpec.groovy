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

  private void initRepo() {
    runGit("init")
    runGit("config", "user.email", "test@example.com")
    runGit("config", "user.name", "Test User")
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
