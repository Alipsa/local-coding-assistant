package se.alipsa.lca.gui

import groovy.transform.CompileStatic
import groovy.transform.PackageScope

import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Timer
import javax.swing.text.html.HTMLEditorKit
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.ActionEvent
import java.awt.event.ActionListener

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
  private final StringBuilder liveBlock = new StringBuilder()
  private boolean blockOpen = false
  private final Timer coalesceTimer

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
    coalesceTimer = new Timer(60, { ActionEvent e -> render() } as ActionListener)
    coalesceTimer.setRepeats(false)
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

  /** Open a live, growing block rendered as a fenced code region. */
  void beginBlock() {
    liveBlock.setLength(0)
    blockOpen = true
    renderCoalesced()
  }

  /** Append one line to the open live block (opening one if needed). */
  void appendBlock(String line) {
    if (!blockOpen) {
      beginBlock()
    }
    // AnsiHtml runs after escapeHtml, on the already-escaped text — so streamed shell/command
    // output that colorizes itself (e.g. colored git/grep output) shows color instead of raw
    // escape-code garbage, without the escaping pass mangling the tags it inserts.
    liveBlock.append(AnsiHtml.translate(MarkdownRenderer.escapeHtml(line ?: ""))).append("\n")
    renderCoalesced()
  }

  /** Commit the live block to the transcript and flush immediately. */
  void endBlock() {
    if (blockOpen && liveBlock.length() > 0) {
      body.append("<div class='msg-assistant'><div class='role'>lca</div><pre>")
        .append(liveBlock).append("</pre></div>")
    }
    blockOpen = false
    liveBlock.setLength(0)
    coalesceTimer.stop()
    render()
  }

  /** Defensively commit and close any live block left open by a previous turn (idempotent). */
  void closeAnyOpenBlock() {
    if (blockOpen) {
      endBlock()
    }
  }

  /** Remove all messages from the transcript. */
  void clear() {
    body.setLength(0)
    liveBlock.setLength(0)
    blockOpen = false
    render()
  }

  void addNote(String text) {
    if (text == null || text.trim().isEmpty()) {
      return
    }
    body.append("<div class='note'>${AnsiHtml.translate(MarkdownRenderer.escapeHtml(text))}</div>")
    render()
  }

  private static String row(String cssClass, String role, String innerHtml) {
    "<div class='${cssClass}'><div class='role'>${role}</div>${innerHtml}</div>".toString()
  }

  /** Coalesce bursts of appends into at most one render per timer window. */
  private void renderCoalesced() {
    if (!coalesceTimer.isRunning()) {
      coalesceTimer.start()
    }
  }

  /** The full HTML document for the current state (committed body + any open live block). */
  @PackageScope
  String snapshotHtml() {
    StringBuilder live = new StringBuilder()
    if (blockOpen && liveBlock.length() > 0) {
      live.append("<div class='msg-assistant'><div class='role'>lca</div><pre>")
        .append(liveBlock).append("</pre></div>")
    }
    StringBuilder doc = new StringBuilder("<html><head><style>")
    doc.append(markdownRenderer.css()).append(EXTRA_CSS)
    doc.append("</style></head><body>").append(body).append(live).append("</body></html>")
    doc.toString()
  }

  private void render() {
    String doc = snapshotHtml()
    pane.setText(doc)
    pane.setCaretPosition(pane.getDocument().getLength())
  }
}
