package se.alipsa.lca.gui

import groovy.transform.CompileStatic

import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.SwingWorker
import javax.swing.Timer
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.List

/**
 * The main window: header strip, a multi-line input with a Submit button, the conversation
 * transcript, and the footer metrics strip. Submitting runs the turn on a background worker
 * so the UI stays responsive; a Swing {@link Timer} refreshes the footer periodically.
 */
@CompileStatic
class LcaMainFrame extends JFrame {

  private static final String SESSION_ID = "default"
  private static final String SUBMIT_LABEL = "Submit (Ctrl+Enter)"

  private final ConversationView conversationView
  private final FooterBar footerBar
  private final HeaderBar headerBar
  private final GuiTurnController turnController

  private final JTextArea inputArea = new JTextArea(6, 80)
  private final JButton submitButton = new JButton(SUBMIT_LABEL)
  private final Timer metricsTimer

  LcaMainFrame(HeaderBar headerBar, ConversationView conversationView, FooterBar footerBar,
               GuiTurnController turnController) {
    super("Local Coding Assistant")
    this.headerBar = headerBar
    this.conversationView = conversationView
    this.footerBar = footerBar
    this.turnController = turnController
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE)
    buildLayout()
    bindKeys()
    metricsTimer = new Timer(2000, { footerBar.refreshAsync() } as ActionListener)
    metricsTimer.start()
    setSize(1100, 800)
    setLocationRelativeTo(null)
  }

  private void buildLayout() {
    JPanel top = new JPanel(new BorderLayout())
    top.add(headerBar, BorderLayout.NORTH)
    JScrollPane inputScroll = new JScrollPane(inputArea)
    inputScroll.setBorder(BorderFactory.createTitledBorder("Your message"))
    inputArea.setLineWrap(true)
    inputArea.setWrapStyleWord(true)
    top.add(inputScroll, BorderLayout.CENTER)
    JPanel submitPanel = new JPanel()
    submitPanel.add(submitButton)
    top.add(submitPanel, BorderLayout.SOUTH)
    submitButton.addActionListener({ ActionEvent e -> submit() } as ActionListener)

    setLayout(new BorderLayout())
    add(top, BorderLayout.NORTH)
    add(conversationView, BorderLayout.CENTER)
    add(footerBar, BorderLayout.SOUTH)
  }

  private void bindKeys() {
    KeyStroke ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)
    inputArea.getInputMap(JComponent.WHEN_FOCUSED).put(ctrlEnter, "submit")
    inputArea.getActionMap().put("submit", new AbstractAction() {
      @Override
      void actionPerformed(ActionEvent e) {
        submit()
      }
    })
  }

  private void submit() {
    String text = inputArea.getText()
    if (text == null || text.trim().isEmpty()) {
      return
    }
    // Close any block a previous turn left open (e.g. late shell output after its worker finished).
    conversationView.closeAnyOpenBlock()
    conversationView.addUserMessage(text)
    inputArea.setText("")
    setBusy(true)
    new StreamingWorker(text).execute()
  }

  /**
   * Runs one turn off the EDT and streams its output back. The worker itself is the {@link TurnSink}:
   * its sink methods run on background thread(s) and {@code publish} a {@link SinkEvent}; {@code process}
   * applies each event to the transcript on the EDT.
   */
  private class StreamingWorker extends SwingWorker<TurnResult, SinkEvent> implements TurnSink {

    private final String input

    StreamingWorker(String input) {
      this.input = input
    }

    @Override
    protected TurnResult doInBackground() throws Exception {
      turnController.process(input, this)
    }

    @Override
    void note(String text) { publish(SinkEvent.note(text)) }

    @Override
    void beginBlock() { publish(SinkEvent.blockBegin()) }

    @Override
    void append(String line) { publish(SinkEvent.blockAppend(line)) }

    @Override
    void endBlock() { publish(SinkEvent.blockEnd()) }

    @Override
    void message(String markdown) { publish(SinkEvent.message(markdown)) }

    @Override
    protected void process(List<SinkEvent> chunks) {
      for (SinkEvent event : chunks) {
        SinkEventDispatcher.apply(conversationView, event)
      }
    }

    @Override
    protected void done() {
      TurnResult result
      try {
        result = get()
      } catch (Exception e) {
        conversationView.addNote("Error: ${e.message}".toString())
        result = new TurnResult(true, GuiAction.NONE)
      }
      if (result?.action == GuiAction.EXIT) {
        dispose()
        System.exit(0)
        return
      }
      if (result?.action == GuiAction.CLEAR) {
        conversationView.clear()
      }
      footerBar.refreshAsync()
      headerBar.refresh()
      setBusy(false)
      inputArea.requestFocusInWindow()
    }
  }

  private void setBusy(boolean busy) {
    inputArea.setEnabled(!busy)
    submitButton.setEnabled(!busy)
    submitButton.setText(busy ? "Thinking…" : SUBMIT_LABEL)
  }
}
