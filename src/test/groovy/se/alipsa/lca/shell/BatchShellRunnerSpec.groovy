package se.alipsa.lca.shell

import org.springframework.shell.CommandNotFound
import org.springframework.shell.context.InteractionMode
import org.springframework.shell.context.ShellContext
import spock.lang.Specification

class BatchShellRunnerSpec extends Specification {

  def "run executes commands sequentially and stops on failure"() {
    given:
    BatchCommandExecutor executor = Mock()
    ShellCommands shellCommands = Mock()
    ShellContext shellContext = Mock()
    TestExitHandler exitHandler = new TestExitHandler()
    BatchShellRunner runner = new BatchShellRunner(executor, shellCommands, shellContext, exitHandler)

    when:
    boolean handled = runner.run(["-c", "status; unknown"] as String[])

    then:
    handled
    1 * shellContext.setInteractionMode(InteractionMode.NONINTERACTIVE)
    1 * shellCommands.configureBatchMode(true, false)
    1 * executor.execute("status") >> new BatchCommandOutcome("ok", null, false, null)
    1 * executor.execute("unknown") >> new BatchCommandOutcome(
      new CommandNotFound(["unknown"]),
      null,
      false,
      null
    )
    exitHandler.exitCode == 1
  }

  def "run returns false when no batch args are provided"() {
    given:
    BatchShellRunner runner = new BatchShellRunner(
      Stub(BatchCommandExecutor),
      Stub(ShellCommands),
      Stub(ShellContext),
      new TestExitHandler()
    )

    expect:
    !runner.run([] as String[])
  }

  def "run ignores batch flags without command input"() {
    given:
    TestExitHandler exitHandler = new TestExitHandler()
    BatchShellRunner runner = new BatchShellRunner(
      Stub(BatchCommandExecutor),
      Stub(ShellCommands),
      Stub(ShellContext),
      exitHandler
    )

    when:
    boolean handled = runner.run(["--batch-json"] as String[])

    then:
    !handled
    exitHandler.exitCode == null
  }

  private static class TestExitHandler implements BatchExitHandler {
    Integer exitCode

    @Override
    void exit(int code) {
      this.exitCode = code
    }
  }
}
