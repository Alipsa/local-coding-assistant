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
}
