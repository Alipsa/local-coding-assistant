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
}
