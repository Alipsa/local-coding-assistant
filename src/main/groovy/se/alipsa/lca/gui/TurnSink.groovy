package se.alipsa.lca.gui

import groovy.transform.CompileStatic

/**
 * Streaming target for one GUI turn. Implementations marshal these calls onto the EDT; callers
 * (the turn pipeline) may invoke {@code append} concurrently from more than one background thread
 * (stdout and stderr reader threads), so implementations must tolerate concurrent calls.
 */
@CompileStatic
interface TurnSink {
  /** A routing / confidence / status / error line shown above content. */
  void note(String text)
  /** Open a live block (rendered as a fenced code block) for streamed shell output. */
  void beginBlock()
  /** Append one line to the currently open live block. */
  void append(String line)
  /** Close the live block, committing it to the transcript. */
  void endBlock()
  /** A completed content chunk (e.g. one command's result), rendered as Markdown. */
  void message(String markdown)
}
