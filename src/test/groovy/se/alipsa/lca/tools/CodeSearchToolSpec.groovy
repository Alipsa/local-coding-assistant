package se.alipsa.lca.tools

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class CodeSearchToolSpec extends Specification {

  @TempDir
  Path tempDir

  def "search returns matches with context"() {
    given:
    Path file = tempDir.resolve("Sample.groovy")
    Files.writeString(file, "one\nmatch here\nthree\n")
    CodeSearchTool tool = new CodeSearchTool(tempDir)

    when:
    def hits = tool.search("match", List.of("Sample.groovy"), 1, 10)

    then:
    hits.size() == 1
    hits.first().path.endsWith("Sample.groovy")
    hits.first().snippet.contains("match here")
  }

  def "validatePath rejects traversal"() {
    when:
    new CodeSearchTool(tempDir).search("x", ["../outside.txt"], 0, 1)

    then:
    thrown(IllegalArgumentException)
  }

  def "query with unsafe characters is rejected"() {
    when:
    new CodeSearchTool(tempDir).search("foo | bar", List.of(), 0, 1)

    then:
    thrown(IllegalArgumentException)
  }

  def "returns empty list when no matches"() {
    given:
    Path file = tempDir.resolve("empty.txt")
    Files.writeString(file, "nothing here")
    CodeSearchTool tool = new CodeSearchTool(tempDir)

    expect:
    tool.search("nomatch", List.of("empty.txt"), 0, 5).isEmpty()
  }

  def "ignores git directory content by default"() {
    given:
    Path gitDir = tempDir.resolve(".git")
    Files.createDirectories(gitDir)
    Files.writeString(gitDir.resolve("config"), "match")
    CodeSearchTool tool = new CodeSearchTool(tempDir)

    expect:
    tool.search("match", List.of(), 1, 5).isEmpty()
  }

  def "respects aiexclude patterns"() {
    given:
    Files.writeString(tempDir.resolve(".aiexclude"), "secret.txt\n")
    Files.writeString(tempDir.resolve("secret.txt"), "match")
    CodeSearchTool tool = new CodeSearchTool(tempDir)

    expect:
    tool.search("match", List.of(), 0, 5).isEmpty()
  }
}
