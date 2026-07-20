package se.alipsa.lca.memory

import groovy.transform.CompileStatic

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shared by both places recalled memories reach the model - MemoryPromptContributor
 * (automatic pre-turn injection) and recallMemory() (explicit tool) - so the
 * recallMaxContextChars cap applies exactly once and can't diverge between the two paths.
 */
@CompileStatic
class RecalledMemoryFormatter {

  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)

  private RecalledMemoryFormatter() {
  }

  /**
   * recalled is assumed already sorted by score desc (recall()'s contract). Builds
   * "[createdAt, similarity score] content" lines, appending in that order and stopping
   * (not trimming mid-line) once the next line would push the joined result over maxChars -
   * i.e. keep highest-scored first, drop the rest, same behaviour regardless of caller.
   * Returns "" if recalled is empty.
   */
  static String render(List<RecalledMemory> recalled, int maxChars) {
    if (!recalled) {
      return ""
    }
    StringBuilder builder = new StringBuilder()
    for (RecalledMemory recalledMemory : recalled) {
      String line = formatLine(recalledMemory)
      int separatorLength = builder.length() > 0 ? 1 : 0
      if (builder.length() + separatorLength + line.length() > maxChars) {
        break
      }
      if (separatorLength > 0) {
        builder.append('\n')
      }
      builder.append(line)
    }
    builder.toString()
  }

  private static String formatLine(RecalledMemory recalledMemory) {
    String date = DATE_FORMAT.format(recalledMemory.entry.createdAt)
    String score = String.format(Locale.ROOT, "%.2f", recalledMemory.score)
    "[${date}, similarity ${score}] ${recalledMemory.entry.content}"
  }
}
