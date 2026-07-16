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
}
