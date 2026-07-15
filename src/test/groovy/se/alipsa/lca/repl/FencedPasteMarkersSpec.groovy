package se.alipsa.lca.repl

import spock.lang.Specification

class FencedPasteMarkersSpec extends Specification {

  def "openerOf recognises /paste and ^^^ as the sole first line"() {
    expect:
    FencedPasteMarkers.openerOf("/paste") == "/paste"
    FencedPasteMarkers.openerOf("^^^") == "^^^"
    FencedPasteMarkers.openerOf("/paste\nsome content") == "/paste"
    FencedPasteMarkers.openerOf("^^^\nsome content\n^^^") == "^^^"
  }

  def "openerOf ignores non-opener first lines, including /paste with extra args on the same line"() {
    expect:
    FencedPasteMarkers.openerOf("/paste --content foo") == null
    FencedPasteMarkers.openerOf("hello world") == null
    FencedPasteMarkers.openerOf(null) == null
  }

  def "isClosed requires a matching closer as the last line"() {
    expect:
    FencedPasteMarkers.isClosed("/paste\nsome content\n/end") == true
    FencedPasteMarkers.isClosed("^^^\nsome content\n^^^") == true
    FencedPasteMarkers.isClosed("/paste\nsome content") == false
    FencedPasteMarkers.isClosed("/paste\n/end\nmore after the closer") == false
  }

  def "isClosed treats a mismatched closer as still open"() {
    expect:
    FencedPasteMarkers.isClosed("/paste\n^^^") == false
    FencedPasteMarkers.isClosed("^^^\n/end") == false
  }

  def "isClosed does not treat a bare single-line ^^^ opener as already closed"() {
    expect:
    FencedPasteMarkers.isClosed("^^^") == false
    FencedPasteMarkers.extractContent("^^^") == null
    FencedPasteMarkers.isClosed("^^^\nfoo\n^^^") == true
    FencedPasteMarkers.isClosed("^^^\n^^^") == true
    FencedPasteMarkers.extractContent("^^^\n^^^") == ""
  }

  def "extractContent strips marker lines and preserves inner blank lines"() {
    expect:
    FencedPasteMarkers.extractContent("/paste\nline one\n\nline two\n/end") == "line one\n\nline two"
    FencedPasteMarkers.extractContent("^^^\nfoo\n^^^") == "foo"
  }

  def "extractContent returns empty string for an empty block"() {
    expect:
    FencedPasteMarkers.extractContent("/paste\n/end") == ""
  }

  def "extractContent returns null when the block is not closed or has no opener"() {
    expect:
    FencedPasteMarkers.extractContent("/paste\nno closer yet") == null
    FencedPasteMarkers.extractContent("just plain text") == null
  }
}
