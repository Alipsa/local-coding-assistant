package se.alipsa.lca.gui

import com.formdev.flatlaf.FlatDarkLaf
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import se.alipsa.lca.shell.BangCommandHandler
import se.alipsa.lca.shell.SessionState
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.GitTool
import se.alipsa.lca.tools.Workspace

import javax.swing.SwingUtilities
import java.util.function.Consumer

/**
 * Launches the Swing GUI once the Spring context is ready, mirroring {@code ReplRunner} but
 * for the GUI. Only active when {@code lca.gui.enabled=true} (the {@code lcaGui} launcher sets
 * this and disables the REPL). Does not call {@code System.exit}; the running web server and
 * the AWT thread keep the JVM alive, and the window's close handler exits.
 */
@Component
@CompileStatic
@ConditionalOnProperty(name = "lca.gui.enabled", havingValue = "true")
class GuiRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(GuiRunner)

  private final MarkdownRenderer markdownRenderer
  private final SystemMetrics systemMetrics
  private final ContextEstimator contextEstimator
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
      FooterBar footer = new FooterBar(systemMetrics, contextEstimator, "default")
      Runnable onBaseDirChanged = {
        footer.refresh()
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
