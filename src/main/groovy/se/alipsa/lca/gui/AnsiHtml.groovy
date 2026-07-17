package se.alipsa.lca.gui

import groovy.transform.CompileStatic

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Translates ANSI SGR (Select Graphic Rendition) escape codes into equivalent inline HTML markup,
 * so command output that colorizes text for a terminal (e.g. {@code /review}'s severity labels,
 * via {@code ShellCommands.colorSeverity}) still shows color in the GUI instead of the literal
 * escape-code garbage a non-ANSI-capable {@link javax.swing.JEditorPane} would otherwise render.
 *
 * <p>Callers must apply this to an already-finalised HTML string/fragment — after Markdown
 * rendering or HTML-escaping, never before — so the inserted {@code <font>} tags land in the
 * actual HTML the pane displays rather than being treated as literal text by a later escaping
 * step, or as raw-HTML-in-a-code-fence that Markdown wouldn't honour.
 *
 * <p>Uses the classic {@code <font color>} tag rather than a CSS {@code style} attribute, since
 * {@code javax.swing.text.html.HTMLEditorKit}'s HTML 3.2 engine supports it more reliably. Only
 * the codes this app actually emits are mapped; anything else is dropped silently rather than
 * left as visible garbage — this isn't meant to be a full terminal emulator.
 */
@CompileStatic
class AnsiHtml {

  // The ANSI escape character, built from its numeric code point rather than embedding a literal
  // control byte in the source: an invisible byte is unreviewable in a diff and easy to corrupt
  // via copy/paste, re-encoding, or editor settings.
  private static final String ESC = String.valueOf((char) 27)
  private static final Pattern SGR = Pattern.compile(ESC + "\\[(\\d+(?:;\\d+)*)m")

  private static final Map<String, String> OPEN_TAG_BY_CODE = [
    "31": '<font color="#e06c75">', // red    -- ShellCommands.colorSeverity HIGH
    "33": '<font color="#e5c07b">', // yellow -- ShellCommands.colorSeverity MEDIUM
  ]

  private AnsiHtml() {}

  /** {@code text} unchanged if it has no ANSI escapes; otherwise with SGR codes replaced by HTML. */
  static String translate(String text) {
    if (text == null || !text.contains(ESC + "[")) {
      return text
    }
    Matcher matcher = SGR.matcher(text)
    StringBuilder result = new StringBuilder()
    int lastEnd = 0
    int openTags = 0
    while (matcher.find()) {
      result.append(text, lastEnd, matcher.start())
      for (String code : matcher.group(1).split(";")) {
        if (code == "0") {
          while (openTags > 0) {
            result.append("</font>")
            openTags--
          }
        } else {
          String openTag = OPEN_TAG_BY_CODE.get(code)
          if (openTag != null) {
            result.append(openTag)
            openTags++
          }
        }
      }
      lastEnd = matcher.end()
    }
    result.append(text, lastEnd, text.length())
    while (openTags > 0) {
      result.append("</font>")
      openTags--
    }
    result.toString()
  }
}
