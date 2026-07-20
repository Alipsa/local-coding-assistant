package se.alipsa.lca.gui

import com.formdev.flatlaf.FlatDarkLaf
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import se.alipsa.lca.shell.BangCommandHandler
import se.alipsa.lca.shell.ContextCompactor
import se.alipsa.lca.shell.SessionState
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.GitTool
import se.alipsa.lca.tools.ModelRegistry
import se.alipsa.lca.tools.Workspace

import javax.swing.SwingUtilities
import java.util.function.Consumer

/**
 * Launches the Swing GUI once the Spring context is ready, mirroring {@code ReplRunner} but
 * for the GUI. Registered as a {@code @Bean} (see {@code RunnerConfiguration}) only when
 * {@code lca.gui.enabled=true}, which also structurally suppresses {@code ReplRunner}. Does not
 * call {@code System.exit}; the running web server and the AWT thread keep the JVM alive, and
 * the window's close handler exits.
 */
@CompileStatic
class GuiRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(GuiRunner)

  private final MarkdownRenderer markdownRenderer
  private final SystemMetrics systemMetrics
  private final ContextEstimator contextEstimator
  private final ModelRegistry modelRegistry
  private final ContextCompactor contextCompactor
  private final GuiTurnController turnController
  private final FileEditingTool fileEditingTool
  private final GitTool gitTool
  private final SessionState sessionState
  private final Workspace workspace
  private final BangCommandHandler bangCommandHandler

  GuiRunner(
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
    this.markdownRenderer = markdownRenderer
    this.systemMetrics = systemMetrics
    this.contextEstimator = contextEstimator
    this.modelRegistry = modelRegistry
    this.contextCompactor = contextCompactor
    this.turnController = turnController
    this.fileEditingTool = fileEditingTool
    this.gitTool = gitTool
    this.sessionState = sessionState
    this.workspace = workspace
    this.bangCommandHandler = bangCommandHandler
  }

  @Override
  void run(ApplicationArguments args) throws Exception {
    log.info("Starting LCA GUI...")
    SwingUtilities.invokeLater({
      try {
        FlatDarkLaf.setup()
      } catch (Exception e) {
        log.warn("Could not install FlatLaf look and feel: {}", e.message)
      }
      ConversationView conversation = new ConversationView(markdownRenderer)
      FooterBar footer = new FooterBar(systemMetrics, contextEstimator, modelRegistry, contextCompactor, "default")
      Runnable onBaseDirChanged = {
        footer.refreshAsync()
        conversation.addNote("Base dir changed to ${workspace.baseDir}".toString())
      } as Runnable
      Consumer<String> onCommandOutput = { String output ->
        conversation.addAssistantMessage("```\n${output ?: ''}\n```".toString())
      } as Consumer<String>
      HeaderBar header = new HeaderBar(fileEditingTool, gitTool, sessionState, workspace,
        bangCommandHandler, onBaseDirChanged, onCommandOutput)
      LcaMainFrame frame = new LcaMainFrame(header, conversation, footer, turnController)
      frame.setVisible(true)
    } as Runnable)
  }
}
