package se.alipsa.lca.repl

import groovy.transform.CompileStatic
import org.jline.reader.EOFError
import org.jline.reader.ParsedLine
import org.jline.reader.Parser
import org.jline.reader.impl.DefaultParser

/**
 * Extends DefaultParser to recognise two fenced multi-line block openers
 * (/paste ... /end and ^^^ ... ^^^), using JLine's own EOFError/ACCEPT_LINE
 * continuation mechanism (the same one DefaultParser already uses for
 * unclosed quotes) so LineReader.readLine() keeps reading physical lines
 * until the fence closes, then returns the whole block as one string.
 *
 * Once a fence closes, parsing is delegated to super.parse(..., COMPLETE)
 * rather than the original context: DefaultParser's own EOFError checks
 * (unclosed quotes/brackets) are gated on context != COMPLETE, so this
 * avoids pasted content that happens to contain a stray unmatched quote
 * re-opening a block the user just explicitly closed.
 */
@CompileStatic
class FencedPasteParser extends DefaultParser {

  @Override
  ParsedLine parse(String line, int cursor, Parser.ParseContext context) {
    if (context == Parser.ParseContext.ACCEPT_LINE) {
      String opener = FencedPasteMarkers.openerOf(line)
      if (opener != null) {
        if (!FencedPasteMarkers.isClosed(line)) {
          String closer = FencedPasteMarkers.CLOSERS.get(opener)
          throw new EOFError(-1, -1, "Paste block open, end with a line containing only '${closer}'", closer)
        }
        return super.parse(line, cursor, Parser.ParseContext.COMPLETE)
      }
    }
    super.parse(line, cursor, context)
  }
}
