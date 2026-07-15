package se.alipsa.lca.repl

import groovy.transform.CompileStatic

/**
 * Pure marker-matching logic for the fenced multi-line paste modes
 * (/paste ... /end and ^^^ ... ^^^). No JLine or terminal dependency, so
 * both FencedPasteParser (deciding whether to keep reading) and JLineRepl
 * (extracting the final block's content) share one definition of what
 * counts as an opener/closer pair, instead of duplicating the marker set.
 */
@CompileStatic
class FencedPasteMarkers {

  static final Map<String, String> CLOSERS = [
    "/paste": "/end",
    "^^^"   : "^^^"
  ].asImmutable()

  static String firstLineOf(String buffer) {
    int idx = buffer.indexOf('\n')
    idx == -1 ? buffer : buffer.substring(0, idx)
  }

  static String lastLineOf(String buffer) {
    int idx = buffer.lastIndexOf('\n')
    idx == -1 ? buffer : buffer.substring(idx + 1)
  }

  static String openerOf(String buffer) {
    if (buffer == null) {
      return null
    }
    String firstLine = firstLineOf(buffer).trim()
    CLOSERS.containsKey(firstLine) ? firstLine : null
  }

  static boolean isClosed(String buffer) {
    String opener = openerOf(buffer)
    opener != null && lastLineOf(buffer).trim() == CLOSERS.get(opener)
  }

  static String extractContent(String buffer) {
    if (buffer == null || !isClosed(buffer)) {
      return null
    }
    List<String> lines = buffer.split("\n", -1) as List<String>
    lines.subList(1, lines.size() - 1).join("\n")
  }
}
