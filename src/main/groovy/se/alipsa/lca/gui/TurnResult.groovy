package se.alipsa.lca.gui

import groovy.transform.Canonical
import groovy.transform.CompileStatic

/**
 * The control-state outcome of one GUI turn. Textual content is delivered through the
 * {@link TurnSink} as it is produced, so this only carries what the frame must act on.
 *
 * @param understood {@code false} when intent routing produced no runnable command
 * @param action     a UI-level action the frame must perform (exit/clear); defaults to NONE
 */
@Canonical
@CompileStatic
class TurnResult {
  boolean understood
  GuiAction action = GuiAction.NONE
}
