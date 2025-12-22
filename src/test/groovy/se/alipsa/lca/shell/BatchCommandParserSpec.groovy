package se.alipsa.lca.shell

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class BatchCommandParserSpec extends Specification {

  @TempDir
  Path tempDir

  def "splitCommands separates semicolon-delimited commands"() {
    when:
    List<String> commands = BatchCommandParser.splitCommands("status; review --paths src; ;")

    then:
    commands == ["status", "review --paths src"]
  }

  def "splitCommands ignores semicolons inside quotes"() {
    when:
    List<String> commands = BatchCommandParser.splitCommands("run --command \"echo a; echo b\"; status")

    then:
    commands == ["run --command \"echo a; echo b\"", "status"]
  }

  def "splitCommands keeps escaped semicolons"() {
    when:
    List<String> commands = BatchCommandParser.splitCommands("run --command echo\\;test; status")

    then:
    commands == ["run --command echo\\;test", "status"]
  }

  def "splitCommands rejects unterminated quotes"() {
    when:
    BatchCommandParser.splitCommands("status; review --paths \"src")

    then:
    thrown(IllegalArgumentException)
  }

  def "readCommands loads non-empty lines"() {
    given:
    Path batchFile = tempDir.resolve("batch.txt")
    Files.writeString(batchFile, "status\n\nreview --paths src\nsearch --query \"a; b\"")

    when:
    List<String> commands = BatchCommandParser.readCommands(batchFile)

    then:
    commands == ["status", "review --paths src", "search --query \"a; b\""]
  }
}
