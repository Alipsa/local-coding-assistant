package se.alipsa.lca.shell

import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.springframework.shell.ResultHandlerService
import org.springframework.shell.Shell
import org.springframework.shell.command.CommandCatalog
import org.springframework.shell.command.CommandRegistration
import org.springframework.shell.context.InteractionMode
import org.springframework.shell.context.ShellContext
import org.springframework.shell.exit.ExitCodeMappings
import spock.lang.Specification

class ShellBatchCommandExecutorSpec extends Specification {

  def "execute invokes shell evaluate via reflection"() {
    given:
    ResultHandlerService resultHandlerService = Mock()
    CommandCatalog catalog = CommandCatalog.of()
    catalog.register(
      CommandRegistration.builder()
        .command("/hello")
        .withTarget()
        .function { "hi" }
        .and()
        .build()
    )
    ShellContext shellContext = Stub() {
      getInteractionMode() >> InteractionMode.INTERACTIVE
      hasPty() >> false
    }
    ExitCodeMappings exitCodeMappings = Stub()
    Terminal terminal = TerminalBuilder.builder().dumb(true).build()
    Shell shell = new Shell(resultHandlerService, catalog, terminal, shellContext, exitCodeMappings)
    ShellBatchCommandExecutor executor = new ShellBatchCommandExecutor(
      shell,
      resultHandlerService,
      new CommandInputNormaliser(new ShellSettings(true))
    )

    when:
    BatchCommandOutcome outcome = executor.execute("/hello")

    then:
    outcome.error == null
    outcome.exitRequested == false
    outcome.result != null
    1 * resultHandlerService.handle(_)

    cleanup:
    terminal.close()
  }
}
