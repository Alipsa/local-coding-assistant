package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

/**
 * Handles {@code !}-prefixed input as a direct shell command, shared by the REPL and the GUI so
 * behaviour is identical. The command is run verbatim via {@link ShellCommands#shellCommand}
 * (a {@code bash -lc} subshell rooted at the current {@link se.alipsa.lca.tools.Workspace} base
 * dir), which streams output, applies {@code CommandPolicy}, and records the command plus its
 * (summarised) output into the session conversation so the assistant sees it on the next turn.
 *
 * <p>There is deliberately no {@code cd}-to-change-base-dir handling: a {@code cd} inside a
 * {@code !} command only affects that subshell. The session base dir is changed elsewhere
 * (the GUI header's folder chooser).
 */
@Component
@CompileStatic
class BangCommandHandler {

  static final String BANG = "!"

  private final ShellCommands shellCommands

  BangCommandHandler(ShellCommands shellCommands) {
    this.shellCommands = shellCommands
  }

  /** Whether the input is a {@code !}-prefixed shell command. */
  boolean isBang(String input) {
    input != null && input.trim().startsWith(BANG)
  }

  /**
   * Run the command after the leading {@code !}. When {@code captureOutput} is true the command
   * output is returned as text (for the GUI, where stdout is not visible); otherwise it streams
   * live to the console and a concise summary is returned (for the REPL). History/conversation
   * logging is shared with chat under the given session id.
   */
  String handle(String input, String session, boolean captureOutput) {
    if (!isBang(input)) {
      return ""
    }
    String command = input.trim().substring(BANG.length()).trim()
    if (command.isEmpty()) {
      return "Usage: ! <shell command>"
    }
    String sessionId = session ?: "default"
    captureOutput
      ? shellCommands.shellCommandCaptured(command, sessionId)
      : shellCommands.shellCommand(command, sessionId)
  }
}
