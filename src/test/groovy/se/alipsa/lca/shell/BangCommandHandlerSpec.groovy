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

  def "strip returns the command after the bang"() {
    expect:
    handler.strip("!  git status ") == "git status"
  }

  def "the streaming overload passes the line consumer to shellCommandCaptured"() {
    given:
    def consumer = { String l -> } as java.util.function.Consumer

    when:
    handler.handle("! ls", "s1", true, consumer)

    then:
    1 * shellCommands.shellCommandCaptured("ls", "s1", consumer) >> "[exit 0]"
  }

  def "the streaming overload reports usage for a bare bang without calling the shell"() {
    when:
    String out = handler.handle("!   ", "s1", true, { String l -> } as java.util.function.Consumer)

    then:
    0 * shellCommands.shellCommandCaptured(_, _, _)
    out == "Usage: ! <shell command>"
  }
}
