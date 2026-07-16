package se.alipsa.lca.gui

import groovy.transform.CompileStatic
import groovy.transform.Immutable

/**
 * One streaming event produced during a GUI turn, published from the background worker and
 * applied to the {@link ConversationView} on the EDT by {@link SinkEventDispatcher}.
 */
@Immutable
@CompileStatic
class SinkEvent {
  enum Kind { NOTE, BLOCK_BEGIN, BLOCK_APPEND, BLOCK_END, MESSAGE }
  Kind kind
  String text

  /** A routing / status / error line. */
  static SinkEvent note(String text) { new SinkEvent(Kind.NOTE, text) }

  /** Open a live block (no payload). */
  static SinkEvent blockBegin() { new SinkEvent(Kind.BLOCK_BEGIN, null) }

  /** Append one line to the open live block. */
  static SinkEvent blockAppend(String line) { new SinkEvent(Kind.BLOCK_APPEND, line) }

  /** Close the live block (no payload). */
  static SinkEvent blockEnd() { new SinkEvent(Kind.BLOCK_END, null) }

  /** A completed Markdown message chunk. */
  static SinkEvent message(String markdown) { new SinkEvent(Kind.MESSAGE, markdown) }
}
