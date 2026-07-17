package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.util.function.Consumer

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
    handle(input, session, captureOutput, null)
  }

  String handle(String input, String session, boolean captureOutput, Consumer<String> lineConsumer) {
    if (!isBang(input)) {
      return ""
    }
    if (!captureOutput && lineConsumer != null) {
      // The console path (captureOutput=false) has no way to forward a line consumer — it streams
      // to stdout instead. Reject the combination loudly rather than silently dropping the consumer.
      throw new IllegalArgumentException("lineConsumer is only supported when captureOutput is true")
    }
    String command = strip(input)
    if (command.isEmpty()) {
      return "Usage: ! <shell command>"
    }
    String sessionId = session ?: "default"
    if (captureOutput) {
      // Preserve the plain 3-arg handle's existing call shape (2-arg overload) when there is no
      // line consumer, so callers that never stream keep hitting shellCommandCaptured(cmd, session).
      return lineConsumer != null
        ? shellCommands.shellCommandCaptured(command, sessionId, lineConsumer)
        : shellCommands.shellCommandCaptured(command, sessionId)
    }
    // Console path streams live to stdout; the line consumer is not used there.
    shellCommands.shellCommand(command, sessionId)
  }

  /** The shell command after the leading {@code !}, trimmed. */
  String strip(String input) {
    input.trim().substring(BANG.length()).trim()
  }
}
