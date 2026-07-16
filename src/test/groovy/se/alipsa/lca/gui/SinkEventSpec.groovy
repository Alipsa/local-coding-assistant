package se.alipsa.lca.gui

import spock.lang.Specification

class SinkEventSpec extends Specification {

  def "carries kind and text"() {
    when:
    SinkEvent e = new SinkEvent(SinkEvent.Kind.BLOCK_APPEND, "line one")

    then:
    e.kind == SinkEvent.Kind.BLOCK_APPEND
    e.text == "line one"
  }

  def "text may be null for block boundaries"() {
    expect:
    new SinkEvent(SinkEvent.Kind.BLOCK_BEGIN, null).text == null
  }

  def "factories build the events the streaming worker publishes"() {
    expect:
    SinkEvent.note("routing") == new SinkEvent(SinkEvent.Kind.NOTE, "routing")
    SinkEvent.blockBegin() == new SinkEvent(SinkEvent.Kind.BLOCK_BEGIN, null)
    SinkEvent.blockAppend("out line") == new SinkEvent(SinkEvent.Kind.BLOCK_APPEND, "out line")
    SinkEvent.blockEnd() == new SinkEvent(SinkEvent.Kind.BLOCK_END, null)
    SinkEvent.message("**done**") == new SinkEvent(SinkEvent.Kind.MESSAGE, "**done**")
  }
}
