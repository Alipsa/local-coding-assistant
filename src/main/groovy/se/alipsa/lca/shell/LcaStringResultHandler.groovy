package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.jline.terminal.Terminal
import org.springframework.shell.context.ShellContext
import org.springframework.shell.result.TerminalAwareResultHandler

@CompileStatic
class LcaStringResultHandler extends TerminalAwareResultHandler<String> {

  private final ShellContext shellContext
  private final ShellOutputStyler styler

  LcaStringResultHandler(Terminal terminal, ShellContext shellContext, ShellOutputStyler styler) {
    super(terminal)
    this.shellContext = shellContext
    this.styler = styler != null ? styler : new ShellOutputStyler()
  }

  @Override
  protected void doHandleResult(String result) {
    String output = String.valueOf(result)
    String styled = styler.shouldColour(shellContext) ? styler.colourise(output) : output
    terminal.writer().println(styled)
  }
}
