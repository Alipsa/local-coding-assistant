package se.alipsa.lca.gui

import spock.lang.Specification

class ConversationViewSpec extends Specification {

  ConversationView view = new ConversationView(new MarkdownRenderer())

  def "an open block shows appended lines, escaped"() {
    when:
    view.beginBlock()
    view.appendBlock('$ echo <hi>')
    view.appendBlock("<hi>")

    then:
    String html = view.snapshotHtml()
    html.contains("&lt;hi&gt;")      // HTML-escaped
    !html.contains("<hi>")           // raw angle brackets never leak into the document
  }

  def "endBlock commits the block so later content follows it"() {
    when:
    view.beginBlock()
    view.appendBlock("line-1")
    view.endBlock()
    view.addNote("after")

    then:
    String html = view.snapshotHtml()
    html.contains("line-1")
    html.indexOf("line-1") < html.indexOf("after")
  }

  def "clear removes committed blocks"() {
    given:
    view.beginBlock()
    view.appendBlock("gone")
    view.endBlock()

    when:
    view.clear()

    then:
    !view.snapshotHtml().contains("gone")
  }

  // Mirrors AnsiHtml.ESC: built from its numeric code point rather than an invisible literal
  // control byte in the test source, which is unreviewable in a diff.
  private static final String ESC = String.valueOf((char) 27)

  def "a streamed block line with a colorized severity label shows color, not raw escape codes"() {
    when:
    view.beginBlock()
    view.appendBlock("[${ESC}[31mHIGH${ESC}[0m] some finding")
    view.endBlock()

    then:
    String html = view.snapshotHtml()
    html.contains('<font color="#e06c75">HIGH</font>')
    !html.contains(ESC)
  }

  def "a note with a colorized label shows color, not raw escape codes"() {
    when:
    view.addNote("[${ESC}[33mMEDIUM${ESC}[0m] some warning")

    then:
    String html = view.snapshotHtml()
    html.contains('<font color="#e5c07b">MEDIUM</font>')
    !html.contains(ESC)
  }
}
