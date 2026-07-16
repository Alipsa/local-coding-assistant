package se.alipsa.lca.shell

import spock.lang.Specification

class BangCommandHandlerSpec extends Specification {

  ShellCommands shellCommands = Mock()
  BangCommandHandler handler = new BangCommandHandler(shellCommands)

  def "isBang detects the ! prefix"() {
    expect:
    handler.isBang("! ls")
    handler.isBang("  !ls")
    !handler.isBang("ls")
    !handler.isBang(null)
    !handler.isBang("")
  }

  def "handle strips ! and runs via shellCommand when streaming"() {
    when:
    String out = handler.handle("! git status", "default", false)

    then:
    1 * shellCommands.shellCommand("git status", "default") >> "summary"
    0 * shellCommands.shellCommandCaptured(_, _)
    out == "summary"
  }

  def "handle strips ! and captures output for the GUI"() {
    when:
    String out = handler.handle("!  mvn -v ", "default", true)

    then:
    1 * shellCommands.shellCommandCaptured("mvn -v", "default") >> "captured"
    0 * shellCommands.shellCommand(_, _)
    out == "captured"
  }

  def "a null session defaults to 'default'"() {
    when:
    handler.handle("! ls", null, false)

    then:
    1 * shellCommands.shellCommand("ls", "default") >> "ok"
  }

  def "blank command returns usage"() {
    expect:
    handler.handle("!", "default", true) == "Usage: ! <shell command>"
    handler.handle("!   ", "default", false) == "Usage: ! <shell command>"
  }

  def "non-bang input returns empty and runs nothing"() {
    when:
    String out = handler.handle("ls", "default", false)

    then:
    0 * shellCommands.shellCommand(_, _)
    0 * shellCommands.shellCommandCaptured(_, _)
    out == ""
  }
}
