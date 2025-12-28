package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.springframework.shell.context.InteractionMode
import org.springframework.shell.context.ShellContext

@CompileStatic
class ShellOutputStyler {

  static final String LIGHT_YELLOW = "\u001B[93m"
  static final String RESET = "\u001B[0m"

  boolean shouldColour(ShellContext context) {
    if (context == null) {
      return false
    }
    InteractionMode mode = context.interactionMode
    if (mode != InteractionMode.INTERACTIVE && mode != InteractionMode.ALL) {
      return false
    }
    context.hasPty()
  }

  String colourise(String value) {
    if (value == null || value.isEmpty()) {
      return value
    }
    StringBuilder builder = new StringBuilder()
    builder.append(LIGHT_YELLOW)
    int index = 0
    int resetIndex = value.indexOf(RESET, index)
    while (resetIndex >= 0) {
      builder.append(value, index, resetIndex + RESET.length())
      builder.append(LIGHT_YELLOW)
      index = resetIndex + RESET.length()
      resetIndex = value.indexOf(RESET, index)
    }
    if (index < value.length()) {
      builder.append(value.substring(index))
    }
    builder.append(RESET)
    builder.toString()
  }
}
