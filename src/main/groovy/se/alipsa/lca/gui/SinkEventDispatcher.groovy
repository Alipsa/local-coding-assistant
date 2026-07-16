package se.alipsa.lca.gui

import groovy.transform.CompileStatic

/**
 * Applies a {@link SinkEvent} to a {@link ConversationView}. Pure mapping with no threading of its
 * own — the caller ({@code LcaMainFrame}'s worker) invokes it on the EDT.
 */
@CompileStatic
class SinkEventDispatcher {

  static void apply(ConversationView view, SinkEvent event) {
    switch (event.kind) {
      case SinkEvent.Kind.NOTE:
        view.addNote(event.text)
        break
      case SinkEvent.Kind.BLOCK_BEGIN:
        view.beginBlock()
        break
      case SinkEvent.Kind.BLOCK_APPEND:
        view.appendBlock(event.text)
        break
      case SinkEvent.Kind.BLOCK_END:
        view.endBlock()
        break
      case SinkEvent.Kind.MESSAGE:
        view.addAssistantMessage(event.text)
        break
    }
  }
}
