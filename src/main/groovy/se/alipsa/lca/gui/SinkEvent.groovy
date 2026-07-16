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
}
