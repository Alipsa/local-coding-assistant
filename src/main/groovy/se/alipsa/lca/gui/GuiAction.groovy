package se.alipsa.lca.gui

import groovy.transform.CompileStatic

/**
 * A UI-level action a turn may request of the main frame, for built-in commands that the GUI
 * itself must carry out (rather than the assistant).
 */
@CompileStatic
enum GuiAction {
  NONE,
  EXIT,
  CLEAR
}
