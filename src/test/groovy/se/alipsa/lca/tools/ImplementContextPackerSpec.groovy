package se.alipsa.lca.tools

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ImplementContextPackerSpec extends Specification {

  @TempDir
  Path tempDir

  def "extractFileReferences finds single file reference"() {
    given:
    def packer = packerWith(tempDir)

    when:
    def refs = packer.extractFileReferences("read projectplan.md and implement Phase 4")

    then:
    refs == ["projectplan.md"]
  }

  def "extractFileReferences finds paths with directories"() {
    given:
    def packer = packerWith(tempDir)

    when:
    def refs = packer.extractFileReferences("modify src/main/groovy/Foo.groovy")

    then:
    refs == ["src/main/groovy/Foo.groovy"]
  }

  def "extractFileReferences finds multiple references and deduplicates"() {
    given:
    def packer = packerWith(tempDir)

    when:
    def refs = packer.extractFileReferences(
      "read projectplan.md then update build.gradle and check projectplan.md again"
    )

    then:
    refs.contains("projectplan.md")
    refs.contains("build.gradle")
    refs.count { it == "projectplan.md" } == 1
  }

  def "extractFileReferences returns empty for null or blank"() {
    given:
    def packer = packerWith(tempDir)

    expect:
    packer.extractFileReferences(null) == []
    packer.extractFileReferences("") == []
    packer.extractFileReferences("   ") == []
  }

  def "extractFileReferences returns empty when no file patterns found"() {
    given:
    def packer = packerWith(tempDir)

    when:
    def refs = packer.extractFileReferences("implement the login feature")

    then:
    refs.isEmpty()
  }

  def "buildContext includes tree and file contents"() {
    given:
    Files.writeString(tempDir.resolve("plan.md"), "# Plan\nPhase 1: Setup")
    def gitTool = Stub(GitTool) {
      isGitRepo() >> true
      listFiles() >> new GitTool.GitResult(true, true, 0, "plan.md\nsrc/\n", "")
    }
    def treeTool = new TreeTool(tempDir, gitTool)
    def fileEditingTool = new FileEditingTool(tempDir)
    def packer = new ImplementContextPacker(treeTool, fileEditingTool, 12000)

    when:
    def ctx = packer.buildContext("read plan.md and implement Phase 1")

    then:
    ctx.contextBlock.contains("PROJECT STRUCTURE")
    ctx.contextBlock.contains("plan.md")
    ctx.contextBlock.contains("# Plan")
    ctx.contextBlock.contains("Phase 1: Setup")
    ctx.filesRead == ["plan.md"]
    !ctx.truncated
  }

  def "buildContext handles missing file gracefully"() {
    given:
    def gitTool = Stub(GitTool) {
      isGitRepo() >> true
      listFiles() >> new GitTool.GitResult(true, true, 0, "src/\n", "")
    }
    def treeTool = new TreeTool(tempDir, gitTool)
    def fileEditingTool = new FileEditingTool(tempDir)
    def packer = new ImplementContextPacker(treeTool, fileEditingTool, 12000)

    when:
    def ctx = packer.buildContext("read missing.txt and implement it")

    then:
    ctx.contextBlock.contains("not found")
    ctx.filesRead.isEmpty()
  }

  def "buildContext truncates when content exceeds budget"() {
    given:
    String largeContent = "x" * 15000
    Files.writeString(tempDir.resolve("big.txt"), largeContent)
    def gitTool = Stub(GitTool) {
      isGitRepo() >> true
      listFiles() >> new GitTool.GitResult(true, true, 0, "big.txt\n", "")
    }
    def treeTool = new TreeTool(tempDir, gitTool)
    def fileEditingTool = new FileEditingTool(tempDir)
    def packer = new ImplementContextPacker(treeTool, fileEditingTool, 5000)

    when:
    def ctx = packer.buildContext("read big.txt and do something")

    then:
    ctx.truncated
    ctx.contextBlock.length() <= 5200
  }

  def "buildContext returns empty block when no file refs and tree fails"() {
    given:
    def gitTool = Stub(GitTool) {
      isGitRepo() >> false
    }
    def treeTool = new TreeTool(tempDir, gitTool)
    def fileEditingTool = new FileEditingTool(tempDir)
    def packer = new ImplementContextPacker(treeTool, fileEditingTool, 12000)

    when:
    def ctx = packer.buildContext("implement the login feature")

    then:
    ctx.contextBlock.isEmpty() || ctx.contextBlock.trim().isEmpty()
    ctx.filesRead.isEmpty()
  }

  private ImplementContextPacker packerWith(Path root) {
    def gitTool = Stub(GitTool) {
      isGitRepo() >> false
    }
    def treeTool = new TreeTool(root, gitTool)
    def fileEditingTool = new FileEditingTool(root)
    new ImplementContextPacker(treeTool, fileEditingTool, 12000)
  }
}
