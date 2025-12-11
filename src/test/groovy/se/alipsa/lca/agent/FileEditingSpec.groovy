package se.alipsa.lca.agent

import se.alipsa.lca.tools.FileEditingTool
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class FileEditingSpec extends Specification {

  FileEditingTool fileEditingAgent
  @TempDir
  Path tempDir
  Path tempFile

  def setup() {
    fileEditingAgent = new FileEditingTool(tempDir)
    tempFile = tempDir.resolve("test-file.txt")
  }

  def "writes a file"() {
    when:
    def result = fileEditingAgent.writeFile("test-file.txt", "Hello, world!")

    then:
    result == "Successfully wrote to test-file.txt"
    Files.readString(tempFile) == "Hello, world!"
  }

  def "replaces content"() {
    given:
    Files.writeString(tempFile, "Hello, world!")

    when:
    def result = fileEditingAgent.replace("test-file.txt", "world", "Groovy")

    then:
    result == "Successfully replaced content in test-file.txt"
    Files.readString(tempFile) == "Hello, Groovy!"
  }

  def "deletes a file"() {
    given:
    Files.writeString(tempFile, "some content")

    when:
    def result = fileEditingAgent.deleteFile("test-file.txt")

    then:
    result == "Successfully deleted test-file.txt"
    !Files.exists(tempFile)
  }

  def "rejects paths outside project root"() {
    when:
    fileEditingAgent.writeFile("../test-file.txt", "some content")

    then:
    def ex = thrown(IllegalArgumentException)
    ex.message == "File path must be within the project directory"
  }

  def "replace rejects missing file"() {
    when:
    fileEditingAgent.replace("missing.txt", "a", "b")

    then:
    def ex = thrown(IllegalArgumentException)
    ex.message == "File missing.txt does not exist"
  }

  def "applies unified patch and creates backup"() {
    given:
    Files.writeString(tempFile, "one\n-two\n")
    String patch = """\
--- a/test-file.txt
+++ b/test-file.txt
@@ -1,2 +1,3 @@
-one
+one updated
 -two
+three
"""

    when:
    FileEditingTool.PatchResult result = fileEditingAgent.applyPatch(patch, false)

    then:
    result.applied
    !result.hasConflicts
    Files.readString(tempFile).contains("one updated")
    def fileResult = result.fileResults.first()
    fileResult.backupPath
    Files.exists(tempDir.resolve(fileResult.backupPath))
  }

  def "detects conflicts and keeps file unchanged"() {
    given:
    Files.writeString(tempFile, "alpha\nbeta\n")
    String patch = """\
--- a/test-file.txt
+++ b/test-file.txt
@@ -1,2 +1,2 @@
-gamma
+delta
 beta
"""

    when:
    FileEditingTool.PatchResult result = fileEditingAgent.applyPatch(patch, false)

    then:
    result.hasConflicts
    !result.applied
    Files.readString(tempFile) == "alpha\nbeta\n"
  }

  def "normalizes dot segments in project root"() {
    given:
    FileEditingTool dotTool = new FileEditingTool(tempDir.resolve("."))
    Path dotFile = tempDir.resolve("dot-file.txt")
    Files.writeString(dotFile, "one\n")
    String patch = """\
--- a/dot-file.txt
+++ b/dot-file.txt
@@ -1,1 +1,1 @@
-one
+two
"""

    when:
    FileEditingTool.PatchResult result = dotTool.applyPatch(patch, false)

    then:
    result.applied
    Files.readString(dotFile) == "two\n"
  }

  def "supports dry-run without modifying files"() {
    given:
    Files.writeString(tempFile, "start\n")
    String patch = """\
--- a/test-file.txt
+++ b/test-file.txt
@@ -1,1 +1,1 @@
-start
+finish
"""

    when:
    FileEditingTool.PatchResult result = fileEditingAgent.applyPatch(patch, true)

    then:
    result.dryRun
    !result.applied
    Files.readString(tempFile) == "start\n"
    result.fileResults.first().preview.contains("finish")
  }

  def "reverts to latest backup"() {
    given:
    Files.writeString(tempFile, "first\n")
    String patch = """\
--- a/test-file.txt
+++ b/test-file.txt
@@ -1,1 +1,1 @@
-first
+second
"""
    FileEditingTool.PatchResult applied = fileEditingAgent.applyPatch(patch, false)
    Files.writeString(tempFile, "manual\n")

    when:
    FileEditingTool.EditResult reverted = fileEditingAgent.revertLatestBackup("test-file.txt", false)

    then:
    reverted.applied
    Files.readString(tempFile) == "first\n"
    applied.fileResults.first().backupPath == reverted.backupPath
  }

  def "replaces a specific line range"() {
    given:
    Files.writeString(tempFile, "alpha\nbeta\ngamma\n")

    when:
    FileEditingTool.EditResult result = fileEditingAgent.replaceRange("test-file.txt", 2, 2, "BETA", false)

    then:
    result.applied
    Files.readString(tempFile) == "alpha\nBETA\ngamma\n"
    Files.exists(tempDir.resolve(result.backupPath))
  }

  def "provides symbol context"() {
    given:
    Files.writeString(tempFile, "one\nspecialSymbol here\nthree\n")

    when:
    def ctx = fileEditingAgent.contextBySymbol("test-file.txt", "specialSymbol", 1)

    then:
    ctx.filePath == "test-file.txt"
    ctx.snippet.contains("specialSymbol here")
    ctx.startLine == 1
    ctx.endLine == 3
  }
}
