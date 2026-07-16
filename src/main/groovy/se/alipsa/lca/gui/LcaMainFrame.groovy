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
    metricsTimer = new Timer(2000, { footerBar.refresh() } as ActionListener)
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
    conversationView.addUserMessage(text)
    inputArea.setText("")
    setBusy(true)
    SwingWorker<TurnResult, Void> worker = new SwingWorker<TurnResult, Void>() {
      @Override
      protected TurnResult doInBackground() throws Exception {
        turnController.process(text)
      }

      @Override
      protected void done() {
        TurnResult result
        try {
          result = get()
        } catch (Exception e) {
          result = new TurnResult("Error: ${e.message}".toString(), null, true, GuiAction.NONE)
        }
        if (result?.action == GuiAction.EXIT) {
          dispose()
          System.exit(0)
          return
        }
        if (result?.action == GuiAction.CLEAR) {
          conversationView.clear()
          setBusy(false)
          inputArea.requestFocusInWindow()
          return
        }
        if (result?.note) {
          conversationView.addNote(result.note)
        }
        if (result?.output && !result.output.trim().isEmpty()) {
          conversationView.addAssistantMessage(result.output)
        }
        footerBar.refresh()
        headerBar.refresh()
        setBusy(false)
        inputArea.requestFocusInWindow()
      }
    }
    worker.execute()
  }

  private void setBusy(boolean busy) {
    inputArea.setEnabled(!busy)
    submitButton.setEnabled(!busy)
    submitButton.setText(busy ? "Thinking…" : SUBMIT_LABEL)
  }
}
