package se.alipsa.lca.gui

import spock.lang.Specification

class AnsiHtmlSpec extends Specification {

  // Mirrors AnsiHtml.ESC: built from its numeric code point rather than an invisible literal
  // control byte in the test source, which is unreviewable in a diff.
  private static final String ESC = String.valueOf((char) 27)

  def "text without ANSI escapes is returned unchanged"() {
    expect:
    AnsiHtml.translate("plain text") == "plain text"
    AnsiHtml.translate(null) == null
  }

  def "translates the red HIGH severity marker exactly like ShellCommands.colorSeverity emits it"() {
    expect:
    AnsiHtml.translate("${ESC}[31mHIGH${ESC}[0m") == '<font color="#e06c75">HIGH</font>'
  }

  def "translates the yellow MEDIUM severity marker"() {
    expect:
    AnsiHtml.translate("${ESC}[33mMEDIUM${ESC}[0m") == '<font color="#e5c07b">MEDIUM</font>'
  }

  def "leaves surrounding text untouched, only replacing the escape codes"() {
    expect:
    AnsiHtml.translate("- [${ESC}[31mHIGH${ESC}[0m] path/to/file.sql:64") ==
      '- [<font color="#e06c75">HIGH</font>] path/to/file.sql:64'
  }

  def "an unclosed color code is still closed at the end of the string"() {
    expect:
    AnsiHtml.translate("${ESC}[31mHIGH") == '<font color="#e06c75">HIGH</font>'
  }

  def "an unrecognised SGR code is dropped, not left as visible garbage"() {
    expect:
    AnsiHtml.translate("${ESC}[1mBOLD${ESC}[0m") == "BOLD"
  }

  def "no literal escape bytes or bracket-digit-m sequences survive in the output"() {
    given:
    String translated = AnsiHtml.translate("${ESC}[31mHIGH${ESC}[0m and ${ESC}[33mMEDIUM${ESC}[0m")

    expect:
    !translated.contains(ESC)
    !translated.contains("[31m")
    !translated.contains("[33m")
    !translated.contains("[0m")
  }
}
