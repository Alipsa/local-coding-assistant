package se.alipsa.lca

import org.springframework.boot.test.context.runner.ApplicationContextRunner
import se.alipsa.lca.gui.ContextEstimator
import se.alipsa.lca.gui.GuiRunner
import se.alipsa.lca.gui.GuiTurnController
import se.alipsa.lca.gui.MarkdownRenderer
import se.alipsa.lca.gui.SystemMetrics
import se.alipsa.lca.repl.JLineRepl
import se.alipsa.lca.repl.ReplRunner
import se.alipsa.lca.shell.BangCommandHandler
import se.alipsa.lca.shell.SessionState
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.GitTool
import se.alipsa.lca.tools.Workspace
import spock.lang.Specification
import spock.lang.Unroll

class RunnerConfigurationSpec extends Specification {

  private ApplicationContextRunner contextRunner() {
    new ApplicationContextRunner()
      .withUserConfiguration(RunnerConfiguration)
      .withBean(MarkdownRenderer, { Mock(MarkdownRenderer) })
      .withBean(SystemMetrics, { Mock(SystemMetrics) })
      .withBean(ContextEstimator, { Mock(ContextEstimator) })
      .withBean(GuiTurnController, { Mock(GuiTurnController) })
      .withBean(FileEditingTool, { Mock(FileEditingTool) })
      .withBean(GitTool, { Mock(GitTool) })
      .withBean(SessionState, { Mock(SessionState) })
      .withBean(Workspace, { Mock(Workspace) })
      .withBean(BangCommandHandler, { Mock(BangCommandHandler) })
      .withBean(JLineRepl, { Mock(JLineRepl) })
  }

  @Unroll
  def "with lca.gui.enabled=#guiEnabled and lca.repl.enabled=#replEnabled, only #expected runner(s) register"() {
    given:
    List<String> props = []
    if (guiEnabled != null) {
      props << "lca.gui.enabled=${guiEnabled}".toString()
    }
    if (replEnabled != null) {
      props << "lca.repl.enabled=${replEnabled}".toString()
    }

    expect:
    contextRunner().withPropertyValues(props as String[]).run { context ->
      assert context.containsBean("guiRunner") == guiPresent
      assert context.containsBean("replRunner") == replPresent
    }

    where:
    guiEnabled | replEnabled || guiPresent | replPresent
    "true"     | null        || true       | false
    // The actual bug scenario: both flags true (e.g. LCA_GUI_ENABLED=true with no matching
    // lca.repl.enabled override) — GuiRunner must still win alone.
    "true"     | "true"      || true       | false
    null       | null        || false      | true
    "false"    | null        || false      | true
    // The existing, intentional REST-API-only mode: neither interactive runner registers.
    "false"    | "false"     || false      | false

    expected = guiPresent && replPresent ? "both (bug!)" : guiPresent ? "GuiRunner" : replPresent ? "ReplRunner" : "no"
  }
}
