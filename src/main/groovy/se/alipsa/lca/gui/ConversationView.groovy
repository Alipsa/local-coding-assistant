package se.alipsa.lca.gui

import groovy.transform.CompileStatic

import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.text.html.HTMLEditorKit
import java.awt.BorderLayout
import java.awt.Color

/**
 * Scrollable conversation transcript. Renders the whole conversation into a single
 * {@code JEditorPane} (HTML) so text wraps to width without the per-component sizing
 * problems that {@code BoxLayout} + {@code JEditorPane} would introduce. Assistant replies
 * are Markdown-rendered; user input and notes are shown literally.
 */
@CompileStatic
class ConversationView extends JPanel {

  private static final String EXTRA_CSS = '''
    .role { color:#7f7f7f; font-size:10px; }
    .msg-user { background-color:#232a23; padding:4px 8px; }
    .msg-assistant { background-color:#26262b; padding:4px 8px; }
    .note { color:#999999; padding:2px 8px; font-style:italic; }
  '''

  private final MarkdownRenderer markdownRenderer
  private final JEditorPane pane = new JEditorPane()
  private final JScrollPane scrollPane
  private final StringBuilder body = new StringBuilder()

  ConversationView(MarkdownRenderer markdownRenderer) {
    this.markdownRenderer = markdownRenderer
    setLayout(new BorderLayout())
    pane.setEditorKit(new HTMLEditorKit())
    pane.setContentType("text/html")
    pane.setEditable(false)
    pane.setBackground(new Color(0x1e1e1e))
    scrollPane = new JScrollPane(pane)
    scrollPane.getVerticalScrollBar().setUnitIncrement(16)
    add(scrollPane, BorderLayout.CENTER)
    render()
  }

  void addUserMessage(String text) {
    body.append(row("msg-user", "You", "<pre>${MarkdownRenderer.escapeHtml(text)}</pre>"))
    render()
  }

  void addAssistantMessage(String markdown) {
    body.append(row("msg-assistant", "lca", markdownRenderer.renderBody(markdown)))
    render()
  }

  /** Remove all messages from the transcript. */
  void clear() {
    body.setLength(0)
    render()
  }

  void addNote(String text) {
    if (text == null || text.trim().isEmpty()) {
      return
    }
    body.append("<div class='note'>${MarkdownRenderer.escapeHtml(text)}</div>")
    render()
  }

  private static String row(String cssClass, String role, String innerHtml) {
    "<div class='${cssClass}'><div class='role'>${role}</div>${innerHtml}</div>".toString()
  }

  private void render() {
    String doc = "<html><head><style>${markdownRenderer.css()}${EXTRA_CSS}</style></head><body>${body}</body></html>"
    pane.setText(doc)
    pane.setCaretPosition(pane.getDocument().getLength())
  }
}
