package se.alipsa.lca

import groovy.transform.CompileStatic
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import se.alipsa.lca.gui.ContextEstimator
import se.alipsa.lca.gui.GuiRunner
import se.alipsa.lca.gui.GuiTurnController
import se.alipsa.lca.gui.MarkdownRenderer
import se.alipsa.lca.gui.SystemMetrics
import se.alipsa.lca.repl.JLineRepl
import se.alipsa.lca.repl.ReplRunner
import se.alipsa.lca.shell.BangCommandHandler
import se.alipsa.lca.shell.ContextCompactor
import se.alipsa.lca.shell.SessionState
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.GitTool
import se.alipsa.lca.tools.ModelRegistry
import se.alipsa.lca.tools.Workspace

/**
 * Registers exactly one of {@link GuiRunner} / {@link ReplRunner}, structurally rather than by
 * convention. Declared as {@code @Bean} factory methods in one {@code @Configuration} class —
 * not as {@code @Component}-scanned classes — specifically so {@code guiRunner}'s presence is
 * decided before {@code replRunner}'s {@link ConditionalOnMissingBean} check runs: Spring only
 * guarantees that ordering for {@code @Bean} methods within the same configuration class,
 * evaluated in declaration order, not for independently component-scanned classes.
 *
 * <p>{@code guiRunner} being present (however {@code lca.gui.enabled=true} was resolved — a
 * {@code -D} flag, {@code LCA_GUI_ENABLED}, {@code application.properties}, a profile) always
 * suppresses {@code replRunner}, regardless of {@code lca.repl.enabled}: {@code ReplRunner.run()}
 * blocks on stdin and calls {@code System.exit(0)} on EOF, which would silently kill a running
 * GUI session if both were ever active at once.
 */
@Configuration
@CompileStatic
class RunnerConfiguration {

  @Bean
  @ConditionalOnProperty(name = "lca.gui.enabled", havingValue = "true")
  GuiRunner guiRunner(
    MarkdownRenderer markdownRenderer,
    SystemMetrics systemMetrics,
    ContextEstimator contextEstimator,
    ModelRegistry modelRegistry,
    ContextCompactor contextCompactor,
    GuiTurnController turnController,
    FileEditingTool fileEditingTool,
    GitTool gitTool,
    SessionState sessionState,
    Workspace workspace,
    BangCommandHandler bangCommandHandler
  ) {
    new GuiRunner(markdownRenderer, systemMetrics, contextEstimator, modelRegistry, contextCompactor,
      turnController, fileEditingTool, gitTool, sessionState, workspace, bangCommandHandler)
  }

  @Bean
  @ConditionalOnProperty(name = "lca.repl.enabled", havingValue = "true", matchIfMissing = true)
  @ConditionalOnMissingBean(GuiRunner)
  ReplRunner replRunner(JLineRepl repl) {
    new ReplRunner(repl)
  }
}
