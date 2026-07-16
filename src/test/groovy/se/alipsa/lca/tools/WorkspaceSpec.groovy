package se.alipsa.lca.tools

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class WorkspaceSpec extends Specification {

  @TempDir
  Path tempDir

  Workspace workspace = new Workspace()

  def "default base dir is the current working directory"() {
    expect:
    workspace.baseDir == Paths.get(".").toAbsolutePath().normalize()
  }

  def "changeBaseDir to an existing directory succeeds"() {
    when:
    def result = workspace.changeBaseDir(tempDir.toString())

    then:
    result.success
    workspace.baseDir == tempDir.toAbsolutePath().normalize()
  }

  def "changeBaseDir resolves a relative path against the current base dir"() {
    given:
    Path child = Files.createDirectories(tempDir.resolve("child"))
    workspace.changeBaseDir(tempDir.toString())

    when:
    def result = workspace.changeBaseDir("child")

    then:
    result.success
    workspace.baseDir == child.toAbsolutePath().normalize()
  }

  def "changeBaseDir expands a leading tilde"() {
    when:
    def result = workspace.changeBaseDir("~")

    then:
    result.success
    workspace.baseDir == Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize()
  }

  def "changeBaseDir rejects a non-existent directory"() {
    given:
    Path missing = tempDir.resolve("does-not-exist")

    when:
    def result = workspace.changeBaseDir(missing.toString())

    then:
    !result.success
    result.message.toLowerCase().contains("no such")
    workspace.baseDir != missing.toAbsolutePath().normalize()
  }

  def "changeBaseDir rejects a file"() {
    given:
    Path file = tempDir.resolve("a-file.txt")
    Files.writeString(file, "x")

    when:
    def result = workspace.changeBaseDir(file.toString())

    then:
    !result.success
    result.message.toLowerCase().contains("not a directory")
  }

  def "changeBaseDir rejects blank input"() {
    expect:
    !workspace.changeBaseDir("   ").success
    !workspace.changeBaseDir(null).success
  }
}
