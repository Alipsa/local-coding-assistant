package se.alipsa.lca.shell

import spock.lang.Specification

class BatchModeOptionsSpec extends Specification {

  def "parses command mode flags"() {
    when:
    BatchModeOptions options = BatchModeOptions.parse(["-c", "/status; /review --paths src"] as String[])

    then:
    options.enabled
    options.commandText == "/status; /review --paths src"
    options.batchFile == null
    !options.batchJson
    !options.assumeYes
  }

  def "parses batch file flags"() {
    when:
    BatchModeOptions options = BatchModeOptions.parse(
      ["--batch-file", "batch.txt", "--batch-json", "--yes"] as String[]
    )

    then:
    options.enabled
    options.batchFile == "batch.txt"
    options.batchJson
    options.assumeYes
  }

  def "rejects duplicate command flags"() {
    when:
    BatchModeOptions.parse(["-c", "one", "--command", "two"] as String[])

    then:
    thrown(IllegalArgumentException)
  }

  def "rejects command and batch file combination"() {
    when:
    BatchModeOptions.parse(["-c", "/status", "--batch-file", "batch.txt"] as String[])

    then:
    thrown(IllegalArgumentException)
  }
}
