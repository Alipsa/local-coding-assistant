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
}
