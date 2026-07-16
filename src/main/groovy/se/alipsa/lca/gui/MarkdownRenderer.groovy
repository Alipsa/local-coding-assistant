package se.alipsa.lca.gui

import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

/**
 * Renders assistant replies (Markdown) to a self-contained HTML document suitable for a
 * Swing {@code JEditorPane}. The bundled CSS gives the OpenCode-like look asked for in
 * {@code docs/gui.md} within the HTML 3.2 / CSS subset that {@code JEditorPane} supports.
 */
@Component
@CompileStatic
class MarkdownRenderer {

  private static final String CSS = '''
    body { font-family: sans-serif; font-size: 12px; color: #dcdcdc; margin: 2px 4px; }
    h1, h2, h3, h4 { color: #7fd1ff; margin: 8px 0 4px 0; }
    p { margin: 4px 0; }
    code { font-family: monospace; background-color: #2b2b2b; color: #e6db74; }
    pre { font-family: monospace; background-color: #2b2b2b; color: #f8f8f2; padding: 6px; }
    a { color: #7fd1ff; }
    ul, ol { margin: 4px 0 4px 18px; }
    blockquote { color: #a6a6a6; margin: 4px 0 4px 8px; }
  '''

  private final Parser parser
  private final HtmlRenderer renderer

  MarkdownRenderer() {
    MutableDataSet options = new MutableDataSet()
    this.parser = Parser.builder(options).build()
    this.renderer = HtmlRenderer.builder(options).build()
  }

  /**
   * Convert Markdown to a full HTML document. A {@code null} input renders an empty body.
   */
  String toHtml(String markdown) {
    "<html><head><style>${CSS}</style></head><body>${renderBody(markdown)}</body></html>".toString()
  }

  /**
   * Render Markdown to an HTML body fragment (no {@code <html>}/{@code <style>} wrapper), for
   * composing into a larger transcript document. A {@code null} input renders an empty string.
   */
  String renderBody(String markdown) {
    markdown == null ? "" : renderer.render(parser.parse(markdown))
  }

  /** The CSS used by {@link #toHtml}, exposed so the transcript view can reuse it. */
  String css() {
    CSS
  }

  /** Escape the HTML metacharacters in plain text so it renders literally. */
  static String escapeHtml(String text) {
    text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
  }
}
