package se.alipsa.lca.gui

import groovy.transform.Canonical
import groovy.transform.CompileStatic

/**
 * The outcome of processing one GUI turn.
 *
 * @param note       optional routing / confidence / error line to show above the reply (may be null)
 * @param output     combined assistant/command output (may be null or empty)
 * @param understood {@code false} when intent routing produced no runnable command
 * @param action     a UI-level action the frame must perform (exit/clear); defaults to NONE
 */
@Canonical
@CompileStatic
class TurnResult {
  String note
  String output
  boolean understood
  GuiAction action = GuiAction.NONE
}
