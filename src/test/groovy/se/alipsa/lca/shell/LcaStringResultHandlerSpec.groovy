package se.alipsa.lca.shell

import org.jline.terminal.Terminal
import org.springframework.shell.context.InteractionMode
import org.springframework.shell.context.ShellContext
import spock.lang.Specification

class LcaStringResultHandlerSpec extends Specification {

  def "handler colours output in interactive mode"() {
    given:
    StringWriter buffer = new StringWriter()
    PrintWriter printWriter = new PrintWriter(buffer)
    Terminal terminal = Stub(Terminal) {
      writer() >> printWriter
    }
    ShellContext context = Stub(ShellContext) {
      getInteractionMode() >> InteractionMode.INTERACTIVE
      hasPty() >> true
    }
    LcaStringResultHandler handler = new LcaStringResultHandler(terminal, context, new ShellOutputStyler())

    when:
    handler.handleResult("Hello")
    printWriter.flush()

    then:
    buffer.toString() == "${ShellOutputStyler.LIGHT_YELLOW}Hello${ShellOutputStyler.RESET}${System.lineSeparator()}"
  }

  def "handler keeps output plain in non-interactive mode"() {
    given:
    StringWriter buffer = new StringWriter()
    PrintWriter printWriter = new PrintWriter(buffer)
    Terminal terminal = Stub(Terminal) {
      writer() >> printWriter
    }
    ShellContext context = Stub(ShellContext) {
      getInteractionMode() >> InteractionMode.NONINTERACTIVE
      hasPty() >> true
    }
    LcaStringResultHandler handler = new LcaStringResultHandler(terminal, context, new ShellOutputStyler())

    when:
    handler.handleResult("Hello")
    printWriter.flush()

    then:
    buffer.toString() == "Hello${System.lineSeparator()}"
  }
}
