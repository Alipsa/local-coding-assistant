package se.alipsa.lca.shell

import se.alipsa.lca.repl.CommandExecutor
import spock.lang.Specification

class ShellBatchCommandExecutorSpec extends Specification {

  def "execute calls CommandExecutor with normalized command"() {
    given:
    CommandExecutor commandExecutor = Mock()
    ShellBatchCommandExecutor executor = new ShellBatchCommandExecutor(
      commandExecutor,
      new CommandInputNormaliser(new ShellSettings(true))
    )

    when:
    BatchCommandOutcome outcome = executor.execute("/hello")

    then:
    1 * commandExecutor.execute("/hello") >> "hi"
    outcome.error == null
    outcome.exitRequested == false
    outcome.result == "hi"
  }

  def "execute normalizes natural language to intent commands"() {
    given:
    CommandExecutor commandExecutor = Mock()
    ShellBatchCommandExecutor executor = new ShellBatchCommandExecutor(
      commandExecutor,
      new CommandInputNormaliser(new ShellSettings(true))
    )

    when:
    BatchCommandOutcome outcome = executor.execute("help")

    then:
    1 * commandExecutor.execute('/chat --prompt "help"') >> "Help text"
    outcome.error == null
    outcome.result == "Help text"
  }

  def "execute handles commands that are already normalized"() {
    given:
    CommandExecutor commandExecutor = Mock()
    ShellBatchCommandExecutor executor = new ShellBatchCommandExecutor(
      commandExecutor,
      new CommandInputNormaliser(new ShellSettings(true))
    )

    when:
    BatchCommandOutcome outcome = executor.execute("/health")

    then:
    1 * commandExecutor.execute("/health") >> "OK"
    outcome.error == null
    outcome.result == "OK"
  }

  def "execute handles exit command without calling CommandExecutor"() {
    given:
    CommandExecutor commandExecutor = Mock()
    ShellBatchCommandExecutor executor = new ShellBatchCommandExecutor(
      commandExecutor,
      new CommandInputNormaliser(new ShellSettings(true))
    )

    when:
    BatchCommandOutcome outcome = executor.execute("exit")

    then:
    0 * commandExecutor.execute(_)
    outcome.exitRequested == true
    outcome.exitCode == 0
    outcome.error == null
  }

  def "execute handles quit command without calling CommandExecutor"() {
    given:
    CommandExecutor commandExecutor = Mock()
    ShellBatchCommandExecutor executor = new ShellBatchCommandExecutor(
      commandExecutor,
      new CommandInputNormaliser(new ShellSettings(true))
    )

    when:
    BatchCommandOutcome outcome = executor.execute("/quit")

    then:
    0 * commandExecutor.execute(_)
    outcome.exitRequested == true
    outcome.exitCode == 0
  }

  def "execute captures exceptions from CommandExecutor"() {
    given:
    CommandExecutor commandExecutor = Mock()
    ShellBatchCommandExecutor executor = new ShellBatchCommandExecutor(
      commandExecutor,
      new CommandInputNormaliser(new ShellSettings(true))
    )
    RuntimeException testException = new RuntimeException("Test error")

    when:
    BatchCommandOutcome outcome = executor.execute("/error")

    then:
    1 * commandExecutor.execute("/error") >> { throw testException }
    outcome.error == testException
    outcome.result == null
    outcome.exitRequested == false
  }

  def "execute rejects empty commands"() {
    given:
    CommandExecutor commandExecutor = Mock()
    ShellBatchCommandExecutor executor = new ShellBatchCommandExecutor(
      commandExecutor,
      new CommandInputNormaliser(new ShellSettings(true))
    )

    when:
    BatchCommandOutcome outcome = executor.execute("")

    then:
    0 * commandExecutor.execute(_)
    outcome.error instanceof IllegalArgumentException
    outcome.error.message == "Command must not be empty."
  }
}
