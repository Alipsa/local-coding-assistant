package se.alipsa.lca.gui

import spock.lang.Specification

class MarkdownRendererSpec extends Specification {

  MarkdownRenderer renderer = new MarkdownRenderer()

  def "renders headings"() {
    expect:
    renderer.toHtml("# Title").contains("<h1")
  }

  def "renders fenced code blocks"() {
    when:
    String html = renderer.toHtml("```\nsome code\n```")

    then:
    html.contains("<pre")
    html.contains("<code")
  }

  def "renders list items"() {
    expect:
    renderer.toHtml("- a\n- b").contains("<li")
  }

  def "null markdown renders an empty body"() {
    expect:
    renderer.toHtml(null).contains("<body></body>")
  }

  def "full document includes the stylesheet"() {
    expect:
    renderer.toHtml("x").contains("<style>")
  }

  def "escapeHtml escapes metacharacters"() {
    expect:
    MarkdownRenderer.escapeHtml("<a> & <b>") == "&lt;a&gt; &amp; &lt;b&gt;"
  }

  // Mirrors AnsiHtml.ESC: built from its numeric code point rather than an invisible literal
  // control byte in the test source, which is unreviewable in a diff.
  private static final String ESC = String.valueOf((char) 27)

  def "a colorized severity label in a list item renders as HTML color, not raw escape codes"() {
    when:
    String html = renderer.renderBody("- [${ESC}[31mHIGH${ESC}[0m] some/file.sql:64")

    then:
    html.contains('<font color="#e06c75">HIGH</font>')
    !html.contains(ESC)
  }

  def "a colorized label inside a fenced code block still renders as HTML color"() {
    // Raw HTML embedded in Markdown source is NOT honoured inside a code fence, so this only
    // works because AnsiHtml runs on the rendered HTML, after flexmark has already emitted the
    // <pre><code> block -- not on the Markdown source before parsing.
    when:
    String html = renderer.renderBody("```\n[${ESC}[31mHIGH${ESC}[0m]\n```")

    then:
    html.contains("<pre")
    html.contains('<font color="#e06c75">HIGH</font>')
    !html.contains(ESC)
  }
}
