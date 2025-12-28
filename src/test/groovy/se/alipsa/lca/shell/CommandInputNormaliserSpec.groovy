package se.alipsa.lca.shell

import spock.lang.Specification

class CommandInputNormaliserSpec extends Specification {

  def "normalise keeps slash commands unchanged"() {
    given:
    def normaliser = new CommandInputNormaliser(new ShellSettings(true))

    expect:
    normaliser.normalise("/status") == "/status"
  }

  def "normalise allows slash-prefixed built-in commands"() {
    given:
    def normaliser = new CommandInputNormaliser(new ShellSettings(true))

    expect:
    normaliser.normalise("/help") == "help"
  }

  def "normalise keeps /version as a custom command"() {
    given:
    def normaliser = new CommandInputNormaliser(new ShellSettings(true))

    expect:
    normaliser.normalise("/version") == "/version"
  }

  def "normalise keeps exit commands as slash commands"() {
    given:
    def normaliser = new CommandInputNormaliser(new ShellSettings(true))

    expect:
    normaliser.normalise("/exit") == "/exit"
    normaliser.normalise("/quit") == "/quit"
  }

  def "normalise maps plain text to chat command"() {
    given:
    def normaliser = new CommandInputNormaliser(new ShellSettings(true))

    expect:
    normaliser.normalise("Hello world") == "/chat --prompt \"Hello world\""
  }

  def "normaliseWords maps multiline input to paste command"() {
    given:
    def normaliser = new CommandInputNormaliser(new ShellSettings(true))
    String input = "Line one\nLine two"

    expect:
    normaliser.normaliseWords(input) == ["/paste", "--content", input, "--send", "true"]
  }

  def "normaliseWords honours auto-paste toggle"() {
    given:
    def normaliser = new CommandInputNormaliser(new ShellSettings(false))
    String input = "Line one\nLine two"

    expect:
    normaliser.normaliseWords(input) == null
  }

  def "normaliseWords ignores slash commands"() {
    given:
    def normaliser = new CommandInputNormaliser(new ShellSettings(true))

    expect:
    normaliser.normaliseWords("/chat --prompt \"hi\"") == null
  }

  def "normalise escapes quotes in prompts"() {
    given:
    def normaliser = new CommandInputNormaliser(new ShellSettings(true))

    expect:
    normaliser.normalise('She said "hello"') == "/chat --prompt \"She said \\\"hello\\\"\""
  }

  def "normalise leaves blank input untouched"() {
    given:
    def normaliser = new CommandInputNormaliser(new ShellSettings(true))

    expect:
    normaliser.normalise("   ") == "   "
  }
}
